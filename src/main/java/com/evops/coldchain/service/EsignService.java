package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.EsignAdjudicateRequest;
import com.evops.coldchain.dto.EsignCorrectRequest;
import com.evops.coldchain.dto.EsignPhotoItem;
import com.evops.coldchain.dto.EsignSyncRequest;
import com.evops.coldchain.dto.EsignUploadRequest;
import com.evops.coldchain.dto.SignRequest;
import com.evops.coldchain.entity.EsignRequest;
import com.evops.coldchain.entity.SignCompensation;
import com.evops.coldchain.entity.SignCorrection;
import com.evops.coldchain.entity.SignReceipt;
import com.evops.coldchain.entity.SignReceiptPhoto;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.EsignRequestMapper;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.coldchain.mapper.SignCompensationMapper;
import com.evops.coldchain.mapper.SignCorrectionMapper;
import com.evops.coldchain.mapper.SignReceiptMapper;
import com.evops.coldchain.mapper.SignReceiptPhotoMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 电子签收与离线回执服务（V12）。
 *
 * 业务规则：
 * - 司机离线时在端上保存签收草稿并生成回执编号；恢复网络后签名、照片、定位可分多次
 *   上传，服务端按回执编号合并进同一条回执（同一回执编号核心内容不一致直接拒绝）。
 * - 回执要素（签收人/设备时间/签名/照片/定位/核对字段）齐全后 DRAFT -> COMPLETE，
 *   并按既有签收卡口（快照核对/未关闭异常/交接核验/权限收紧/改期待审批）尝试激活批次签收。
 * - 同一批次出现两份内容不同的签收（COMPLETE/EFFECTIVE 且内容摘要不一致）即冻结批次
 *   （FROZEN，记录冻结前状态），签收/结算/撤回停止，由主管裁决选定生效回执；
 *   内容一致的重复回执置 DUPLICATE，不触发冻结。
 * - 签收时间并列展示：设备时间（deviceTime）与服务器时间（serverTime）同时落库并列返回。
 * - 人工修正只追加修正记录（t_cc_sign_correction），原始回执行永不修改。
 * - 部分成功补偿成对对应：同步/上传部分成功时，电子签收侧（ESIGN）与离线回执侧
 *   （OFFLINE）各开一条补偿记录，pair_id 互指；回执后续完整上传成功，两侧一并闭环。
 * - 上传/同步按 requestId 幂等：同键同载荷重放返回首次结果，同键不同载荷拒绝。
 *
 * 注意：激活签收调用 TransportBatchService.sign 前先做全部前置卡口预检——
 * BusinessException 是 RuntimeException，跨 @Transactional 边界抛出会把当前事务
 * 标记为 rollback-only，因此不能在调用 sign 后用 try/catch 兜底继续提交流程。
 */
@Service
public class EsignService {

    private static final ObjectMapper M = new ObjectMapper().findAndRegisterModules();

    /** 允许人工修正的字段（修正只追加记录，不改原行） */
    private static final Set<String> CORRECTABLE_FIELDS =
            new HashSet<>(Arrays.asList("signBy", "signRemark", "locationAddress"));

    @Resource
    private SignReceiptMapper receiptMapper;
    @Resource
    private SignReceiptPhotoMapper photoMapper;
    @Resource
    private SignCorrectionMapper correctionMapper;
    @Resource
    private SignCompensationMapper compensationMapper;
    @Resource
    private EsignRequestMapper requestMapper;
    @Resource
    private TransportBatchMapper batchMapper;
    @Resource
    private SequenceMapper sequenceMapper;
    @Resource
    private TransportBatchService batchService;
    @Resource
    private PermissionService permissionService;
    @Resource
    private HandoverService handoverService;
    @Resource
    private ExceptionLifecycleService exceptionLifecycleService;
    @Resource
    private BatchRescheduleService batchRescheduleService;
    @Resource
    private OpLogService opLogService;

    // ---------------- 单回执上传（幂等入口） ----------------

    /** 单回执上传：requestId 幂等；离线草稿/补传要素按回执编号合并 */
    @Transactional
    public Map<String, Object> upload(EsignUploadRequest req) {
        if (isBlank(req.getRequestId())) {
            throw new BusinessException("幂等键requestId不能为空（离线端建议生成UUID）");
        }
        EsignRequest existing = requestMapper.selectOne(new QueryWrapper<EsignRequest>()
                .eq("request_id", req.getRequestId()));
        String payloadHash = DeviceCertService.sha256Hex(payloadOf(req));
        if (existing != null) {
            return replay(existing, payloadHash);
        }

        Map<String, Object> result = mergeReceipt(req, null);
        result.put("replay", false);
        registerRequest(req.getRequestId(), "UPLOAD", payloadHash, result);
        return result;
    }

    // ---------------- 批量同步（恢复网络后一次上传多张回执） ----------------

