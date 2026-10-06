package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.TraceGrantRequest;
import com.evops.coldchain.dto.TraceGrantRevokeRequest;
import com.evops.coldchain.dto.TraceNodePublishRequest;
import com.evops.coldchain.dto.TraceSupplementRequest;
import com.evops.coldchain.entity.Drug;
import com.evops.coldchain.entity.DrugVersion;
import com.evops.coldchain.entity.HandoverOrder;
import com.evops.coldchain.entity.HandoverSeal;
import com.evops.coldchain.entity.ReturnBatch;
import com.evops.coldchain.entity.ShipmentOrder;
import com.evops.coldchain.entity.TraceNode;
import com.evops.coldchain.entity.TraceNodeSeal;
import com.evops.coldchain.entity.TraceNodeSupplement;
import com.evops.coldchain.entity.TraceOrgGrant;
import com.evops.coldchain.entity.TracePublishRequest;
import com.evops.coldchain.entity.TraceOpAudit;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.entity.Vehicle;
import com.evops.coldchain.mapper.DrugMapper;
import com.evops.coldchain.mapper.DrugVersionMapper;
import com.evops.coldchain.mapper.HandoverOrderMapper;
import com.evops.coldchain.mapper.HandoverSealMapper;
import com.evops.coldchain.mapper.ReturnBatchMapper;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.coldchain.mapper.ShipmentOrderMapper;
import com.evops.coldchain.mapper.TraceNodeMapper;
import com.evops.coldchain.mapper.TraceNodeSealMapper;
import com.evops.coldchain.mapper.TraceNodeSupplementMapper;
import com.evops.coldchain.mapper.TraceOrgGrantMapper;
import com.evops.coldchain.mapper.TracePublishRequestMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.coldchain.mapper.VehicleMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 药品批次追溯图（V13）。
 *
 * 链路：采购 PURCHASE（发货单创建锁版本）→ 仓储 WAREHOUSE（派车备货）→ 发运 SHIP（在途）→
 * 中转 TRANSIT（分仓交接提交、责任转移）→ 签收 SIGN（按快照核对闭环）→ 退货 RETURN（登记即快照）。
 *
 * 不变量：
 * - 每个节点发布当刻由系统采集业务表当时状态写入不可变快照（status_snapshot），
 *   应用层没有任何改写/删除节点或快照的路径；补录只能漏记补建（BACKFILL）或追加补充行。
 * - 冷链同一操作并发发起：op_key 数据库唯一约束仲裁，恰一个 WIN 生效，其余 LOST 被拒绝，
 *   双方操作者/时间/拒绝原因写 t_cc_trace_op_audit（LOST 审计独立事务保留，不随主事务回滚）。
 * - 机构授权：未授权机构的节点仍在链路中（保证链路不断），但机构名/联系人/电话全部掩码；
 *   授权但 contactVisible=FALSE 时机构可见、联系方式脱敏。授权收紧只改变查询/导出口径。
 * - 按批次号或封签号反查同一查询口径；导出 CSV 头标明数据截止时间与过滤条件并逐次审计。
 */
@Service
public class TraceService {

    private static final ObjectMapper M = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final String PRIMARY_BIZ_KEY = "PRIMARY";

    private static final Set<String> NODE_TYPES = new LinkedHashSet<>(Arrays.asList(
            CcConstants.TRACE_PURCHASE, CcConstants.TRACE_WAREHOUSE, CcConstants.TRACE_SHIP,
            CcConstants.TRACE_TRANSIT, CcConstants.TRACE_SIGN, CcConstants.TRACE_RETURN));
    private static final Map<String, String> NODE_TYPE_NAMES = new LinkedHashMap<>();
    static {
        NODE_TYPE_NAMES.put(CcConstants.TRACE_PURCHASE, "采购");
        NODE_TYPE_NAMES.put(CcConstants.TRACE_WAREHOUSE, "仓储");
        NODE_TYPE_NAMES.put(CcConstants.TRACE_SHIP, "发运");
        NODE_TYPE_NAMES.put(CcConstants.TRACE_TRANSIT, "中转");
        NODE_TYPE_NAMES.put(CcConstants.TRACE_SIGN, "签收");
        NODE_TYPE_NAMES.put(CcConstants.TRACE_RETURN, "退货");
    }
    private static final Set<String> SUPP_TYPES = new LinkedHashSet<>(Arrays.asList(
            CcConstants.SUPP_NOTE, CcConstants.SUPP_FILE, CcConstants.SUPP_CORRECTION_REF));

    @Resource private TraceNodeMapper nodeMapper;
    @Resource private TraceNodeSealMapper sealMapper;
    @Resource private TraceNodeSupplementMapper supplementMapper;
    @Resource private TracePublishRequestMapper requestMapper;
    @Resource private TraceOrgGrantMapper grantMapper;
    @Resource private com.evops.coldchain.mapper.TraceOpAuditMapper traceAuditMapper;
    @Resource private SequenceMapper sequenceMapper;
    @Resource private TransportBatchMapper batchMapper;
    @Resource private ShipmentOrderMapper orderMapper;
    @Resource private VehicleMapper vehicleMapper;
    @Resource private DrugMapper drugMapper;
    @Resource private DrugVersionMapper drugVersionMapper;
    @Resource private HandoverOrderMapper handoverMapper;
    @Resource private HandoverSealMapper handoverSealMapper;
    @Resource private ReturnBatchMapper returnBatchMapper;
    @Resource private OpLogService opLogService;
    @Resource private TraceAuditRecorder auditRecorder;

    // ============================ 业务流程自动挂钩（AUTO） ============================

    /** 采购节点：发货单创建、锁定档案版本瞬间发布（此时批次尚未派车，以 orderId 为链路锚） */
    @Transactional
    public void recordPurchase(ShipmentOrder order, Drug drug, DrugVersion version) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("orderNo", order.getOrderNo());
        snap.put("drugName", drug == null ? null : drug.getDrugName());
        snap.put("drugId", order.getDrugId());
        snap.put("pinnedVersionNo", order.getDrugVersionNo());
        snap.put("approvalNo", version == null ? null : version.getApprovalNo());
        snap.put("tempMin", version == null ? null : version.getTempMin());
        snap.put("tempMax", version == null ? null : version.getTempMax());
        snap.put("packageSpec", version == null ? null : version.getPackageSpec());
        snap.put("shipQty", order.getShipQty());
        snap.put("receiverOrg", order.getReceiverOrg());
        snap.put("planShipTime", order.getPlanShipTime());
        List<Map<String, Object>> parties = new ArrayList<>();
        parties.add(party("发货机构", order.getSenderOrg(), null, null));
        parties.add(party("收货机构", order.getReceiverOrg(), null, null));
        snap.put("parties", parties);

