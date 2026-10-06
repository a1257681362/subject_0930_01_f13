package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.ReturnDecisionRequest;
import com.evops.coldchain.dto.ReturnInspectRequest;
import com.evops.coldchain.dto.ReturnIsolateRequest;
import com.evops.coldchain.dto.ReturnMoveRequest;
import com.evops.coldchain.dto.ReturnRegisterRequest;
import com.evops.coldchain.dto.ReturnReviewRequest;
import com.evops.coldchain.entity.Drug;
import com.evops.coldchain.entity.ExceptionEvent;
import com.evops.coldchain.entity.OpLog;
import com.evops.coldchain.entity.ReturnBatch;
import com.evops.coldchain.entity.ReturnDecision;
import com.evops.coldchain.entity.ReturnInspection;
import com.evops.coldchain.entity.ReturnMovement;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.DrugMapper;
import com.evops.coldchain.mapper.ExceptionEventMapper;
import com.evops.coldchain.mapper.OpLogMapper;
import com.evops.coldchain.mapper.ReturnBatchMapper;
import com.evops.coldchain.mapper.ReturnDecisionMapper;
import com.evops.coldchain.mapper.ReturnInspectionMapper;
import com.evops.coldchain.mapper.ReturnMovementMapper;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 冷链药品退货服务（V11）。
 *
 * 业务规则：
 * - 退货原因三类：收货拒签 / 温度超限 / 包装破损；登记当刻把原销售批次的批次号、药品名称、
 *   批准文号、储存温区、包装规格逐字段快照固化；原销售批次只读关联，退货流程绝不回写原批次。
 * - 退货批次进入隔离库位后不能再次出库：库位移动只能在隔离区内（隔离库位以 ISO 开头），
 *   唯一的出库路径是双人复核通过的放行/销毁决定（系统自动生成出库移动留痕）。
 * - 质量人员（QA）完成检验才能提出 放行/销毁/转待查 决定；转待查后须重新检验。
 * - 处理决定双人复核：提出人与复核人必须是不同登录账号；驳回退回已检验状态可重新提出。
 * - 每次移动记录库位、人员和时间（t_cc_return_movement 只追加）；检验与决定同样只追加。
 * - 页面、列表导出与后台接口读同一视图查询（listReturnViews）：原运输对象置为无效后
 *   三者同口径（快照不变、原批次当前状态一致展示为已作废）。
 */
@Service
public class ReturnService {

    /** 隔离库位约定前缀：入隔离与隔离区内移动的目标库位必须以此为前缀 */
    public static final String ISOLATION_LOCATION_PREFIX = "ISO";

    @Resource
    private ReturnBatchMapper returnMapper;
    @Resource
    private ReturnMovementMapper movementMapper;
    @Resource
    private ReturnInspectionMapper inspectionMapper;
    @Resource
    private ReturnDecisionMapper decisionMapper;
    @Resource
    private TransportBatchMapper batchMapper;
    @Resource
    private DrugMapper drugMapper;
    @Resource
    private ExceptionEventMapper exceptionMapper;
    @Resource
    private OpLogMapper opLogMapper;
    @Resource
    private SequenceMapper sequenceMapper;
    @Resource
    private OpLogService opLogService;

    // ---------------- 退货登记（三类退货原因 + 原批次快照只读关联） ----------------