    /**
     * 离线回执批量同步：每张回执独立处理。全部成功 SUCCESS；有成功有失败 PARTIAL——
     * 为每张失败回执成对开立电子签收侧/离线回执侧补偿记录（pair_id 互指）。
     */
    @Transactional
    public Map<String, Object> sync(EsignSyncRequest req) {
        EsignRequest existing = requestMapper.selectOne(new QueryWrapper<EsignRequest>()
                .eq("request_id", req.getRequestId()));
        String payloadHash = DeviceCertService.sha256Hex(payloadOf(req));
        if (existing != null) {
            return replay(existing, payloadHash);
        }

        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> compensations = new ArrayList<>();
        int succeeded = 0, partial = 0, failed = 0;
        for (int i = 0; i < req.getReceipts().size(); i++) {
            EsignUploadRequest item = req.getReceipts().get(i);
            Map<String, Object> itemResult = new LinkedHashMap<>();
            itemResult.put("receiptNo", isBlank(item.getReceiptNo()) ? null : item.getReceiptNo().trim());
            try {
                Map<String, Object> r = mergeReceipt(item, req.getRequestId());
                boolean itemPartial = CcConstants.SYNC_PARTIAL.equals(r.get("result"));
                itemResult.put("success", true);
                itemResult.put("partial", itemPartial);
                itemResult.put("receiptId", r.get("receiptId"));
                itemResult.put("receiptStatus", r.get("receiptStatus"));
                itemResult.put("message", itemPartial ? "部分要素缺失，已开补偿记录" : "已合并");
                if (itemPartial) {
                    partial++;
                } else {
                    succeeded++;
                }
            } catch (BusinessException e) {
                // 回执级失败（校验在写库前抛出，本事务内无半成品行）：记录失败明细并开成对补偿
                failed++;
                itemResult.put("success", false);
                itemResult.put("partial", false);
                itemResult.put("message", e.getMessage());
                compensations.addAll(createPairedCompensation(null,
                        isBlank(item.getReceiptNo()) ? "UNASSIGNED-" + req.getRequestId() + "-" + (i + 1)
                                : item.getReceiptNo().trim(),
                        item.getBatchId(), req.getRequestId(), e.getMessage()));
            }
            items.add(itemResult);
        }

        // 整体结果：全部完整成功 SUCCESS；全部失败 FAILED；其余（含部分要素缺失）PARTIAL
        String overall = (partial == 0 && failed == 0) ? CcConstants.SYNC_SUCCESS
                : (failed == req.getReceipts().size() ? CcConstants.SYNC_FAILED : CcConstants.SYNC_PARTIAL);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", overall);
        result.put("total", req.getReceipts().size());
        result.put("succeeded", succeeded);
        result.put("partial", partial);
        result.put("failed", failed);
        result.put("items", items);
        result.put("compensations", compensations);
        result.put("replay", false);

        opLogService.log(CcConstants.ENT_ESIGN_RECEIPT, null, null, CcConstants.ACT_ESIGN_SYNC,
                String.format("离线回执批量同步 %s：共%d张，完整成功%d，部分要素缺失%d，失败%d，结果=%s%s",
                        req.getRequestId(), req.getReceipts().size(), succeeded, partial, failed, overall,
                        compensations.isEmpty() ? "" : "；已为失败/缺失回执成对开立补偿记录（电子签收侧↔离线回执侧互指）"));

        registerRequest(req.getRequestId(), "SYNC", payloadHash, result);
        return result;
    }

    // ---------------- 合并核心（按回执编号） ----------------