        NodeDraft d = NodeDraft.of(CcConstants.TRACE_PURCHASE, order.getId(), null, null,
                PRIMARY_BIZ_KEY, order.getSenderOrg(), null, null, order.getStatus(),
                order.getCreateTime() == null ? LocalDateTime.now() : order.getCreateTime(),
                "发货单创建即锁定档案版本 V" + order.getDrugVersionNo(), snap);
        publishCore(d, "AUTO:PURCHASE:O" + order.getId(), false);
    }

    /** 仓储节点：派车生成批次、药品备货待发运 */
    @Transactional
    public void recordWarehouse(TransportBatch batch) {
        ShipmentOrder order = orderMapper.selectById(batch.getOrderId());
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("batchNo", batch.getBatchNo());
        snap.put("orderNo", order == null ? null : order.getOrderNo());
        snap.put("drugVersionNo", batch.getDrugVersionNo());
        snap.put("approvalNo", batch.getApprovalNoSnap());
        snap.put("tempMin", batch.getTempMinSnap());
        snap.put("tempMax", batch.getTempMaxSnap());
        snap.put("packageSpec", batch.getPackageSpecSnap());
        snap.put("shipQty", batch.getShipQty());
        snap.put("receiverOrg", batch.getReceiverOrg());
        snap.put("dispatchTime", batch.getDispatchTime());
        snap.put("routeId", batch.getRouteId());
        List<Map<String, Object>> parties = new ArrayList<>();
        parties.add(party("发货机构", order == null ? null : order.getSenderOrg(), null, null));
        parties.add(party("收货机构", batch.getReceiverOrg(), null, null));
        snap.put("parties", parties);

        NodeDraft d = NodeDraft.of(CcConstants.TRACE_WAREHOUSE, order == null ? null : order.getId(),
                batch.getId(), batch.getBatchNo(), PRIMARY_BIZ_KEY,
                order == null ? null : order.getSenderOrg(), null, null, batch.getStatus(),
                batch.getDispatchTime() == null ? LocalDateTime.now() : batch.getDispatchTime(),
                "派车备货，档案 V" + batch.getDrugVersionNo() + " 快照已固化到批次", snap);
        publishCore(d, "AUTO:WAREHOUSE:B" + batch.getId(), false);
    }

    /** 发运节点：批次在途，责任主体初始为承运方 */
    @Transactional
    public void recordShip(TransportBatch batch) {
        ShipmentOrder order = orderMapper.selectById(batch.getOrderId());
        Vehicle vehicle = batch.getVehicleId() == null ? null : vehicleMapper.selectById(batch.getVehicleId());
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("batchNo", batch.getBatchNo());
        snap.put("shipTime", batch.getShipTime());
        snap.put("plateNo", vehicle == null ? null : vehicle.getPlateNo());
        snap.put("carrierId", vehicle == null ? null : vehicle.getCarrierId());
        snap.put("probeSerialNo", batch.getProbeSerialNoSnap());
        snap.put("probeCertNo", batch.getProbeCertNoSnap());
        snap.put("probeValidUntil", batch.getProbeValidUntilSnap());
        snap.put("routeSeason", batch.getRouteSeason());
        snap.put("routeVersionNo", batch.getRouteVersionNo());
        snap.put("responsibleOrg", batch.getResponsibleOrg());
        snap.put("responsibleParty", batch.getResponsibleParty());
        List<Map<String, Object>> parties = new ArrayList<>();
        parties.add(party("承运机构", batch.getResponsibleOrg(),
                vehicle == null ? batch.getResponsibleParty() : vehicle.getDriverName(),
                vehicle == null ? null : vehicle.getDriverPhone()));
        parties.add(party("收货机构", batch.getReceiverOrg(), null, null));
        snap.put("parties", parties);

        NodeDraft d = NodeDraft.of(CcConstants.TRACE_SHIP, batch.getOrderId(), batch.getId(),
                batch.getBatchNo(), PRIMARY_BIZ_KEY, batch.getResponsibleOrg(),
                batch.getResponsibleParty(), vehicle == null ? null : vehicle.getDriverPhone(),
                batch.getStatus(),
                batch.getShipTime() == null ? LocalDateTime.now() : batch.getShipTime(),
                "发运在途，车载探头凭证与路线季节版本已逐字段快照", snap);
        publishCore(d, "AUTO:SHIP:B" + batch.getId(), false);
    }

    /** 中转节点：交接单提交、封签全部核验、责任主体随单转移（fromOrg/fromParty 为交出方，提交前责任主体） */
    @Transactional
    public void recordTransit(HandoverOrder ho, String fromOrg, String fromParty) {
        TransportBatch batch = batchMapper.selectById(ho.getBatchId());
        List<HandoverSeal> seals = handoverSealMapper.selectList(new QueryWrapper<HandoverSeal>()
                .eq("handover_id", ho.getId()).orderByAsc("seal_seq"));
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("handoverNo", ho.getHandoverNo());
        snap.put("warehouseName", ho.getWarehouseName());
        snap.put("boxQty", ho.getBoxQty());
        snap.put("sealQty", ho.getSealQty());
        snap.put("handoverTemp", ho.getHandoverTemp());
        snap.put("handoverTime", ho.getHandoverTime());
        snap.put("submitTime", ho.getSubmitTime());
        snap.put("sealNos", sealNosOf(seals));
        List<Map<String, Object>> parties = new ArrayList<>();
        parties.add(party("交出方（承运）", fromOrg, fromParty, null));
        parties.add(party("接收机构（中转仓）", ho.getReceiverOrg(), ho.getReceiverName(), null));
        snap.put("parties", parties);

        NodeDraft d = NodeDraft.of(CcConstants.TRACE_TRANSIT,
                batch == null ? null : batch.getOrderId(), ho.getBatchId(),
                batch == null ? null : batch.getBatchNo(), ho.getHandoverNo(),
                ho.getReceiverOrg(), ho.getReceiverName(), null, CcConstants.HANDOVER_SUBMITTED,
                ho.getSubmitTime() == null ? LocalDateTime.now() : ho.getSubmitTime(),
                "交接单提交，" + ho.getBoxQty() + "箱/" + ho.getSealQty() + "封签全部核验，责任主体随单转移", snap);
        for (HandoverSeal s : seals) {
            d.seals.add(new SealDraft(s.getSealNo(), s.getVerifyStatus(), s.getVerifiedBy(),
                    s.getVerifyTime(), ho.getHandoverNo()));
        }
        publishCore(d, "AUTO:TRANSIT:B" + ho.getBatchId() + ":" + ho.getHandoverNo(), false);
    }

    /** 签收节点：按批次快照核对一致、闭环 */
    @Transactional
    public void recordSign(TransportBatch batch) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("batchNo", batch.getBatchNo());
        snap.put("signTime", batch.getSignTime());
        snap.put("signBy", batch.getSignBy());
        snap.put("signRemark", batch.getSignRemark());
        snap.put("shipQty", batch.getShipQty());
        snap.put("checkedApprovalNo", batch.getApprovalNoSnap());
        snap.put("checkedPackageSpec", batch.getPackageSpecSnap());
        snap.put("responsibleOrg", batch.getResponsibleOrg());
        snap.put("responsibleParty", batch.getResponsibleParty());
        List<Map<String, Object>> parties = new ArrayList<>();
        parties.add(party("签收机构", batch.getReceiverOrg(), batch.getSignBy(), null));
        snap.put("parties", parties);

        NodeDraft d = NodeDraft.of(CcConstants.TRACE_SIGN, batch.getOrderId(), batch.getId(),
                batch.getBatchNo(), PRIMARY_BIZ_KEY, batch.getReceiverOrg(), batch.getSignBy(), null,
                batch.getStatus(),
                batch.getSignTime() == null ? LocalDateTime.now() : batch.getSignTime(),
                "按派车档案快照核对一致，签收闭环", snap);
        publishCore(d, "AUTO:SIGN:B" + batch.getId(), false);
    }

    /** 退货节点：退货登记即固化快照，原销售批次只读关联 */
    @Transactional
    public void recordReturn(ReturnBatch ret) {
        TransportBatch source = ret.getSourceBatchId() == null ? null
                : batchMapper.selectById(ret.getSourceBatchId());
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("returnNo", ret.getReturnNo());
        snap.put("sourceBatchNo", ret.getSourceBatchNo());
        snap.put("reasonType", ret.getReasonType());
        snap.put("reasonTypeName", reasonTypeName(ret.getReasonType()));
        snap.put("reasonDetail", ret.getReasonDetail());
        snap.put("exceptionId", ret.getExceptionId());
        snap.put("returnQty", ret.getReturnQty());
        snap.put("drugName", ret.getDrugNameSnap());
        snap.put("approvalNo", ret.getApprovalNoSnap());
        snap.put("tempMin", ret.getTempMinSnap());
        snap.put("tempMax", ret.getTempMaxSnap());
        snap.put("packageSpec", ret.getPackageSpecSnap());
        List<Map<String, Object>> parties = new ArrayList<>();
        parties.add(party("退货责任机构", source == null ? null : source.getReceiverOrg(), null, null));
        snap.put("parties", parties);

        NodeDraft d = NodeDraft.of(CcConstants.TRACE_RETURN, ret.getOrderId(), ret.getSourceBatchId(),
                ret.getSourceBatchNo(), ret.getReturnNo(),
                source == null ? null : source.getReceiverOrg(), null, null, ret.getStatus(),
                ret.getCreateTime() == null ? LocalDateTime.now() : ret.getCreateTime(),
                "退货登记，原销售批次只读关联、登记快照已固化", snap);
        publishCore(d, "AUTO:RETURN:B" + ret.getSourceBatchId() + ":" + ret.getReturnNo(), false);
    }

    // ============================ 手工发布 / 漏记补建 ============================

    /**
     * 手工发布节点；backfill=TRUE 为漏记节点补建（source=BACKFILL，可填历史发生时间）。
     * 已发布 op_key 不可再次发布——并发或重复发起由唯一约束仲裁：一个生效、其余拒绝并审计。
     */
    @Transactional
    public Map<String, Object> publishManual(TraceNodePublishRequest req, boolean backfill) {
        requirePerm(CcConstants.PERM_TRACE_PUBLISH);
        String nodeType = req.getNodeType() == null ? null : req.getNodeType().trim().toUpperCase();
        if (!NODE_TYPES.contains(nodeType)) {
            throw new BusinessException("追溯节点类型非法：" + req.getNodeType()
                    + "（仅支持 PURCHASE/WAREHOUSE/SHIP/TRANSIT/SIGN/RETURN）");
        }
        TransportBatch batch = batchMapper.selectById(req.getBatchId());
        if (batch == null) {
            throw new BusinessException("运输批次不存在: " + req.getBatchId());
        }
        ShipmentOrder order = orderMapper.selectById(batch.getOrderId());
        if (order == null) {
            throw new BusinessException("批次关联发货单不存在，无法锚定追溯链路: " + batch.getOrderId());
        }

        // 1) requestId 幂等：同键同载荷返回首次结果（replay=true），同键不同载荷拒绝
        String payloadHash = sha256(json(M.valueToTree(req)));
        TracePublishRequest existing = requestMapper.selectOne(new QueryWrapper<TracePublishRequest>()
                .eq("request_id", req.getRequestId()).last("LIMIT 1"));
        if (existing != null) {
            if (!payloadHash.equals(existing.getPayloadHash())) {
                throw new BusinessException("幂等冲突：requestId=" + req.getRequestId()
                        + " 已用于不同内容的发布请求，不能复用（节点补录不能改变已发布快照）");
            }
            Map<String, Object> first = readJson(existing.getResultJson());
            first.put("replay", true);
            auditRecorder.recordDetached(req.getRequestId(), CcConstants.ACT_TRACE_PUBLISH,
                    CcConstants.TRACE_REPLAY, batch.getId(), batch.getBatchNo(), nodeType,
                    (String) first.get("opKey"), toLong(first.get("nodeId")), null, null);
            return first;
        }

        // 2) 组装节点：业务状态仍由系统在发布当刻采集，手工要素（机构/联系/说明/封签）合并进快照
        String bizKey = (req.getBizKey() == null || req.getBizKey().trim().isEmpty())
                ? PRIMARY_BIZ_KEY : req.getBizKey().trim();
        Map<String, Object> snap = collectManualSnapshot(nodeType, batch, order, req, bizKey);
        LocalDateTime occurred = req.getOccurredTime() == null ? LocalDateTime.now() : req.getOccurredTime();

        NodeDraft d = NodeDraft.of(nodeType, order.getId(), batch.getId(), batch.getBatchNo(), bizKey,
                trimToNull(req.getOrgName()), trimToNull(req.getContactName()), trimToNull(req.getContactPhone()),
                currentBizStatus(nodeType, batch, bizKey), occurred,
                trimToNull(req.getRemark()) == null ? (backfill ? "漏记节点补建" : "手工发布追溯节点")
                        : trimToNull(req.getRemark()), snap);
        d.source = backfill ? CcConstants.TRACE_SRC_BACKFILL : CcConstants.TRACE_SRC_MANUAL;
        d.orgName = d.orgName == null ? batch.getReceiverOrg() : d.orgName;
        // 中转节点补建：逐签固化封签锚点（补录时未知核验人/时间允许留空，状态按已核验固化）
        if (CcConstants.TRACE_TRANSIT.equals(nodeType) && req.getSealNos() != null) {
            for (String sealNo : req.getSealNos()) {
                String no = sealNo == null ? null : sealNo.trim();
                if (no != null && !no.isEmpty()) {
                    d.seals.add(new SealDraft(no, CcConstants.SEAL_CONFIRMED, null, null,
                            PRIMARY_BIZ_KEY.equals(bizKey) ? null : bizKey));
                }
            }
        }

        // 3) 占幂等键（唯一约束兜底同 requestId 并发）；同键同载荷并发抢入时按重放返回首次结果
        try {
            claimRequest(req.getRequestId(), "PUBLISH", payloadHash);
        } catch (BusinessException e) {
            if ("__REPLAY__".equals(e.getMessage())) {
                TracePublishRequest winner = requestMapper.selectOne(new QueryWrapper<TracePublishRequest>()
                        .eq("request_id", req.getRequestId()).last("LIMIT 1"));
                Map<String, Object> first = winner == null ? new LinkedHashMap<>()
                        : readJson(winner.getResultJson());
                first.put("replay", true);
                return first;
            }
            throw e;
        }

        // 4) 仲裁发布：op_key 唯一约束决定谁生效（一个 WIN，其余 LOST 被拒绝并审计）
        TraceNode node = publishCore(d, req.getRequestId(), true);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodeId", node.getId());
        result.put("nodeNo", node.getNodeNo());
        result.put("opKey", node.getOpKey());
        result.put("batchNo", node.getBatchNo());
        result.put("nodeType", node.getNodeType());
        result.put("source", node.getSource());
        result.put("replay", false);
        TracePublishRequest claimed = requestMapper.selectOne(new QueryWrapper<TracePublishRequest>()
                .eq("request_id", req.getRequestId()).last("LIMIT 1"));
        if (claimed != null) {
            claimed.setResultJson(json(result));
            requestMapper.updateById(claimed);
        }
        return result;
    }

    /** 已发布节点追加补充（NOTE/FILE/CORRECTION_REF）；绝不改变已发布快照原值 */
    @Transactional
    public Map<String, Object> supplement(TraceSupplementRequest req) {
        requirePerm(CcConstants.PERM_TRACE_PUBLISH);
        String type = req.getSupplementType() == null ? null : req.getSupplementType().trim().toUpperCase();
        if (!SUPP_TYPES.contains(type)) {
            throw new BusinessException("补充类型非法：" + req.getSupplementType()
                    + "（仅支持 NOTE/FILE/CORRECTION_REF）");
        }
        TraceNode node = nodeMapper.selectById(req.getNodeId());
        if (node == null) {
            throw new BusinessException("追溯节点不存在: " + req.getNodeId());
        }

        String payloadHash = sha256(json(M.valueToTree(req)));
        TracePublishRequest existing = requestMapper.selectOne(new QueryWrapper<TracePublishRequest>()
                .eq("request_id", req.getRequestId()).last("LIMIT 1"));
        if (existing != null) {
            if (!payloadHash.equals(existing.getPayloadHash())) {
                throw new BusinessException("幂等冲突：requestId=" + req.getRequestId()
                        + " 已用于不同内容的补充请求，不能复用");
            }
            Map<String, Object> first = readJson(existing.getResultJson());
            first.put("replay", true);
            auditRecorder.recordDetached(req.getRequestId(), CcConstants.ACT_TRACE_SUPPLEMENT,
                    CcConstants.TRACE_REPLAY, node.getBatchId(), node.getBatchNo(), node.getNodeType(),
                    node.getOpKey(), node.getId(), null, null);
            return first;
        }
        try {
            claimRequest(req.getRequestId(), "SUPPLEMENT", payloadHash);
        } catch (BusinessException e) {
            if ("__REPLAY__".equals(e.getMessage())) {
                TracePublishRequest winner = requestMapper.selectOne(new QueryWrapper<TracePublishRequest>()
                        .eq("request_id", req.getRequestId()).last("LIMIT 1"));
                Map<String, Object> first = winner == null ? new LinkedHashMap<>()
                        : readJson(winner.getResultJson());
                first.put("replay", true);
                return first;
            }
            throw e;
        }

        TraceNodeSupplement row = new TraceNodeSupplement();
        row.setNodeId(node.getId());
        row.setBatchId(node.getBatchId());
        row.setSupplementType(type);
        row.setContent(req.getContent().trim());
        supplementMapper.insert(row);

        auditRecorder.recordJoined(req.getRequestId(), CcConstants.ACT_TRACE_SUPPLEMENT, CcConstants.TRACE_INFO,
                node.getBatchId(), node.getBatchNo(), node.getNodeType(), node.getOpKey(), node.getId(),
                null, null);
        opLogService.log(CcConstants.ENT_TRACE_NODE, node.getId(), node.getBatchId(),
                CcConstants.ACT_TRACE_SUPPLEMENT,
                String.format("追溯节点 %s（%s）追加补充[%s]：%s（只追加，已发布快照原值不变）",
                        node.getNodeNo(), node.getBatchNo(), type, req.getContent()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("supplementId", row.getId());
        result.put("nodeId", node.getId());
        result.put("nodeNo", node.getNodeNo());
        result.put("supplementType", type);
        result.put("replay", false);
        TracePublishRequest claimed = requestMapper.selectOne(new QueryWrapper<TracePublishRequest>()
                .eq("request_id", req.getRequestId()).last("LIMIT 1"));
        if (claimed != null) {
            claimed.setResultJson(json(result));
            requestMapper.updateById(claimed);
        }
        return result;
    }

    // ============================ 追溯图 / 反查 ============================

    /** 按批次查看追溯图（节点按业务发生时间排序，授权过滤在视图层掩码） */
    @Transactional(readOnly = true)
    public Map<String, Object> graphByBatch(Long batchId, String nodeTypeFilter, String orgFilter) {
        TransportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BusinessException("运输批次不存在: " + batchId);
        }
        return assembleGraph(batch, null, nodeTypeFilter, orgFilter);
    }

    /** 按批次号反查追溯链路 */
    @Transactional(readOnly = true)
    public Map<String, Object> graphByBatchNo(String batchNo, String nodeTypeFilter, String orgFilter) {
        String no = trimToNull(batchNo);
        if (no == null) {
            throw new BusinessException("批次号不能为空");
        }
        TransportBatch batch = batchMapper.selectOne(new QueryWrapper<TransportBatch>()
                .eq("batch_no", no).last("LIMIT 1"));
        if (batch == null) {
            throw new BusinessException("按批次号反查不到运输批次: " + no);
        }
        return assembleGraph(batch, null, nodeTypeFilter, orgFilter);
    }

    /** 按封签号反查：命中封签锚点 -> 所属节点 -> 整条批次链路 */
    @Transactional(readOnly = true)
    public Map<String, Object> graphBySealNo(String sealNo, String nodeTypeFilter, String orgFilter) {
        String no = trimToNull(sealNo);
        if (no == null) {
            throw new BusinessException("封签号不能为空");
        }
        TraceNodeSeal seal = sealMapper.selectOne(new QueryWrapper<TraceNodeSeal>()
                .eq("seal_no", no).last("LIMIT 1"));
        if (seal == null) {
            throw new BusinessException("按封签号反查不到追溯节点: " + no);
        }
        TraceNode owner = nodeMapper.selectById(seal.getNodeId());
        if (owner == null || owner.getBatchId() == null) {
            throw new BusinessException("封签 " + no + " 所在追溯节点缺失批次锚点");
        }
        TransportBatch batch = batchMapper.selectById(owner.getBatchId());
        if (batch == null) {
            throw new BusinessException("封签 " + no + " 所属运输批次不存在: " + owner.getBatchId());
        }
        Map<String, Object> graph = assembleGraph(batch, no, nodeTypeFilter, orgFilter);
        return graph;
    }

    /** 追溯节点最近流水（页面默认视图，授权掩码同图口径） */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> recentNodes(int limit) {
        int safe = limit <= 0 || limit > 200 ? 50 : limit;
        List<TraceNode> nodes = nodeMapper.selectList(new QueryWrapper<TraceNode>()
                .isNotNull("batch_id").orderByDesc("id").last("LIMIT " + safe));
        Grants grants = Grants.current(grantMapper, UserContext.currentName());
        List<Map<String, Object>> views = new ArrayList<>();
        for (TraceNode n : nodes) {
            views.add(toNodeView(n, grants));
        }
        return views;
    }

    private Map<String, Object> assembleGraph(TransportBatch batch, String hitSealNo,
                                              String nodeTypeFilter, String orgFilter) {
        String typeFilter = trimToNull(nodeTypeFilter) == null ? null
                : nodeTypeFilter.trim().toUpperCase();
        if (typeFilter != null && !NODE_TYPES.contains(typeFilter)) {
            throw new BusinessException("节点类型过滤条件非法：" + nodeTypeFilter);
        }
        String ofilter = trimToNull(orgFilter);
        Grants grants = Grants.current(grantMapper, UserContext.currentName());
        if (ofilter != null && !grants.canSeeOrg(ofilter)) {
            throw new BusinessException(String.format(
                    "机构授权隔离：当前账号[%s]未被授权机构[%s]，不能按该机构过滤追溯结果",
                    UserContext.currentName(), ofilter));
        }

        List<TraceNode> nodes = nodeMapper.selectList(new QueryWrapper<TraceNode>()
                .eq("order_id", batch.getOrderId())
                .orderByAsc("occurred_time", "id"));

        List<Map<String, Object>> views = new ArrayList<>();
        int chainSeq = 0;
        int filteredOut = 0;
        for (TraceNode n : nodes) {
            boolean authorized = grants.canSeeOrg(n.getOrgName());
            if (typeFilter != null && !typeFilter.equals(n.getNodeType())) {
                filteredOut++;
                continue;
            }
            if (ofilter != null && !ofilter.equals(n.getOrgName())) {
                filteredOut++;
                continue;
            }
            Map<String, Object> v = toNodeView(n, grants);
            v.put("chainSeq", ++chainSeq);
            if (hitSealNo != null) {
                v.put("hitBySeal", hasSeal(n.getId(), hitSealNo));
            }
            views.add(v);
        }

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("batchNo", batch.getBatchNo());
        filters.put("sealNo", hitSealNo);
        filters.put("nodeType", typeFilter);
        filters.put("orgName", ofilter);
        filters.put("operator", UserContext.currentName());

        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("batchId", batch.getId());
        graph.put("batchNo", batch.getBatchNo());
        graph.put("orderId", batch.getOrderId());
        graph.put("dataCutoffTime", LocalDateTime.now());
        graph.put("filters", filters);
        graph.put("nodeTotal", nodes.size());
        graph.put("nodeReturned", views.size());
        graph.put("filteredOut", filteredOut);
        graph.put("nodes", views);
        return graph;
    }

    // ============================ 节点详情（授权掩码同追溯图） ============================

    /** 单个节点视图（含封签锚点/补充；机构与联系方式按当前账号授权掩码） */
    @Transactional(readOnly = true)
    public Map<String, Object> nodeViewById(Long nodeId) {
        TraceNode node = nodeMapper.selectById(nodeId);
        if (node == null) {
            throw new BusinessException("追溯节点不存在: " + nodeId);
        }
        return toNodeView(node, Grants.current(grantMapper, UserContext.currentName()));
    }

    // ============================ 机构授权维护 ============================

    @Transactional
    public TraceOrgGrant grant(TraceGrantRequest req) {
        requirePerm(CcConstants.PERM_TRACE_GRANT);
        String username = req.getUsername().trim();
        String orgName = req.getOrgName().trim();
        boolean contact = Boolean.TRUE.equals(req.getContactVisible());

        TraceOrgGrant row = grantMapper.selectOne(new QueryWrapper<TraceOrgGrant>()
                .eq("username", username).eq("org_name", orgName).last("LIMIT 1"));
        if (row == null) {
            row = new TraceOrgGrant();
            row.setUsername(username);
            row.setOrgName(orgName);
            row.setContactVisible(contact);
            row.setStatus(CcConstants.GRANT_ACTIVE);
            grantMapper.insert(row);
        } else {
            // 重新激活/调整既有授权（授权表带更新审计字段；已发布追溯节点事实不受影响）
            row.setStatus(CcConstants.GRANT_ACTIVE);
            row.setContactVisible(contact);
            row.setRevokeReason(null);
            row.setRevokeTime(null);
            grantMapper.updateById(row);
        }
        auditRecorder.recordJoined(null, CcConstants.ACT_TRACE_GRANT, CcConstants.TRACE_INFO,
                null, null, null, null, row.getId(), null,
                json(new LinkedHashMap<String, Object>() {{
                    put("username", username);
                    put("orgName", orgName);
                    put("contactVisible", contact);
                }}));
        opLogService.log(CcConstants.ENT_TRACE_GRANT, row.getId(), null, CcConstants.ACT_TRACE_GRANT,
                String.format("授权账号[%s]可见追溯机构[%s]，联系方式明文=%s（只影响此后查询/导出口径，已发布节点快照不变）",
                        username, CcConstants.TRACE_ORG_ALL.equals(orgName) ? "全域" : orgName, contact));
        return row;
    }

    @Transactional
    public TraceOrgGrant revokeGrant(TraceGrantRevokeRequest req) {
        requirePerm(CcConstants.PERM_TRACE_GRANT);
        TraceOrgGrant row = grantMapper.selectById(req.getId());
        if (row == null) {
            throw new BusinessException("授权记录不存在: " + req.getId());
        }
        if (CcConstants.GRANT_REVOKED.equals(row.getStatus())) {
            throw new BusinessException("授权已撤销，不能重复撤销: " + row.getUsername() + "/" + row.getOrgName());
        }
        row.setStatus(CcConstants.GRANT_REVOKED);
        row.setRevokeReason(req.getReason().trim());
        row.setRevokeTime(LocalDateTime.now());
        grantMapper.updateById(row);

        auditRecorder.recordJoined(null, CcConstants.ACT_TRACE_GRANT_REVOKE, CcConstants.TRACE_INFO,
                null, null, null, null, row.getId(), null,
                json(new LinkedHashMap<String, Object>() {{
                    put("username", row.getUsername());
                    put("orgName", row.getOrgName());
                    put("reason", req.getReason());
                }}));
        opLogService.log(CcConstants.ENT_TRACE_GRANT, row.getId(), null, CcConstants.ACT_TRACE_GRANT_REVOKE,
                String.format("撤销账号[%s]对追溯机构[%s]的可见授权：%s（已发布节点事实保留，仅查询/导出收窄）",
                        row.getUsername(), row.getOrgName(), req.getReason()));
        return row;
    }

    @Transactional(readOnly = true)
    public List<TraceOrgGrant> listGrants(String username) {
        QueryWrapper<TraceOrgGrant> qw = new QueryWrapper<TraceOrgGrant>().orderByDesc("id");
        if (trimToNull(username) != null) {
            qw.eq("username", username.trim());
        }
        return grantMapper.selectList(qw);
    }

    /** 导出审计（由 Controller 在 CSV 输出成功后调用） */
    public void auditExport(Map<String, Object> filters, int nodeRows) {
        Map<String, Object> f = new LinkedHashMap<>(filters);
        f.put("nodeRows", nodeRows);
        auditRecorder.recordDetached(null, CcConstants.ACT_TRACE_EXPORT, CcConstants.TRACE_INFO,
                filters.get("batchId") == null ? null : ((Number) filters.get("batchId")).longValue(),
                filters.get("batchNo") == null ? null : String.valueOf(filters.get("batchNo")),
                null, null, null, null, json(f));
        opLogService.log(CcConstants.ENT_TRACE_NODE, null,
                filters.get("batchId") == null ? null : ((Number) filters.get("batchId")).longValue(),
                CcConstants.ACT_TRACE_EXPORT,
                "导出追溯图 CSV：数据截止时间=" + f.get("dataCutoffTime")
                        + "，过滤条件=" + f.get("batchNo") + "/" + f.get("sealNo") + "/" + f.get("nodeType")
                        + "/" + f.get("orgName") + "，导出节点 " + nodeRows + " 个");
    }

    // ============================ 内部：发布仲裁 ============================

    /**
     * 发布核心：op_key 唯一约束仲裁。
     * 成功返回新生效节点（WIN）；同一操作已被他人生效时抛业务异常（LOST），
     * LOST 审计在独立事务落库，主事务随后回滚不影响审计保留。
     * manualRequest=TRUE 时幂等行已在调用方占住；AUTO 挂钩使用确定性 requestId 做重放保护。
     */
    private TraceNode publishCore(NodeDraft d, String requestId, boolean manual) {
        // AUTO 挂钩的确定性 requestId：重复触发直接回首次节点，不新增、不报错（主流程重入安全）
        if (!manual) {
            TracePublishRequest existing = requestMapper.selectOne(new QueryWrapper<TracePublishRequest>()
                    .eq("request_id", requestId).last("LIMIT 1"));
            if (existing != null && existing.getResultJson() != null) {
                Map<String, Object> first = readJson(existing.getResultJson());
                Object nodeId = first.get("nodeId");
                if (nodeId != null) {
                    return nodeMapper.selectById(((Number) nodeId).longValue());
                }
            }
        }

        String opKey = (d.batchId != null ? "B" + d.batchId : "O" + d.orderId)
                + "|" + d.nodeType + "|" + d.bizKey;
        TraceNode winner = nodeMapper.selectOne(new QueryWrapper<TraceNode>()
                .eq("op_key", opKey).last("LIMIT 1"));
        if (winner != null) {
            rejectLost(requestId, d, opKey, winner);
        }

        TraceNode node = new TraceNode();
        node.setNodeNo(generateNodeNo());
        node.setOrderId(d.orderId);
        node.setBatchId(d.batchId);
        node.setBatchNo(d.batchNo);
        node.setNodeType(d.nodeType);
        node.setBizKey(d.bizKey);
        node.setOpKey(opKey);
        node.setOrgName(d.orgName);
        node.setContactName(d.contactName);
        node.setContactPhone(d.contactPhone);
        node.setBizStatus(d.bizStatus);
        node.setSource(d.source);
        node.setOccurredTime(d.occurredTime);
        node.setRemark(d.remark);
        node.setStatusSnapshot(json(d.snapshot));
        try {
            nodeMapper.insert(node);
        } catch (DuplicateKeyException e) {
            // 并发窗口：预查之后、插入之前被他人抢先生效——本次为 LOST
            TraceNode concurrent = nodeMapper.selectOne(new QueryWrapper<TraceNode>()
                    .eq("op_key", opKey).last("LIMIT 1"));
            rejectLost(requestId, d, opKey, concurrent == null ? winner : concurrent);
            return null; // unreachable：rejectLost 必抛异常
        }
        for (SealDraft sd : d.seals) {
            TraceNodeSeal row = new TraceNodeSeal();
            row.setNodeId(node.getId());
            row.setBatchId(node.getBatchId());
            row.setSealNo(sd.sealNo);
            row.setSealStatus(sd.sealStatus);
            row.setVerifiedBy(sd.verifiedBy);
            row.setVerifyTime(sd.verifyTime);
            row.setHandoverNo(sd.handoverNo);
            sealMapper.insert(row);
        }

        // AUTO 挂钩登记确定性幂等键（同内容），支撑主流程重入
        if (!manual) {
            TracePublishRequest pr = new TracePublishRequest();
            pr.setRequestId(requestId);
            pr.setRequestKind("PUBLISH");
            pr.setPayloadHash(sha256(opKey));
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("nodeId", node.getId());
            r.put("nodeNo", node.getNodeNo());
            r.put("opKey", opKey);
            r.put("auto", true);
            pr.setResultJson(json(r));
            try {
                requestMapper.insert(pr);
            } catch (DuplicateKeyException ignored) {
                // 并发重入：对方已登记，随本事务提交后以其结果为准
            }
        }

        // WIN 审计随主事务（生效事实成立才留痕）；操作日志同口径
        auditRecorder.recordJoined(requestId, CcConstants.ACT_TRACE_PUBLISH, CcConstants.TRACE_WIN,
                node.getBatchId(), node.getBatchNo(), node.getNodeType(), opKey, node.getId(), null, null);
        opLogService.log(CcConstants.ENT_TRACE_NODE, node.getId(), node.getBatchId(),
                CcConstants.ACT_TRACE_PUBLISH,
                String.format("追溯节点发布[%s] %s %s：节点号=%s，业务键=%s，来源=%s，状态快照已固化且不可变",
                        NODE_TYPE_NAMES.get(node.getNodeType()),
                        node.getBatchNo() == null ? "O" + node.getOrderId() : node.getBatchNo(),
                        node.getNodeType(), node.getNodeNo(), node.getBizKey(), node.getSource()));
        return node;
    }

    private void rejectLost(String requestId, NodeDraft d, String opKey, TraceNode winner) {
        String reason;
        if (winner == null) {
            throw new BusinessException("同一操作并发冲突（opKey=" + opKey + "），本次请求被拒绝，请重新查询后再试");
        }
        String winnerOperator = resolveOperatorName(winner);
        reason = String.format(
                "冷链同一操作并发/重复发起：操作[%s|%s]已由账号[%s]于 %s 生效（节点号=%s，ID=%d，来源=%s）；"
                        + "本次请求被拒绝，已发布状态快照不能改变（要求人=%s）",
                d.nodeType, d.bizKey, winnerOperator, winner.getCreateTime(),
                winner.getNodeNo(), winner.getId(), winner.getSource(), UserContext.currentName());
        // LOST 审计独立事务：主事务回滚/异常不影响“谁被拒绝、谁已生效”的审计保留
        auditRecorder.recordDetached(requestId, CcConstants.ACT_TRACE_PUBLISH, CcConstants.TRACE_LOST,
                d.batchId, d.batchNo, d.nodeType, opKey, winner.getId(), reason, null);
        throw new BusinessException(reason);
    }

    // ============================ 内部：视图与授权掩码 ============================

    private Map<String, Object> toNodeView(TraceNode n, Grants grants) {
        boolean authorized = grants.canSeeOrg(n.getOrgName());
        boolean contactVisible = authorized && grants.contactVisible(n.getOrgName());

        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", n.getId());
        v.put("nodeNo", n.getNodeNo());
        v.put("batchId", n.getBatchId());
        v.put("batchNo", n.getBatchNo());
        v.put("nodeType", n.getNodeType());
        v.put("nodeTypeName", NODE_TYPE_NAMES.get(n.getNodeType()));
        v.put("bizKey", n.getBizKey());
        v.put("bizStatus", n.getBizStatus());
        v.put("source", n.getSource());
        v.put("occurredTime", n.getOccurredTime());
        v.put("publishTime", n.getCreateTime());
        v.put("publishBy", n.getCreateBy());
        v.put("remark", n.getRemark());
        v.put("authorized", authorized);
        v.put("contactVisible", contactVisible);
        v.put("orgName", authorized ? n.getOrgName() : mask(n.getOrgName()));
        v.put("contactName", contactVisible ? n.getContactName()
                : (n.getContactName() == null ? null : "***"));
        v.put("contactPhone", contactVisible ? n.getContactPhone() : maskPhone(n.getContactPhone()));

        // 快照视图：原 status_snapshot 不直接下发，按授权掩码 parties 后给出，杜绝从 JSON 原文绕行
        Map<String, Object> snap = readJson(n.getStatusSnapshot());
        Object partiesObj = snap.get("parties");
        if (partiesObj instanceof List) {
            List<Map<String, Object>> masked = new ArrayList<>();
            for (Object o : (List<?>) partiesObj) {
                if (o instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> p = (Map<String, Object>) o;
                    String org = p.get("org") == null ? null : String.valueOf(p.get("org"));
                    boolean pAuth = grants.canSeeOrg(org);
                    boolean pContact = pAuth && grants.contactVisible(org);
                    Map<String, Object> pm = new LinkedHashMap<>(p);
                    pm.put("org", pAuth ? org : mask(org));
                    pm.put("person", pContact ? p.get("person") : (p.get("person") == null ? null : "***"));
                    pm.put("phone", pContact ? p.get("phone") : maskPhone(
                            p.get("phone") == null ? null : String.valueOf(p.get("phone"))));
                    pm.put("authorized", pAuth);
                    pm.put("contactVisible", pContact);
                    masked.add(pm);
                }
            }
            snap.put("parties", masked);
        }
        v.put("snapshot", snap);

        List<TraceNodeSeal> seals = sealMapper.selectList(new QueryWrapper<TraceNodeSeal>()
                .eq("node_id", n.getId()).orderByAsc("id"));
        List<Map<String, Object>> sealViews = new ArrayList<>();
        for (TraceNodeSeal s : seals) {
            Map<String, Object> sv = new LinkedHashMap<>();
            sv.put("sealNo", s.getSealNo());
            sv.put("sealStatus", s.getSealStatus());
            sv.put("handoverNo", authorized ? s.getHandoverNo() : null);
            sv.put("verifiedBy", contactVisible ? s.getVerifiedBy()
                    : (s.getVerifiedBy() == null ? null : "***"));
            sv.put("verifyTime", s.getVerifyTime());
            sealViews.add(sv);
        }
        v.put("seals", sealViews);
        v.put("supplements", supplementMapper.selectList(new QueryWrapper<TraceNodeSupplement>()
                .eq("node_id", n.getId()).orderByAsc("id")));
        return v;
    }

    private boolean hasSeal(Long nodeId, String sealNo) {
        return sealMapper.selectCount(new QueryWrapper<TraceNodeSeal>()
                .eq("node_id", nodeId).eq("seal_no", sealNo)) > 0;
    }

    // ============================ 内部：快照采集/工具 ============================

    private Map<String, Object> collectManualSnapshot(String nodeType, TransportBatch batch,
                                                      ShipmentOrder order,
                                                      TraceNodePublishRequest req, String bizKey) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("batchNo", batch.getBatchNo());
        snap.put("manualPublishedAt", LocalDateTime.now());
        switch (nodeType) {
            case CcConstants.TRACE_PURCHASE:
                snap.put("orderNo", order.getOrderNo());
                snap.put("pinnedVersionNo", order.getDrugVersionNo());
                snap.put("shipQty", order.getShipQty());
                snap.put("receiverOrg", order.getReceiverOrg());
                snap.put("planShipTime", order.getPlanShipTime());
                snap.put("orderStatusAtPublish", order.getStatus());
                break;
            case CcConstants.TRACE_WAREHOUSE:
                snap.put("dispatchTime", batch.getDispatchTime());
                snap.put("drugVersionNo", batch.getDrugVersionNo());
                snap.put("approvalNo", batch.getApprovalNoSnap());
                snap.put("packageSpec", batch.getPackageSpecSnap());
                snap.put("shipQty", batch.getShipQty());
                snap.put("batchStatusAtPublish", batch.getStatus());
                break;
            case CcConstants.TRACE_SHIP:
                snap.put("shipTime", batch.getShipTime());
                snap.put("responsibleOrg", batch.getResponsibleOrg());
                snap.put("responsibleParty", batch.getResponsibleParty());
                snap.put("probeSerialNo", batch.getProbeSerialNoSnap());
                snap.put("routeSeason", batch.getRouteSeason());
                snap.put("batchStatusAtPublish", batch.getStatus());
                break;
            case CcConstants.TRACE_TRANSIT:
                snap.put("handoverBizKey", bizKey);
                snap.put("sealNos", req.getSealNos());
                snap.put("batchStatusAtPublish", batch.getStatus());
                break;
            case CcConstants.TRACE_SIGN:
                snap.put("signTime", batch.getSignTime());
                snap.put("signBy", batch.getSignBy());
                snap.put("checkedApprovalNo", batch.getApprovalNoSnap());
                snap.put("batchStatusAtPublish", batch.getStatus());
                break;
            case CcConstants.TRACE_RETURN:
                snap.put("batchStatusAtPublish", batch.getStatus());
                snap.put("note", "按业务键 " + bizKey + " 补建退货节点；以退货台账记录为准");
                break;
            default:
                break;
        }
        List<Map<String, Object>> parties = new ArrayList<>();
        if (trimToNull(req.getOrgName()) != null || trimToNull(req.getContactName()) != null
                || trimToNull(req.getContactPhone()) != null) {
            parties.add(party("补录机构/联系信息", trimToNull(req.getOrgName()),
                    trimToNull(req.getContactName()), trimToNull(req.getContactPhone())));
        }
        parties.add(party("收货机构", batch.getReceiverOrg(), null, null));
        snap.put("parties", parties);
        return snap;
    }

    private String currentBizStatus(String nodeType, TransportBatch batch, String bizKey) {
        switch (nodeType) {
            case CcConstants.TRACE_PURCHASE:
                ShipmentOrder o = orderMapper.selectById(batch.getOrderId());
                return o == null ? null : o.getStatus();
            case CcConstants.TRACE_TRANSIT:
                return CcConstants.HANDOVER_SUBMITTED;
            case CcConstants.TRACE_RETURN:
                ReturnBatch ret = PRIMARY_BIZ_KEY.equals(bizKey) ? null
                        : returnBatchMapper.selectOne(new QueryWrapper<ReturnBatch>()
                                .eq("return_no", bizKey).last("LIMIT 1"));
                return ret == null ? CcConstants.RETURN_REGISTERED : ret.getStatus();
            default:
                return batch.getStatus();
        }
    }

    private void claimRequest(String requestId, String kind, String payloadHash) {
        try {
            TracePublishRequest pr = new TracePublishRequest();
            pr.setRequestId(requestId);
            pr.setRequestKind(kind);
            pr.setPayloadHash(payloadHash);
            requestMapper.insert(pr);
        } catch (DuplicateKeyException e) {
            TracePublishRequest winner = requestMapper.selectOne(new QueryWrapper<TracePublishRequest>()
                    .eq("request_id", requestId).last("LIMIT 1"));
            if (winner != null && payloadHash.equals(winner.getPayloadHash())) {
                // 同键并发：交回上层走重放（返回首次结果）
                throw new BusinessException("__REPLAY__");
            }
            throw new BusinessException("requestId=" + requestId + " 并发冲突且载荷不一致，已拒绝");
        }
    }

    private List<String> sealNosOf(List<HandoverSeal> seals) {
        List<String> nos = new ArrayList<>();
        for (HandoverSeal s : seals) {
            nos.add(s.getSealNo());
        }
        return nos;
    }

    private static Map<String, Object> party(String role, String org, String person, String phone) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("role", role);
        p.put("org", org);
        p.put("person", person);
        p.put("phone", phone);
        return p;
    }

    public static String nodeTypeName(String type) {
        return NODE_TYPE_NAMES.getOrDefault(type, type);
    }

    private static String reasonTypeName(String t) {
        if (t == null) {
            return null;
        }
        switch (t) {
            case CcConstants.RETURN_REASON_REJECTION: return "收货拒签";
            case CcConstants.RETURN_REASON_TEMP: return "温度超限";
            case CcConstants.RETURN_REASON_DAMAGE: return "包装破损";
            default: return t;
        }
    }

    /** 联系方式脱敏：前3位+******+后2位；长度不足全掩码（与 V7 导出口径一致） */
    public static String maskPhone(String phone) {
        if (phone == null) {
            return null;
        }
        String p = phone.trim();
        if (p.length() < 5) {
            return "******";
        }
        return p.substring(0, 3) + "******" + p.substring(p.length() - 2);
    }

    private static String mask(String s) {
        return s == null ? null : "***（未授权机构）***";
    }

    private String generateNodeNo() {
        return String.format("ZS%08d", sequenceMapper.nextTraceNodeNo() % 100000000L);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            return M.readValue(json, LinkedHashMap.class);
        } catch (Exception e) {
            throw new IllegalStateException("追溯快照 JSON 解析失败", e);
        }
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 计算失败", e);
        }
    }

    private static String json(Object value) {
        try {
            return M.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("追溯 JSON 序列化失败", e);
        }
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : Long.valueOf(s);
    }

    /** 解析生效节点操作者账号名（节点表只存操作者ID，WIN 审计行存账号名；取不到时回退 ID） */
    private String resolveOperatorName(TraceNode node) {
        TraceOpAudit win = traceAuditMapper.selectOne(new QueryWrapper<TraceOpAudit>()
                .eq("node_id", node.getId())
                .eq("result", CcConstants.TRACE_WIN)
                .orderByDesc("id").last("LIMIT 1"));
        if (win != null && win.getOperatorName() != null) {
            return win.getOperatorName();
        }
        return node.getCreateBy() == null ? UserContext.SYSTEM_NAME : ("ID:" + node.getCreateBy());
    }

    private void requirePerm(String perm) {
        try {
            if (!org.apache.shiro.SecurityUtils.getSubject().isPermitted(perm)) {
                throw new BusinessException("Forbidden：当前账号无所需权限（" + perm + "）");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception ignored) {
            // 无 Shiro 环境（服务直调）按系统操作者放行，与既有服务口径一致
        }
    }

    // ============================ 内部数据结构 ============================

    private static final class NodeDraft {
        String nodeType;
        Long orderId;
        Long batchId;
        String batchNo;
        String bizKey;
        String orgName;
        String contactName;
        String contactPhone;
        String bizStatus;
        String source;
        LocalDateTime occurredTime;
        String remark;
        Map<String, Object> snapshot;
        List<SealDraft> seals = new ArrayList<>();

        static NodeDraft of(String nodeType, Long orderId, Long batchId, String batchNo, String bizKey,
                            String orgName, String contactName, String contactPhone, String bizStatus,
                            LocalDateTime occurredTime, String remark, Map<String, Object> snapshot) {
            NodeDraft d = new NodeDraft();
            d.nodeType = nodeType;
            d.orderId = orderId;
            d.batchId = batchId;
            d.batchNo = batchNo;
            d.bizKey = bizKey;
            d.orgName = orgName;
            d.contactName = contactName;
            d.contactPhone = contactPhone;
            d.bizStatus = bizStatus;
            d.source = CcConstants.TRACE_SRC_AUTO;
            d.occurredTime = occurredTime;
            d.remark = remark;
            d.snapshot = snapshot;
            return d;
        }
    }

    private static final class SealDraft {
        final String sealNo;
        final String sealStatus;
        final String verifiedBy;
        final LocalDateTime verifyTime;
        final String handoverNo;

        SealDraft(String sealNo, String sealStatus, String verifiedBy, LocalDateTime verifyTime,
                  String handoverNo) {
            this.sealNo = sealNo;
            this.sealStatus = sealStatus;
            this.verifiedBy = verifiedBy;
            this.verifyTime = verifyTime;
            this.handoverNo = handoverNo;
        }
    }

    /** 当前账号的机构授权快照（查询期间一次性加载） */
    public static final class Grants {
        private final boolean global;
        private final Map<String, Boolean> orgContactVisible;

        private Grants(boolean global, Map<String, Boolean> orgContactVisible) {
            this.global = global;
            this.orgContactVisible = orgContactVisible;
        }

        static Grants current(TraceOrgGrantMapper mapper, String username) {
            List<TraceOrgGrant> rows = mapper.selectList(new QueryWrapper<TraceOrgGrant>()
                    .eq("username", username).eq("status", CcConstants.GRANT_ACTIVE));
            boolean global = false;
            Map<String, Boolean> map = new LinkedHashMap<>();
            for (TraceOrgGrant g : rows) {
                if (CcConstants.TRACE_ORG_ALL.equals(g.getOrgName())) {
                    global = true;
                }
                map.put(g.getOrgName(), Boolean.TRUE.equals(g.getContactVisible()));
            }
            return new Grants(global, map);
        }

        boolean canSeeOrg(String orgName) {
            if (global) {
                return true;
            }
            return orgName != null && orgContactVisible.containsKey(orgName);
        }

        boolean contactVisible(String orgName) {
            if (global) {
                Boolean v = orgContactVisible.get(CcConstants.TRACE_ORG_ALL);
                return v == null || v;
            }
            Boolean v = orgName == null ? null : orgContactVisible.get(orgName);
            return Boolean.TRUE.equals(v);
        }

        public boolean isGlobal() {
            return global;
        }

        public Set<String> orgs() {
            return Collections.unmodifiableSet(orgContactVisible.keySet());
        }
    }
}