    @Transactional
    public ReturnBatch register(ReturnRegisterRequest req) {
        requirePerm(CcConstants.PERM_RETURN_HANDLE);
        TransportBatch source = requireSourceBatch(req.getSourceBatchId());
        String reasonType = req.getReasonType() == null ? null : req.getReasonType().trim().toUpperCase();
        if (!CcConstants.RETURN_REASON_REJECTION.equals(reasonType)
                && !CcConstants.RETURN_REASON_TEMP.equals(reasonType)
                && !CcConstants.RETURN_REASON_DAMAGE.equals(reasonType)) {
            throw new BusinessException(
                    "退货原因类型非法：" + req.getReasonType() + "（仅支持 REJECTION 收货拒签 / TEMP_EXCURSION 温度超限 / PACKAGE_DAMAGE 包装破损）");
        }
        if (req.getReturnQty() == null || req.getReturnQty() <= 0) {
            throw new BusinessException("退货数量必须大于0");
        }
        if (source.getShipQty() != null && req.getReturnQty() > source.getShipQty()) {
            throw new BusinessException(String.format(
                    "退货数量 %d 超过原批次发运数量 %d", req.getReturnQty(), source.getShipQty()));
        }
        // 关联运输异常（可空）：必须属于同一原批次，且异常类型与退货原因匹配
        ExceptionEvent linked = null;
        if (req.getExceptionId() != null) {
            linked = exceptionMapper.selectById(req.getExceptionId());
            if (linked == null) {
                throw new BusinessException("关联运输异常不存在: " + req.getExceptionId());
            }
            if (!source.getId().equals(linked.getBatchId())) {
                throw new BusinessException(String.format(
                        "关联异常 %d 不属于原批次 %s，不能作为本退货的依据", linked.getId(), source.getBatchNo()));
            }
            String expect = expectedExceptionType(reasonType);
            if (!expect.equals(linked.getExceptionType())) {
                throw new BusinessException(String.format(
                        "关联异常类型 %s 与退货原因 %s 不匹配（应为 %s）",
                        linked.getExceptionType(), reasonType, expect));
            }
        }

        ReturnBatch ret = new ReturnBatch();
        ret.setReturnNo(generateReturnNo());
        ret.setSourceBatchId(source.getId());
        ret.setSourceBatchNo(source.getBatchNo());
        ret.setOrderId(source.getOrderId());
        ret.setDrugId(source.getDrugId());
        // 登记当刻快照：此后原批次撤回置无效/档案变更，退货台账口径不变
        ret.setApprovalNoSnap(source.getApprovalNoSnap());
        ret.setTempMinSnap(source.getTempMinSnap());
        ret.setTempMaxSnap(source.getTempMaxSnap());
        ret.setPackageSpecSnap(source.getPackageSpecSnap());
        Drug drug = source.getDrugId() == null ? null : drugMapper.selectById(source.getDrugId());
        ret.setDrugNameSnap(drug == null ? null : drug.getDrugName());
        ret.setReasonType(reasonType);
        ret.setReasonDetail(req.getReasonDetail().trim());
        ret.setExceptionId(linked == null ? null : linked.getId());
        ret.setReturnQty(req.getReturnQty());
        ret.setStatus(CcConstants.RETURN_REGISTERED);
        returnMapper.insert(ret);

        opLogService.log(CcConstants.ENT_RETURN, ret.getId(), source.getId(), CcConstants.ACT_RETURN_REGISTER,
                String.format("原批次 %s 登记退货 %s（原因=%s[%s]，数量=%d，关联异常=%s）；原销售批次只读关联，快照已固化",
                        source.getBatchNo(), ret.getReturnNo(), reasonType, reasonTypeName(reasonType),
                        ret.getReturnQty(), linked == null ? "无" : linked.getId()));
        return ret;
    }

    // ---------------- 入隔离库位（此后不能再次出库） ----------------

    @Transactional
    public ReturnBatch isolate(Long returnId, ReturnIsolateRequest req) {
        requirePerm(CcConstants.PERM_RETURN_HANDLE);
        ReturnBatch ret = requireReturn(returnId);
        if (!CcConstants.RETURN_REGISTERED.equals(ret.getStatus())) {
            throw new BusinessException(String.format(
                    "退货批次 %s 当前状态 %s，只有已登记（REGISTERED）才能入隔离库位",
                    ret.getReturnNo(), ret.getStatus()));
        }
        String location = requireIsolationLocation(req.getIsolationLocation());
        LocalDateTime now = LocalDateTime.now();

        ret.setStatus(CcConstants.RETURN_ISOLATED);
        ret.setCurrentLocation(location);
        ret.setIsolatedBy(UserContext.currentId());
        ret.setIsolatedByName(UserContext.currentName());
        ret.setIsolatedTime(now);
        returnMapper.updateById(ret);

        recordMovement(ret, CcConstants.MOVE_ISOLATE_IN, null, location, req.getNote());
        opLogService.log(CcConstants.ENT_RETURN, ret.getId(), ret.getSourceBatchId(), CcConstants.ACT_RETURN_ISOLATE,
                String.format("退货批次 %s 进入隔离库位 %s（操作人=%s）；此后不能再次出库，唯一出库路径为双人复核通过的放行/销毁决定",
                        ret.getReturnNo(), location, UserContext.currentName()));
        return ret;
    }