    /**
     * 按回执编号合并一张回执：不存在则建档（离线草稿/在线签收），存在则补齐缺失要素。
     * 要素齐全后尝试激活批次签收；发现同批内容不同的另一份签收即冻结批次待主管裁决。
     */
    private Map<String, Object> mergeReceipt(EsignUploadRequest req, String syncRequestId) {
        TransportBatch batch = requireBatch(req.getBatchId());
        if (CcConstants.BATCH_REVOKED.equals(batch.getStatus())) {
            throw new BusinessException("批次已撤回作废，不能上传签收回执: " + batch.getBatchNo());
        }

        String receiptNo = isBlank(req.getReceiptNo())
                ? String.format("ES%08d", sequenceMapper.nextSignReceiptNo())
                : req.getReceiptNo().trim();
        String source = CcConstants.ESIGN_OFFLINE.equalsIgnoreCase(nz(req.getSource()).trim())
                ? CcConstants.ESIGN_OFFLINE : CcConstants.ESIGN_ONLINE;

        // 照片先校验（部分成功口径：有效张落库，无效张跳过并开补偿）
        List<EsignPhotoItem> validPhotos = new ArrayList<>();
        List<String> failedPhotos = new ArrayList<>();
        if (req.getPhotos() != null) {
            for (int i = 0; i < req.getPhotos().size(); i++) {
                EsignPhotoItem p = req.getPhotos().get(i);
                if (p == null || isBlank(p.getPhotoName()) || isBlank(p.getPhotoUrl())) {
                    failedPhotos.add("第" + (i + 1) + "张（名称/地址缺失）");
                } else {
                    validPhotos.add(p);
                }
            }
        }

        LocalDateTime now = LocalDateTime.now();
        SignReceipt receipt = receiptMapper.selectOne(new QueryWrapper<SignReceipt>()
                .eq("receipt_no", receiptNo));
        boolean created = receipt == null;
        if (created) {
            receipt = new SignReceipt();
            receipt.setReceiptNo(receiptNo);
            receipt.setBatchId(batch.getId());
            receipt.setSource(source);
            receipt.setServerTime(now);
            receipt.setStatus(CcConstants.RECEIPT_DRAFT);
            applyElements(receipt, req);
            receipt.setContentHash(contentHash(receipt));
            receiptMapper.insert(receipt);
        } else {
            if (CcConstants.RECEIPT_REJECTED.equals(receipt.getStatus())) {
                throw new BusinessException(String.format(
                        "回执 %s 已经主管裁决作废（事实保留），不能再合并上传；如有异议请主管复核裁决", receiptNo));
            }
            requireSameContent(receipt, req);
            applyElements(receipt, req);
            receipt.setContentHash(contentHash(receipt));
            receiptMapper.updateById(receipt);
        }

        // 照片只追加：同回执内 URL 去重（重传幂等）
        Set<String> existingUrls = new HashSet<>();
        for (SignReceiptPhoto p : listPhotos(receipt.getId())) {
            existingUrls.add(p.getPhotoUrl());
        }
        int photoSeq = existingUrls.size();
        int appended = 0;
        for (EsignPhotoItem p : validPhotos) {
            String url = p.getPhotoUrl().trim();
            if (existingUrls.contains(url)) {
                continue;
            }
            SignReceiptPhoto photo = new SignReceiptPhoto();
            photo.setReceiptId(receipt.getId());
            photo.setBatchId(batch.getId());
            photo.setPhotoSeq(++photoSeq);
            photo.setPhotoName(p.getPhotoName().trim());
            photo.setPhotoUrl(url);
            photo.setSha256(isBlank(p.getSha256()) ? null : p.getSha256().trim());
            photo.setDeviceTime(p.getDeviceTime());
            photoMapper.insert(photo);
            existingUrls.add(url);
            appended++;
        }

        // 要素齐全：DRAFT -> COMPLETE
        boolean justCompleted = false;
        if (CcConstants.RECEIPT_DRAFT.equals(receipt.getStatus()) && isComplete(receipt)) {
            receipt.setStatus(CcConstants.RECEIPT_COMPLETE);
            receiptMapper.updateById(receipt);
            justCompleted = true;
        }

        // 激活/冲突判定（仅要素齐全的回执参与“两份不同签收”判定）
        boolean frozen = false;
        boolean duplicate = false;
        String pendingReason = null;
        if (CcConstants.RECEIPT_COMPLETE.equals(receipt.getStatus())) {
            List<SignReceipt> others = receiptMapper.selectList(new QueryWrapper<SignReceipt>()
                    .eq("batch_id", batch.getId())
                    .in("status", CcConstants.RECEIPT_COMPLETE, CcConstants.RECEIPT_EFFECTIVE)
                    .ne("id", receipt.getId()));
            boolean conflict = false;
            for (SignReceipt other : others) {
                if (!receipt.getContentHash().equals(other.getContentHash())) {
                    conflict = true;
                    break;
                }
            }
            if (conflict) {
                frozen = true;
                freezeBatch(batch, receipt, others);
            } else if (!others.isEmpty()) {
                // 内容一致的重复回执：置 DUPLICATE，不触发冻结、不重复签收
                duplicate = true;
                receipt.setStatus(CcConstants.RECEIPT_DUPLICATE);
                receiptMapper.updateById(receipt);
                opLogService.log(CcConstants.ENT_ESIGN_RECEIPT, receipt.getId(), batch.getId(),
                        CcConstants.ACT_ESIGN_UPLOAD,
                        String.format("回执 %s 与批次 %s 既有回执 %s 内容一致，置为重复件（不触发冻结、不重复签收）",
                                receipt.getReceiptNo(), batch.getBatchNo(), others.get(0).getReceiptNo()));
            } else {
                pendingReason = tryActivate(batch, receipt);
            }
        }

        // 补偿：本次上传部分要素缺失 -> 成对开立；本次完整成功 -> 闭环该回执既有补偿
        List<Map<String, Object>> compensations = new ArrayList<>();
        String resultCode;
        if (!failedPhotos.isEmpty()) {
            compensations.addAll(createPairedCompensation(receipt.getId(), receiptNo, batch.getId(),
                    syncRequestId == null ? req.getRequestId() : syncRequestId,
                    "部分照片要素缺失未上传成功：" + String.join("、", failedPhotos)));
            resultCode = CcConstants.SYNC_PARTIAL;
        } else {
            resolveCompensations(receiptNo, "回执 " + receiptNo + " 已完整上传合并成功");
            resultCode = CcConstants.SYNC_SUCCESS;
        }

        opLogService.log(CcConstants.ENT_ESIGN_RECEIPT, receipt.getId(), batch.getId(),
                CcConstants.ACT_ESIGN_UPLOAD,
                String.format("批次 %s %s回执 %s %s：签名=%s，照片+%d（累计%d），定位=%s，设备时间=%s，服务器时间=%s，状态=%s%s%s%s",
                        batch.getBatchNo(),
                        CcConstants.ESIGN_OFFLINE.equals(receipt.getSource()) ? "离线" : "在线",
                        receiptNo, created ? "建档（离线草稿/在线签收）" : "按回执编号合并",
                        receipt.getSignatureUrl() == null ? "未传" : "已传",
                        appended, existingUrls.size(),
                        receipt.getLocationAddress() == null ? "未传" : receipt.getLocationAddress(),
                        receipt.getDeviceTime(), receipt.getServerTime(), receipt.getStatus(),
                        frozen ? "；发现同批另一份不同签收，批次已冻结待主管裁决" : "",
                        duplicate ? "；内容一致置为重复件" : "",
                        pendingReason == null ? "" : "；待激活：" + pendingReason));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("result", resultCode);
        result.put("receiptId", receipt.getId());
        result.put("receiptNo", receiptNo);
        result.put("receiptStatus", receipt.getStatus());
        result.put("justCompleted", justCompleted);
        result.put("activated", CcConstants.RECEIPT_EFFECTIVE.equals(receipt.getStatus()));
        result.put("frozen", frozen);
        result.put("duplicate", duplicate);
        result.put("pendingReason", pendingReason);
        result.put("failedPhotos", failedPhotos);
        result.put("compensations", compensations);
        result.put("deviceTime", receipt.getDeviceTime());
        result.put("serverTime", receipt.getServerTime());
        return result;
    }

    /**
     * 激活批次签收：先做全部前置卡口预检（任一不满足则回执停留 COMPLETE 待激活），
     * 全部通过才调用 TransportBatchService.sign 闭环（签收时间=服务器时间，
     * 设备时间留在回执上并列展示）。
     *
     * @return null=已激活生效；否则为待激活原因
     */
    private String tryActivate(TransportBatch batch, SignReceipt receipt) {
        if (CcConstants.BATCH_FROZEN.equals(batch.getStatus())) {
            return "批次存在两份不同签收已冻结，待主管裁决后生效";
        }
        if (CcConstants.BATCH_SIGNED.equals(batch.getStatus())) {
            // 批次已闭环（如先走了在线签收）：本回执作为电子凭证补录生效，不重复签收
            receipt.setStatus(CcConstants.RECEIPT_EFFECTIVE);
            receiptMapper.updateById(receipt);
            opLogService.log(CcConstants.ENT_ESIGN_RECEIPT, receipt.getId(), batch.getId(),
                    CcConstants.ACT_ESIGN_UPLOAD,
                    String.format("批次 %s 已签收闭环，回执 %s 作为电子签收凭证补录生效（设备时间=%s 与服务器时间=%s 并列留痕）",
                            batch.getBatchNo(), receipt.getReceiptNo(),
                            receipt.getDeviceTime(), receipt.getServerTime()));
            return null;
        }
        // 前置卡口预检（与 TransportBatchService.sign 同口径；不满足则停留待激活，不丢事实）
        if (!permissionService.isPermitted(CcConstants.PERM_COLD_CHAIN_HANDOVER)) {
            return "冷链药品运输权限已被收紧，签收停止推进（回执事实保留）";
        }
        if (batch.getArrivedBoxQty() != null
                && !Boolean.TRUE.equals(handoverService.progress(batch.getId()).get("readyForDispatch"))) {
            return "分仓交接尚未全部核验平衡，未完成核验的箱子不能签收";
        }
        long unclosed = exceptionLifecycleService.unclosedCount(batch.getId());
        if (unclosed > 0) {
            return "存在 " + unclosed + " 条未关闭异常，暂不能签收闭环";
        }
        if (!batch.getApprovalNoSnap().equals(receipt.getReceivedApprovalNo())) {
            return String.format("签收核对不符：实物批准文号[%s]与批次快照[%s]不一致",
                    receipt.getReceivedApprovalNo(), batch.getApprovalNoSnap());
        }
        if (!batch.getPackageSpecSnap().equals(receipt.getReceivedPackageSpec())) {
            return String.format("签收核对不符：实物包装规格[%s]与批次快照[%s]不一致",
                    receipt.getReceivedPackageSpec(), batch.getPackageSpecSnap());
        }
        if (!batch.getShipQty().equals(receipt.getConfirmQty())) {
            return String.format("签收核对不符：实收数量[%s]与发运数量[%d]不一致",
                    receipt.getConfirmQty(), batch.getShipQty());
        }
        try {
            batchRescheduleService.requireNoPending(batch.getId());
        } catch (BusinessException e) {
            return e.getMessage();
        }

        SignRequest sign = new SignRequest();
        sign.setBatchId(batch.getId());
        sign.setSignBy(receipt.getSignBy());
        sign.setReceivedApprovalNo(receipt.getReceivedApprovalNo());
        sign.setReceivedPackageSpec(receipt.getReceivedPackageSpec());
        sign.setConfirmQty(receipt.getConfirmQty());
        sign.setSignRemark(receipt.getSignRemark());
        batchService.sign(sign);

        receipt.setStatus(CcConstants.RECEIPT_EFFECTIVE);
        receiptMapper.updateById(receipt);
        opLogService.log(CcConstants.ENT_ESIGN_RECEIPT, receipt.getId(), batch.getId(),
                CcConstants.ACT_ESIGN_UPLOAD,
                String.format("回执 %s 要素齐全，批次 %s 据此电子签收闭环：设备时间=%s，服务器签收时间=%s（并列展示）",
                        receipt.getReceiptNo(), batch.getBatchNo(),
                        receipt.getDeviceTime(), receipt.getServerTime()));
        return null;
    }

    /** 同一批次出现两份不同签收：冻结批次（记录冻结前状态），待主管裁决 */
    private void freezeBatch(TransportBatch batch, SignReceipt incoming, List<SignReceipt> others) {
        if (!CcConstants.BATCH_FROZEN.equals(batch.getStatus())) {
            batch.setSignFrozenFrom(batch.getStatus());
            batch.setStatus(CcConstants.BATCH_FROZEN);
            batchMapper.updateById(batch);
        }
        StringBuilder otherNos = new StringBuilder();
        for (SignReceipt o : others) {
            if (otherNos.length() > 0) {
                otherNos.append("、");
            }
            otherNos.append(o.getReceiptNo());
        }
        opLogService.log(CcConstants.ENT_BATCH, batch.getId(), batch.getId(), CcConstants.ACT_BATCH_FREEZE,
                String.format("批次 %s 出现两份不同签收：回执 %s 与 %s 内容不一致，批次冻结（冻结前状态=%s），"
                                + "签收/结算/撤回停止，待主管裁决选定生效回执；全部回执原始记录保留",
                        batch.getBatchNo(), incoming.getReceiptNo(), otherNos,
                        batch.getSignFrozenFrom()));
    }