    // ---------------- 隔离区内库位移动（不能借此出库） ----------------

    @Transactional
    public ReturnBatch move(Long returnId, ReturnMoveRequest req) {
        requirePerm(CcConstants.PERM_RETURN_HANDLE);
        ReturnBatch ret = requireReturn(returnId);
        if (CcConstants.RETURN_DECISION_PENDING.equals(ret.getStatus())) {
            throw new BusinessException(String.format(
                    "退货批次 %s 处理决定待复核，库位冻结，复核完成后才能移动", ret.getReturnNo()));
        }
        if (!CcConstants.RETURN_ISOLATED.equals(ret.getStatus())
                && !CcConstants.RETURN_INSPECTED.equals(ret.getStatus())
                && !CcConstants.RETURN_PENDING_REVIEW.equals(ret.getStatus())) {
            throw new BusinessException(String.format(
                    "退货批次 %s 当前状态 %s 不允许库位移动（仅隔离区内状态 ISOLATED/INSPECTED/PENDING_REVIEW 可移动）",
                    ret.getReturnNo(), ret.getStatus()));
        }
        // 进入隔离库位后不能再次出库：移动目标必须仍为隔离库位
        String toLocation = requireIsolationLocation(req.getToLocation());
        String fromLocation = ret.getCurrentLocation();
        if (toLocation.equals(fromLocation)) {
            throw new BusinessException("目标库位与当前库位相同，无需移动: " + toLocation);
        }

        ret.setCurrentLocation(toLocation);
        returnMapper.updateById(ret);

        recordMovement(ret, CcConstants.MOVE_MOVE, fromLocation, toLocation, req.getNote());
        opLogService.log(CcConstants.ENT_RETURN, ret.getId(), ret.getSourceBatchId(), CcConstants.ACT_RETURN_MOVE,
                String.format("退货批次 %s 隔离区内移动 %s -> %s（操作人=%s）",
                        ret.getReturnNo(), fromLocation, toLocation, UserContext.currentName()));
        return ret;
    }

    // ---------------- 质量检验（QA；完成检验才能提出处理决定） ----------------

    @Transactional
    public ReturnInspection inspect(Long returnId, ReturnInspectRequest req) {
        requirePerm(CcConstants.PERM_RETURN_INSPECT);
        ReturnBatch ret = requireReturn(returnId);
        if (!CcConstants.RETURN_ISOLATED.equals(ret.getStatus())
                && !CcConstants.RETURN_PENDING_REVIEW.equals(ret.getStatus())) {
            throw new BusinessException(String.format(
                    "退货批次 %s 当前状态 %s 不能检验：须先入隔离库位（ISOLATED），转待查（PENDING_REVIEW）后须重新检验",
                    ret.getReturnNo(), ret.getStatus()));
        }
        String result = req.getInspectResult() == null ? null : req.getInspectResult().trim().toUpperCase();
        if (!CcConstants.INSPECT_RESULT_PASS.equals(result) && !CcConstants.INSPECT_RESULT_FAIL.equals(result)) {
            throw new BusinessException("检验结论非法：" + req.getInspectResult() + "（仅支持 PASS 合格 / FAIL 不合格）");
        }

        ReturnInspection insp = new ReturnInspection();
        insp.setReturnId(ret.getId());
        insp.setInspectResult(result);
        insp.setInspectNote(req.getInspectNote().trim());
        insp.setInspectorId(UserContext.currentId());
        insp.setInspectorName(UserContext.currentName());
        insp.setInspectTime(LocalDateTime.now());
        inspectionMapper.insert(insp);

        ret.setStatus(CcConstants.RETURN_INSPECTED);
        returnMapper.updateById(ret);

        opLogService.log(CcConstants.ENT_RETURN_INSPECTION, ret.getId(), ret.getSourceBatchId(),
                CcConstants.ACT_RETURN_INSPECT,
                String.format("退货批次 %s 质量检验完成（结论=%s，检验人=%s）；此后可提出放行/销毁/转待查决定",
                        ret.getReturnNo(), result, insp.getInspectorName()));
        return insp;
    }