    // ---------------- 主管裁决 ----------------

    /**
     * 主管裁决：选定生效回执，其余 COMPLETE 回执置 REJECTED（原始行全部保留），
     * 批次按冻结前状态恢复/闭环。裁决意见只追加到回执裁决列，不改任何原始要素。
     */
    @Transactional
    public Map<String, Object> adjudicate(EsignAdjudicateRequest req) {
        TransportBatch batch = batchService.requireBatch(req.getBatchId());
        if (!CcConstants.BATCH_FROZEN.equals(batch.getStatus())) {
            throw new BusinessException("批次 " + batch.getBatchNo() + " 当前非签收冲突冻结状态，无需裁决: "
                    + batch.getStatus());
        }
        SignReceipt chosen = requireReceipt(req.getEffectiveReceiptId());
        if (!chosen.getBatchId().equals(batch.getId())) {
            throw new BusinessException(String.format(
                    "回执 %s 不属于批次 %s，不能选定为该批次的生效回执", chosen.getReceiptNo(), batch.getBatchNo()));
        }
        if (!CcConstants.RECEIPT_COMPLETE.equals(chosen.getStatus())
                && !CcConstants.RECEIPT_EFFECTIVE.equals(chosen.getStatus())) {
            throw new BusinessException(String.format(
                    "回执 %s 状态为 %s，只有要素齐全（COMPLETE）或已生效（EFFECTIVE）的回执可被裁决为生效",
                    chosen.getReceiptNo(), chosen.getStatus()));
        }

        // 其余 COMPLETE/EFFECTIVE 回执 -> REJECTED（事实保留）
        List<SignReceipt> all = listByBatch(batch.getId());
        List<String> rejected = new ArrayList<>();
        for (SignReceipt r : all) {
            if (r.getId().equals(chosen.getId())) {
                continue;
            }
            if (CcConstants.RECEIPT_COMPLETE.equals(r.getStatus())
                    || CcConstants.RECEIPT_EFFECTIVE.equals(r.getStatus())) {
                r.setStatus(CcConstants.RECEIPT_REJECTED);
                r.setAdjudicateNote("主管裁决未采纳：" + req.getReason());
                receiptMapper.updateById(r);
                rejected.add(r.getReceiptNo());
            }
        }
        chosen.setStatus(CcConstants.RECEIPT_EFFECTIVE);
        chosen.setAdjudicateNote("主管裁决生效：" + req.getReason());
        receiptMapper.updateById(chosen);

        String prevStatus = batch.getSignFrozenFrom() == null
                ? CcConstants.BATCH_SHIPPED : batch.getSignFrozenFrom();
        String signNote;
        if (CcConstants.BATCH_SIGNED.equals(prevStatus)) {
            // 冻结前已签收：裁决维持/改判生效回执；批次回到已签收
            batch.setStatus(CcConstants.BATCH_SIGNED);
            batch.setSignFrozenFrom(null);
            if (!chosen.getSignBy().equals(batch.getSignBy())) {
                // 改判：批次当前签收信息改按生效回执（原始回执全部保留，不覆盖）
                batch.setSignBy(chosen.getSignBy());
                batch.setSignRemark(chosen.getSignRemark());
                batch.setSignTime(LocalDateTime.now());
                signNote = "裁决改判：批次签收信息改按生效回执 " + chosen.getReceiptNo();
            } else {
                signNote = "裁决维持原签收：批次恢复已签收闭环";
            }
            batchMapper.updateById(batch);
        } else {
            // 冻结前未签收：先恢复冻结前状态，再按生效回执走既有签收卡口闭环
            batch.setStatus(prevStatus);
            batch.setSignFrozenFrom(null);
            batchMapper.updateById(batch);
            SignRequest sign = new SignRequest();
            sign.setBatchId(batch.getId());
            sign.setSignBy(chosen.getSignBy());
            sign.setReceivedApprovalNo(chosen.getReceivedApprovalNo());
            sign.setReceivedPackageSpec(chosen.getReceivedPackageSpec());
            sign.setConfirmQty(chosen.getConfirmQty());
            sign.setSignRemark(chosen.getSignRemark());
            batchService.sign(sign);
            signNote = "裁决生效：批次按回执 " + chosen.getReceiptNo() + " 完成签收闭环";
        }

        opLogService.log(CcConstants.ENT_ESIGN_RECEIPT, chosen.getId(), batch.getId(),
                CcConstants.ACT_ESIGN_ADJUDICATE,
                String.format("主管裁决批次 %s 签收冲突：生效回执=%s%s，裁决意见=%s；%s；全部回执原始记录保留可查",
                        batch.getBatchNo(), chosen.getReceiptNo(),
                        rejected.isEmpty() ? "" : "，作废回执=" + rejected,
                        req.getReason(), signNote));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchId", batch.getId());
        result.put("batchStatus", batch.getStatus());
        result.put("effectiveReceiptNo", chosen.getReceiptNo());
        result.put("rejectedReceiptNos", rejected);
        result.put("signNote", signNote);
        return result;
    }