    // ---------------- 提出处理决定（检验完成后；双人复核才生效） ----------------

    @Transactional
    public ReturnDecision proposeDecision(Long returnId, ReturnDecisionRequest req) {
        requirePerm(CcConstants.PERM_RETURN_DECIDE);
        ReturnBatch ret = requireReturn(returnId);
        if (!CcConstants.RETURN_INSPECTED.equals(ret.getStatus())) {
            throw new BusinessException(String.format(
                    "退货批次 %s 当前状态 %s 不能提出处理决定：质量人员完成检验（INSPECTED）后才能选择放行/销毁/转待查",
                    ret.getReturnNo(), ret.getStatus()));
        }
        String type = req.getDecisionType() == null ? null : req.getDecisionType().trim().toUpperCase();
        if (!CcConstants.DECISION_RELEASE.equals(type) && !CcConstants.DECISION_DESTROY.equals(type)
                && !CcConstants.DECISION_PENDING_REVIEW.equals(type)) {
            throw new BusinessException(
                    "处理决定类型非法：" + req.getDecisionType() + "（仅支持 RELEASE 放行 / DESTROY 销毁 / PENDING_REVIEW 转待查）");
        }
        String target = trim(req.getTargetLocation());
        if (CcConstants.DECISION_PENDING_REVIEW.equals(type)) {
            if (target != null) {
                throw new BusinessException("转待查决定不留目标库位：批次继续留在隔离区 " + ret.getCurrentLocation());
            }
        } else {
            if (target == null) {
                throw new BusinessException("放行/销毁决定必须给出目标库位（放行->合格品库位，销毁->销毁区）");
            }
            if (isIsolationLocation(target)) {
                throw new BusinessException(String.format(
                        "目标库位 %s 仍是隔离库位：放行/销毁是出隔离区的唯一路径，目标必须为隔离区外库位", target));
            }
        }

        ReturnDecision decision = new ReturnDecision();
        decision.setReturnId(ret.getId());
        decision.setDecisionType(type);
        decision.setReason(req.getReason().trim());
        decision.setTargetLocation(target);
        decision.setStatus(CcConstants.DECISION_ST_PENDING);
        decision.setProposeBy(UserContext.currentId());
        decision.setProposeName(UserContext.currentName());
        decision.setProposeTime(LocalDateTime.now());
        decisionMapper.insert(decision);

        ret.setStatus(CcConstants.RETURN_DECISION_PENDING);
        returnMapper.updateById(ret);

        opLogService.log(CcConstants.ENT_RETURN_DECISION, ret.getId(), ret.getSourceBatchId(),
                CcConstants.ACT_RETURN_DECIDE,
                String.format("退货批次 %s 提出处理决定 #%d（%s，提出人=%s，目标库位=%s）；待第二人复核",
                        ret.getReturnNo(), decision.getId(), decisionTypeName(type),
                        decision.getProposeName(), target == null ? "留在隔离区" : target));
        return decision;
    }

    // ---------------- 双人复核（第二人确认才生效；驳回退回已检验） ----------------