    // ---------------- 人工修正（只追加，不覆盖原始回执） ----------------

    /**
     * 人工修正：只追加一条修正记录（字段/旧值/新值/原因/修正人），
     * 原始回执行不被修改；有效值由详情视图按“原值 + 最新修正”并列给出。
     */
    @Transactional
    public SignCorrection correct(EsignCorrectRequest req) {
        SignReceipt receipt = requireReceipt(req.getReceiptId());
        String field = req.getFieldName() == null ? "" : req.getFieldName().trim();
        if (!CORRECTABLE_FIELDS.contains(field)) {
            throw new BusinessException("不允许修正字段[" + field + "]：仅支持 "
                    + CORRECTABLE_FIELDS + "（设备时间/签名/照片等原始要素一律不得修正）");
        }
        String oldValue = readField(receipt, field);

        SignCorrection correction = new SignCorrection();
        correction.setReceiptId(receipt.getId());
        correction.setBatchId(receipt.getBatchId());
        correction.setFieldName(field);
        correction.setOldValue(oldValue);
        correction.setNewValue(req.getNewValue().trim());
        correction.setReason(req.getReason().trim());
        correction.setCorrectByName(UserContext.currentName());
        correctionMapper.insert(correction);

        opLogService.log(CcConstants.ENT_ESIGN_CORRECTION, correction.getId(), receipt.getBatchId(),
                CcConstants.ACT_ESIGN_CORRECT,
                String.format("回执 %s 人工修正（只追加、不覆盖原始回执）：字段=%s，原值=[%s]，新值=[%s]，原因=%s，修正人=%s",
                        receipt.getReceiptNo(), field, oldValue, req.getNewValue(),
                        req.getReason(), correction.getCorrectByName()));
        return correction;
    }

    // ---------------- 查询 ----------------

    public SignReceipt requireReceipt(Long receiptId) {
        SignReceipt receipt = receiptMapper.selectById(receiptId);
        if (receipt == null) {
            throw new BusinessException("签收回执不存在: " + receiptId);
        }
        return receipt;
    }

    public List<SignReceipt> listByBatch(Long batchId) {
        return receiptMapper.selectList(new QueryWrapper<SignReceipt>()
                .eq("batch_id", batchId).orderByAsc("id"));
    }

    public List<SignReceiptPhoto> listPhotos(Long receiptId) {
        return photoMapper.selectList(new QueryWrapper<SignReceiptPhoto>()
                .eq("receipt_id", receiptId).orderByAsc("photo_seq"));
    }

    public List<SignCorrection> listCorrections(Long receiptId) {
        return correctionMapper.selectList(new QueryWrapper<SignCorrection>()
                .eq("receipt_id", receiptId).orderByAsc("id"));
    }

    public List<SignCompensation> listCompensations(Long batchId) {
        QueryWrapper<SignCompensation> qw = new QueryWrapper<>();
        if (batchId != null) {
            qw.eq("batch_id", batchId);
        }
        qw.orderByAsc("id");
        return compensationMapper.selectList(qw);
    }

    /**
     * 回执详情：原始要素 + 照片 + 修正记录 + 补偿记录（含互指对方）+
     * 有效值视图（原值 + 最新修正）+ 设备/服务器双时间并列。
     */
    public Map<String, Object> detail(Long receiptId) {
        SignReceipt receipt = requireReceipt(receiptId);
        TransportBatch batch = batchMapper.selectById(receipt.getBatchId());
        List<SignCorrection> corrections = listCorrections(receiptId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("receipt", receipt);
        data.put("photos", listPhotos(receiptId));
        data.put("corrections", corrections);
        data.put("compensations", listCompensationsOfReceipt(receipt.getReceiptNo()));
        // 签收时间并列：设备时间 / 服务器接收时间 / 批次签收闭环时间（互不改写）
        Map<String, Object> times = new LinkedHashMap<>();
        times.put("deviceTime", receipt.getDeviceTime());
        times.put("serverTime", receipt.getServerTime());
        times.put("batchSignTime", batch == null ? null : batch.getSignTime());
        data.put("signTimes", times);
        // 有效值 = 原始值 + 最新修正（原始回执从未被覆盖）
        Map<String, Object> effective = new LinkedHashMap<>();
        for (String field : CORRECTABLE_FIELDS) {
            String value = readField(receipt, field);
            for (SignCorrection c : corrections) {
                if (field.equals(c.getFieldName())) {
                    value = c.getNewValue();
                }
            }
            effective.put(field, value);
        }
        data.put("effectiveValues", effective);
        return data;
    }

    /** 批次电子签收台账段：全部回执（含照片/修正）+ 补偿记录 + 冻结状态 */
    public Map<String, Object> batchEsignInfo(Long batchId) {
        TransportBatch batch = batchMapper.selectById(batchId);
        List<Map<String, Object>> receipts = new ArrayList<>();
        for (SignReceipt r : listByBatch(batchId)) {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("receipt", r);
            rm.put("photos", listPhotos(r.getId()));
            rm.put("corrections", listCorrections(r.getId()));
            receipts.add(rm);
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("frozen", batch != null && CcConstants.BATCH_FROZEN.equals(batch.getStatus()));
        info.put("frozenFrom", batch == null ? null : batch.getSignFrozenFrom());
        info.put("receipts", receipts);
        info.put("compensations", listCompensations(batchId));
        return info;
    }

    // ---------------- 补偿记录（成对彼此对应） ----------------

    /**
     * 成对开立补偿记录：电子签收侧（ESIGN）与离线回执侧（OFFLINE）各一条，
     * pair_id 互指、彼此对应。返回两条记录（便于结果回执/测试断言）。
     */
    private List<Map<String, Object>> createPairedCompensation(Long receiptId, String receiptNo,
                                                               Long batchId, String syncRequestId,
                                                               String reason) {
        SignCompensation esign = new SignCompensation();
        esign.setReceiptId(receiptId);
        esign.setReceiptNo(receiptNo);
        esign.setBatchId(batchId);
        esign.setSyncRequestId(syncRequestId);
        esign.setSide(CcConstants.COMP_ESIGN);
        esign.setReason(reason);
        esign.setStatus(CcConstants.COMP_OPEN);
        compensationMapper.insert(esign);

        SignCompensation offline = new SignCompensation();
        offline.setReceiptId(receiptId);
        offline.setReceiptNo(receiptNo);
        offline.setBatchId(batchId);
        offline.setSyncRequestId(syncRequestId);
        offline.setSide(CcConstants.COMP_OFFLINE);
        offline.setReason(reason);
        offline.setStatus(CcConstants.COMP_OPEN);
        compensationMapper.insert(offline);

        // pair_id 互指：电子签收侧补偿 ↔ 离线回执侧补偿 彼此对应
        esign.setPairId(offline.getId());
        offline.setPairId(esign.getId());
        compensationMapper.updateById(esign);
        compensationMapper.updateById(offline);

        opLogService.log(CcConstants.ENT_ESIGN_COMPENSATION, esign.getId(), batchId,
                CcConstants.ACT_COMP_CREATE,
                String.format("回执 %s 部分成功，成对开立补偿记录：电子签收侧#%d ↔ 离线回执侧#%d（pair_id互指）。原因：%s",
                        receiptNo, esign.getId(), offline.getId(), reason));

        List<Map<String, Object>> out = new ArrayList<>();
        out.add(compView(esign));
        out.add(compView(offline));
        return out;
    }

    /** 回执完整上传成功后：闭环其全部待补偿记录，互指对方一并闭环（彼此对应） */
    private void resolveCompensations(String receiptNo, String note) {
        List<SignCompensation> opens = compensationMapper.selectList(new QueryWrapper<SignCompensation>()
                .eq("receipt_no", receiptNo)
                .eq("status", CcConstants.COMP_OPEN));
        LocalDateTime now = LocalDateTime.now();
        for (SignCompensation c : opens) {
            resolveOne(c, note, now);
            if (c.getPairId() != null) {
                SignCompensation pair = compensationMapper.selectById(c.getPairId());
                if (pair != null && CcConstants.COMP_OPEN.equals(pair.getStatus())) {
                    resolveOne(pair, note + "（随互指补偿一并闭环）", now);
                }
            }
        }
    }

    private void resolveOne(SignCompensation c, String note, LocalDateTime time) {
        c.setStatus(CcConstants.COMP_RESOLVED);
        c.setResolveNote(note);
        c.setResolveTime(time);
        compensationMapper.updateById(c);
        opLogService.log(CcConstants.ENT_ESIGN_COMPENSATION, c.getId(), c.getBatchId(),
                CcConstants.ACT_COMP_RESOLVE,
                String.format("补偿记录#%d（%s侧，回执 %s）闭环：%s；互指对方=#%s",
                        c.getId(), c.getSide(), c.getReceiptNo(), note, c.getPairId()));
    }

    private List<SignCompensation> listCompensationsOfReceipt(String receiptNo) {
        return compensationMapper.selectList(new QueryWrapper<SignCompensation>()
                .eq("receipt_no", receiptNo).orderByAsc("id"));
    }

    private Map<String, Object> compView(SignCompensation c) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", c.getId());
        v.put("receiptNo", c.getReceiptNo());
        v.put("side", c.getSide());
        v.put("pairId", c.getPairId());
        v.put("reason", c.getReason());
        v.put("status", c.getStatus());
        return v;
    }

    // ---------------- 私有工具 ----------------

    private TransportBatch requireBatch(Long batchId) {
        TransportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BusinessException("运输批次不存在: " + batchId);
        }
        return batch;
    }

    /** 合并要素：只补空缺；已有值的一致性由 requireSameContent 先行校验 */
    private void applyElements(SignReceipt receipt, EsignUploadRequest req) {
        if (receipt.getSignBy() == null && !isBlank(req.getSignBy())) {
            receipt.setSignBy(req.getSignBy().trim());
        }
        if (receipt.getSignRemark() == null && !isBlank(req.getSignRemark())) {
            receipt.setSignRemark(req.getSignRemark().trim());
        }
        if (receipt.getReceivedApprovalNo() == null && !isBlank(req.getReceivedApprovalNo())) {
            receipt.setReceivedApprovalNo(req.getReceivedApprovalNo().trim());
        }
        if (receipt.getReceivedPackageSpec() == null && !isBlank(req.getReceivedPackageSpec())) {
            receipt.setReceivedPackageSpec(req.getReceivedPackageSpec().trim());
        }
        if (receipt.getConfirmQty() == null && req.getConfirmQty() != null) {
            receipt.setConfirmQty(req.getConfirmQty());
        }
        if (receipt.getDeviceTime() == null && req.getDeviceTime() != null) {
            receipt.setDeviceTime(req.getDeviceTime());
        }
        if (receipt.getSignatureName() == null && !isBlank(req.getSignatureName())) {
            receipt.setSignatureName(req.getSignatureName().trim());
        }
        if (receipt.getSignatureUrl() == null && !isBlank(req.getSignatureUrl())) {
            receipt.setSignatureUrl(req.getSignatureUrl().trim());
        }
        if (receipt.getLocationLng() == null && req.getLocationLng() != null) {
            receipt.setLocationLng(req.getLocationLng());
        }
        if (receipt.getLocationLat() == null && req.getLocationLat() != null) {
            receipt.setLocationLat(req.getLocationLat());
        }
        if (receipt.getLocationAddress() == null && !isBlank(req.getLocationAddress())) {
            receipt.setLocationAddress(req.getLocationAddress().trim());
        }
    }