    @Transactional
    public ReturnDecision reviewDecision(Long decisionId, ReturnReviewRequest req) {
        requirePerm(CcConstants.PERM_RETURN_REVIEW);
        ReturnDecision decision = decisionMapper.selectById(decisionId);
        if (decision == null) {
            throw new BusinessException("处理决定不存在: " + decisionId);
        }
        if (!CcConstants.DECISION_ST_PENDING.equals(decision.getStatus())) {
            throw new BusinessException(String.format(
                    "处理决定 #%d 已复核（状态=%s），不能重复复核", decisionId, decision.getStatus()));
        }
        // 双人复核：复核人必须是不同于提出人的登录账号
        Long currentId = UserContext.currentId();
        String currentName = UserContext.currentName();
        if (decision.getProposeBy().equals(currentId) || decision.getProposeName().equals(currentName)) {
            throw new BusinessException(String.format(
                    "双人复核：复核人 %s 与提出人 %s 是同一账号，必须由第二个登录账号复核",
                    currentName, decision.getProposeName()));
        }
        ReturnBatch ret = requireReturn(decision.getReturnId());
        if (!CcConstants.RETURN_DECISION_PENDING.equals(ret.getStatus())) {
            throw new BusinessException(String.format(
                    "退货批次 %s 当前状态 %s 与待复核决定不符，拒绝复核", ret.getReturnNo(), ret.getStatus()));
        }

        LocalDateTime now = LocalDateTime.now();
        decision.setReviewBy(currentId);
        decision.setReviewName(currentName);
        decision.setReviewTime(now);
        decision.setReviewNote(req.getReviewNote().trim());

        if (Boolean.TRUE.equals(req.getApproved())) {
            decision.setStatus(CcConstants.DECISION_ST_CONFIRMED);
            applyDecision(ret, decision, now);
        } else {
            // 驳回：退回已检验状态，可重新提出决定；决定行原样保留
            decision.setStatus(CcConstants.DECISION_ST_REJECTED);
            ret.setStatus(CcConstants.RETURN_INSPECTED);
            returnMapper.updateById(ret);
        }
        decisionMapper.updateById(decision);

        opLogService.log(CcConstants.ENT_RETURN_DECISION, ret.getId(), ret.getSourceBatchId(),
                CcConstants.ACT_RETURN_REVIEW,
                String.format("退货批次 %s 处理决定 #%d 双人复核%s（%s，提出人=%s，复核人=%s）；批次状态=%s",
                        ret.getReturnNo(), decision.getId(),
                        Boolean.TRUE.equals(req.getApproved()) ? "通过" : "驳回",
                        decisionTypeName(decision.getDecisionType()),
                        decision.getProposeName(), currentName, ret.getStatus()));
        return decision;
    }

    /** 复核通过：应用决定（放行/销毁生成出库移动；转待查留在隔离区） */
    private void applyDecision(ReturnBatch ret, ReturnDecision decision, LocalDateTime now) {
        String type = decision.getDecisionType();
        if (CcConstants.DECISION_RELEASE.equals(type)) {
            String from = ret.getCurrentLocation();
            ret.setStatus(CcConstants.RETURN_RELEASED);
            ret.setCurrentLocation(decision.getTargetLocation());
            ret.setFinalDecision(CcConstants.DECISION_RELEASE);
            ret.setFinalTime(now);
            returnMapper.updateById(ret);
            recordMovement(ret, CcConstants.MOVE_RELEASE_OUT, from, decision.getTargetLocation(),
                    "双人复核通过放行出库（决定 #" + decision.getId() + "）");
        } else if (CcConstants.DECISION_DESTROY.equals(type)) {
            String from = ret.getCurrentLocation();
            ret.setStatus(CcConstants.RETURN_DESTROYED);
            ret.setCurrentLocation(decision.getTargetLocation());
            ret.setFinalDecision(CcConstants.DECISION_DESTROY);
            ret.setFinalTime(now);
            returnMapper.updateById(ret);
            recordMovement(ret, CcConstants.MOVE_DESTROY_OUT, from, decision.getTargetLocation(),
                    "双人复核通过销毁出库（决定 #" + decision.getId() + "）");
        } else {
            // 转待查：留在隔离区，不产生移动；须重新检验后才能再决定
            ret.setStatus(CcConstants.RETURN_PENDING_REVIEW);
            returnMapper.updateById(ret);
        }
    }

    // ---------------- 查询视图（页面 / 列表导出 / 后台接口同口径） ----------------

    /**
     * 退货批次列表视图：页面、列表 CSV 导出、后台接口共用本方法，保证
     * “运输对象置为无效后再查退货批次隔离处理”三者同口径——
     * 快照字段不变，原批次当前状态（含已作废）实时只读解析、一致展示。
     */
    public List<Map<String, Object>> listReturnViews(String status) {
        QueryWrapper<ReturnBatch> qw = new QueryWrapper<ReturnBatch>().orderByDesc("id");
        if (status != null && !status.trim().isEmpty()) {
            qw.eq("status", status.trim().toUpperCase());
        }
        List<ReturnBatch> returns = returnMapper.selectList(qw);
        List<Map<String, Object>> views = new ArrayList<>();
        for (ReturnBatch ret : returns) {
            views.add(toView(ret));
        }
        return views;
    }