    /** 同一回执编号核心内容必须一致（同号不同单直接拒绝） */
    private void requireSameContent(SignReceipt receipt, EsignUploadRequest req) {
        requireSame(receipt.getSignBy(), trimOrNull(req.getSignBy()), "签收人", receipt.getReceiptNo());
        requireSame(receipt.getReceivedApprovalNo(), trimOrNull(req.getReceivedApprovalNo()),
                "批准文号", receipt.getReceiptNo());
        requireSame(receipt.getReceivedPackageSpec(), trimOrNull(req.getReceivedPackageSpec()),
                "包装规格", receipt.getReceiptNo());
        requireSame(receipt.getConfirmQty(), req.getConfirmQty(), "实收数量", receipt.getReceiptNo());
        requireSame(receipt.getDeviceTime(), req.getDeviceTime(), "设备时间", receipt.getReceiptNo());
        requireSame(receipt.getSignatureUrl(), trimOrNull(req.getSignatureUrl()), "签名", receipt.getReceiptNo());
    }

    private void requireSame(Object existing, Object incoming, String label, String receiptNo) {
        if (existing == null || incoming == null) {
            return; // 任一侧缺失=补传场景，不构成冲突
        }
        boolean same = existing instanceof java.math.BigDecimal
                ? ((java.math.BigDecimal) existing).compareTo((java.math.BigDecimal) incoming) == 0
                : existing.equals(incoming);
        if (!same) {
            throw new BusinessException(String.format(
                    "回执编号 %s 内容冲突：%s已登记为[%s]，本次上传为[%s]；同一回执编号不允许同号不同单",
                    receiptNo, label, existing, incoming));
        }
    }

    /** 要素齐全判定：签收人+设备时间+签名+照片+定位+核对字段 */
    private boolean isComplete(SignReceipt r) {
        boolean located = !isBlank(r.getLocationAddress())
                || (r.getLocationLng() != null && r.getLocationLat() != null);
        return !isBlank(r.getSignBy())
                && r.getDeviceTime() != null
                && !isBlank(r.getSignatureUrl())
                && located
                && r.getConfirmQty() != null
                && !isBlank(r.getReceivedApprovalNo())
                && !isBlank(r.getReceivedPackageSpec())
                && photoMapper.selectCount(new QueryWrapper<SignReceiptPhoto>()
                        .eq("receipt_id", r.getId())) > 0;
    }

    /** 核心内容摘要：判定“两份不同签收”的依据（照片为追加要素，不参与） */
    private String contentHash(SignReceipt r) {
        return DeviceCertService.sha256Hex(String.join("|",
                nz(r.getSignBy()), String.valueOf(r.getDeviceTime()),
                String.valueOf(r.getConfirmQty()), nz(r.getReceivedApprovalNo()),
                nz(r.getReceivedPackageSpec()), nz(r.getSignatureUrl())));
    }

    private String readField(SignReceipt receipt, String field) {
        switch (field) {
            case "signBy":
                return receipt.getSignBy();
            case "signRemark":
                return receipt.getSignRemark();
            case "locationAddress":
                return receipt.getLocationAddress();
            default:
                return null;
        }
    }

    private Map<String, Object> replay(EsignRequest existing, String payloadHash) {
        if (!existing.getPayloadHash().equals(payloadHash)) {
            throw new BusinessException("幂等键 " + existing.getRequestId()
                    + " 已用于另一份载荷，同一requestId不允许携带不同内容重复提交");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> stored = M.readValue(existing.getResultJson(), Map.class);
            stored.put("replay", true);
            return stored;
        } catch (Exception e) {
            throw new BusinessException("幂等重放读取首次结果失败: " + e.getMessage());
        }
    }

    private void registerRequest(String requestId, String type, String payloadHash, Map<String, Object> result) {
        EsignRequest row = new EsignRequest();
        row.setRequestId(requestId);
        row.setRequestType(type);
        row.setPayloadHash(payloadHash);
        try {
            row.setResultJson(M.writeValueAsString(result));
        } catch (Exception e) {
            throw new BusinessException("序列化处理结果失败: " + e.getMessage());
        }
        try {
            requestMapper.insert(row);
        } catch (DuplicateKeyException dup) {
            // 并发同键：以先落库者为准（唯一约束兜底）
        }
    }

    private String payloadOf(Object req) {
        try {
            return M.writeValueAsString(req);
        } catch (Exception e) {
            throw new BusinessException("序列化请求载荷失败: " + e.getMessage());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String trimOrNull(String s) {
        return isBlank(s) ? null : s.trim();
    }
}