    /** 单条退货视图（与列表同一口径） */
    public Map<String, Object> toView(ReturnBatch ret) {
        // 原销售批次只读关联：只读取当前状态用于展示，绝不回写
        TransportBatch source = batchMapper.selectById(ret.getSourceBatchId());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("returnBatch", ret);
        view.put("reasonTypeName", reasonTypeName(ret.getReasonType()));
        view.put("statusName", statusName(ret.getStatus()));
        view.put("sourceBatchStatus", source == null ? null : source.getStatus());
        view.put("sourceBatchStatusName", source == null ? "原批次不存在" : sourceBatchStatusName(source.getStatus()));
        view.put("sourceBatchInvalid", source == null || CcConstants.BATCH_REVOKED.equals(source.getStatus()));
        return view;
    }

    /** 退货详情：主单 + 移动留痕 + 检验记录 + 处理决定（含双人复核） */
    public Map<String, Object> detail(Long returnId) {
        ReturnBatch ret = requireReturn(returnId);
        Map<String, Object> detail = new LinkedHashMap<>(toView(ret));
        detail.put("movements", listMovements(returnId));
        detail.put("inspections", listInspections(returnId));
        detail.put("decisions", listDecisions(returnId));
        return detail;
    }

    /** 证据链：登记快照 + 移动 + 检验 + 决定复核 + 操作日志（一单到底，只读导出） */
    public Map<String, Object> evidenceChain(Long returnId) {
        Map<String, Object> chain = new LinkedHashMap<>(detail(returnId));
        chain.put("opLogs", opLogMapper.selectList(new QueryWrapper<OpLog>()
                .in("entity_type", Arrays.asList(CcConstants.ENT_RETURN, CcConstants.ENT_RETURN_MOVEMENT,
                        CcConstants.ENT_RETURN_INSPECTION, CcConstants.ENT_RETURN_DECISION))
                .eq("entity_id", returnId)
                .orderByAsc("id")));
        return chain;
    }

    public List<ReturnMovement> listMovements(Long returnId) {
        return movementMapper.selectList(new QueryWrapper<ReturnMovement>()
                .eq("return_id", returnId).orderByAsc("id"));
    }

    public List<ReturnInspection> listInspections(Long returnId) {
        return inspectionMapper.selectList(new QueryWrapper<ReturnInspection>()
                .eq("return_id", returnId).orderByAsc("id"));
    }

    public List<ReturnDecision> listDecisions(Long returnId) {
        return decisionMapper.selectList(new QueryWrapper<ReturnDecision>()
                .eq("return_id", returnId).orderByAsc("id"));
    }

    /** 原销售批次名下的全部退货批次（台账只读关联块） */
    public List<ReturnBatch> listBySourceBatch(Long sourceBatchId) {
        return returnMapper.selectList(new QueryWrapper<ReturnBatch>()
                .eq("source_batch_id", sourceBatchId).orderByAsc("id"));
    }

    public ReturnBatch requireReturn(Long returnId) {
        ReturnBatch ret = returnMapper.selectById(returnId);
        if (ret == null) {
            throw new BusinessException("退货批次不存在: " + returnId);
        }
        return ret;
    }

    // ---------------- 展示名（页面/导出/后台同一套口径） ----------------

    public static String reasonTypeName(String reasonType) {
        if (CcConstants.RETURN_REASON_REJECTION.equals(reasonType)) {
            return "收货拒签";
        }
        if (CcConstants.RETURN_REASON_TEMP.equals(reasonType)) {
            return "温度超限";
        }
        if (CcConstants.RETURN_REASON_DAMAGE.equals(reasonType)) {
            return "包装破损";
        }
        return String.valueOf(reasonType);
    }

    public static String statusName(String status) {
        switch (String.valueOf(status)) {
            case CcConstants.RETURN_REGISTERED: return "已登记";
            case CcConstants.RETURN_ISOLATED: return "已入隔离";
            case CcConstants.RETURN_INSPECTED: return "已检验";
            case CcConstants.RETURN_DECISION_PENDING: return "决定待复核";
            case CcConstants.RETURN_RELEASED: return "已放行";
            case CcConstants.RETURN_DESTROYED: return "已销毁";
            case CcConstants.RETURN_PENDING_REVIEW: return "转待查";
            default: return String.valueOf(status);
        }
    }

    public static String decisionTypeName(String decisionType) {
        switch (String.valueOf(decisionType)) {
            case CcConstants.DECISION_RELEASE: return "放行";
            case CcConstants.DECISION_DESTROY: return "销毁";
            case CcConstants.DECISION_PENDING_REVIEW: return "转待查";
            default: return String.valueOf(decisionType);
        }
    }

    public static String moveTypeName(String moveType) {
        switch (String.valueOf(moveType)) {
            case CcConstants.MOVE_ISOLATE_IN: return "入隔离";
            case CcConstants.MOVE_MOVE: return "隔离区内移动";
            case CcConstants.MOVE_RELEASE_OUT: return "放行出库";
            case CcConstants.MOVE_DESTROY_OUT: return "销毁出库";
            default: return String.valueOf(moveType);
        }
    }

    public static String sourceBatchStatusName(String status) {
        switch (String.valueOf(status)) {
            case CcConstants.BATCH_DISPATCHED: return "已派车";
            case CcConstants.BATCH_LOADING: return "装车中";
            case CcConstants.BATCH_SHIPPED: return "在途";
            case CcConstants.BATCH_EXCEPTION: return "异常挂起";
            case CcConstants.BATCH_SIGNED: return "已签收";
            case CcConstants.BATCH_REVOKED: return "已作废（无效）";
            default: return String.valueOf(status);
        }
    }

    // ---------------- 内部 helpers ----------------

    private void recordMovement(ReturnBatch ret, String moveType, String from, String to, String note) {
        ReturnMovement movement = new ReturnMovement();
        movement.setReturnId(ret.getId());
        movement.setMoveType(moveType);
        movement.setFromLocation(from);
        movement.setToLocation(to);
        movement.setOperatorId(UserContext.currentId());
        movement.setOperatorName(UserContext.currentName());
        movement.setMoveTime(LocalDateTime.now());
        movement.setNote(trim(note));
        movementMapper.insert(movement);
    }

    private TransportBatch requireSourceBatch(Long batchId) {
        TransportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BusinessException("原销售运输批次不存在: " + batchId);
        }
        return batch;
    }

    /** 退货原因 -> 对应运输异常类型（关联异常时校验匹配） */
    private String expectedExceptionType(String reasonType) {
        switch (reasonType) {
            case CcConstants.RETURN_REASON_REJECTION: return CcConstants.EXC_REJECTION;
            case CcConstants.RETURN_REASON_TEMP: return CcConstants.EXC_TEMP;
            case CcConstants.RETURN_REASON_DAMAGE: return CcConstants.EXC_DAMAGE;
            default: throw new BusinessException("未知退货原因类型: " + reasonType);
        }
    }

    private boolean isIsolationLocation(String location) {
        return location != null && location.trim().toUpperCase().startsWith(ISOLATION_LOCATION_PREFIX);
    }

    private String requireIsolationLocation(String location) {
        String loc = trim(location);
        if (loc == null) {
            throw new BusinessException("库位不能为空");
        }
        if (!isIsolationLocation(loc)) {
            throw new BusinessException(String.format(
                    "退货批次进入隔离库位后不能再次出库：目标库位 %s 不是隔离库位（隔离库位须以 %s 开头）",
                    loc, ISOLATION_LOCATION_PREFIX));
        }
        return loc;
    }

    private void requirePerm(String perm) {
        try {
            if (!org.apache.shiro.SecurityUtils.getSubject().isPermitted(perm)) {
                throw new BusinessException("Forbidden：当前账号无所需权限（" + perm + "）");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception ignored) {
            // 无 Shiro 环境（服务直调）按系统操作者放行，与 UserContext 回退口径一致
        }
    }

    /** 稳定退货单号：TH+8位序列（序列由 H2 生成，单调唯一，绝不复用） */
    private String generateReturnNo() {
        return String.format("TH%08d", sequenceMapper.nextReturnNo() % 100000000L);
    }

    private String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
