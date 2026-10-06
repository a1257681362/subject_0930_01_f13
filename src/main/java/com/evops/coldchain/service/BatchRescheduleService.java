package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.BatchRescheduleRequest;
import com.evops.coldchain.entity.BatchReschedule;
import com.evops.coldchain.entity.CarrierNotifyLog;
import com.evops.coldchain.entity.LockedPrice;
import com.evops.coldchain.entity.ReceiveOrg;
import com.evops.coldchain.entity.RescheduleImpact;
import com.evops.coldchain.entity.RescheduleTodo;
import com.evops.coldchain.entity.RouteSegmentSnap;
import com.evops.coldchain.entity.ShipmentOrder;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.entity.Vehicle;
import com.evops.coldchain.mapper.CarrierNotifyLogMapper;
import com.evops.coldchain.mapper.BatchRescheduleMapper;
import com.evops.coldchain.mapper.LockedPriceMapper;
import com.evops.coldchain.mapper.RescheduleImpactMapper;
import com.evops.coldchain.mapper.RescheduleTodoMapper;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.coldchain.mapper.ShipmentOrderMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.coldchain.mapper.VehicleMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 运输批次改期服务（V10 送达时窗）。
 *
 * 不变量：
 * 1. 承诺到达时间（committed_arrival_time）派车时固化、只写一次；任何改期都不动它，
 *    改期只按新计划发运重新解析预计到达（estimated_arrival_time）。
 * 2. 未装车（DISPATCHED）改期：申请即 AUTO 生效，仍完整留痕；
 *    一旦进入装车（LOADING，含在途 SHIPPED/异常挂起 EXCEPTION）：落 PENDING 必须审批，
 *    同意后才应用，驳回批次/发货单任何字段不变。
 * 3. 每次改期（无论自动还是审批）都列出受影响的温控区间、承运通知对象、待办责任人，
 *    生效后向承运方投递通知（失败可重试）、生成待办；全程只追加，历史版本可追查。
 * 4. 解析所采用的时窗规则版本、节假日集、运输时长逐字段快照到改期单；
 *    参与结论的节假日/闭馆规则当场冻结，此后节假日旧结论不可改动。
 */
@Service
public class BatchRescheduleService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Resource
    private BatchRescheduleMapper rescheduleMapper;
    @Resource
    private RescheduleImpactMapper impactMapper;
    @Resource
    private CarrierNotifyLogMapper notifyMapper;
    @Resource
    private RescheduleTodoMapper todoMapper;
    @Resource
    private TransportBatchMapper batchMapper;
    @Resource
    private ShipmentOrderMapper orderMapper;
    @Resource
    private VehicleMapper vehicleMapper;
    @Resource
    private LockedPriceMapper lockedPriceMapper;
    @Resource
    private SequenceMapper sequenceMapper;
    @Resource
    private DeliveryWindowService windowService;
    @Resource
    private RouteService routeService;
    @Resource
    private OpLogService opLogService;
    @Resource
    private ExceptionNotifyTransport notifyTransport;

    /** 承运通知 webhook：为空=站内留痕必达；配置后非 2xx 记失败、可手动重试 */
    @Value("${coldchain.carrier-notify.endpoint:}")
    private String carrierNotifyEndpoint;

    // ---------------- 装车节点 ----------------

    /** 装车登记：DISPATCHED -> LOADING（进入该节点后改期必须审批） */
    @Transactional
    public TransportBatch markLoading(Long batchId) {
        TransportBatch batch = requireActiveBatch(batchId);
        if (!CcConstants.BATCH_DISPATCHED.equals(batch.getStatus())) {
            throw new BusinessException(String.format(
                    "仅已派车未装车批次允许装车登记，批次 %s 当前状态=%s", batch.getBatchNo(), batch.getStatus()));
        }
        batch.setStatus(CcConstants.BATCH_LOADING);
        batch.setLoadingTime(LocalDateTime.now());
        batchMapper.updateById(batch);

        opLogService.log(CcConstants.ENT_BATCH, batchId, batchId, CcConstants.ACT_BATCH_LOAD,
                String.format("批次 %s 进入装车状态（%s）；此后改期必须审批，系统列出受影响温控区间、承运通知与待办责任人",
                        batch.getBatchNo(), batch.getLoadingTime()));
        return batch;
    }

    // ---------------- 改期申请 ----------------

    @Transactional
    public BatchReschedule apply(BatchRescheduleRequest req) {
        TransportBatch batch = requireActiveBatch(req.getBatchId());
        String status = batch.getStatus();
        if (CcConstants.BATCH_SIGNED.equals(status)) {
            throw new BusinessException("批次已签收闭环，送达时窗已完成，不能改期: " + batch.getBatchNo());
        }
        BatchReschedule pending = findPending(batch.getId());
        if (pending != null) {
            throw new BusinessException(String.format(
                    "批次 %s 的改期单 %s 仍停留待审批（PENDING），不能重复改期",
                    batch.getBatchNo(), pending.getRescheduleNo()));
        }
        ReceiveOrg org = windowService.findByName(batch.getReceiverOrg());
        if (org == null) {
            throw new BusinessException(String.format(
                    "收货机构[%s]尚未维护送达时窗主档，无法按工作日区间/节假日重新计算预计到达；"
                            + "请先建档并发布时窗规则", batch.getReceiverOrg()));
        }
        org = windowService.requireActiveOrg(org.getId());

        // 以新计划发运按"当前生效规则"重算预计到达；承诺时间不参与、不改写
        DeliveryWindowService.WindowResolution resolution = windowService.resolveEta(org, req.getNewPlanShipTime());

        ShipmentOrder order = orderMapper.selectById(batch.getOrderId());
        BatchReschedule rs = new BatchReschedule();
        rs.setRescheduleNo("RC" + sequenceMapper.nextRescheduleNo());
        rs.setBatchId(batch.getId());
        rs.setReceiverOrg(batch.getReceiverOrg());
        rs.setRuleId(resolution.getRuleId());
        rs.setRuleVersionNo(resolution.getRuleVersionNo());
        rs.setHolidayIdsSnap(joinIds(resolution.getAppliedHolidayIds()));
        rs.setOldPlanShipTime(order == null ? null : order.getPlanShipTime());
        rs.setOldEstimatedArrival(batch.getEstimatedArrivalTime());
        rs.setNewPlanShipTime(req.getNewPlanShipTime());
        rs.setNewEstimatedArrival(resolution.getEta());
        rs.setTransportMinutesSnap(org.getTransportMinutes());
        rs.setReason(req.getReason().trim());
        rs.setApplyBy(UserContext.currentId());
        rs.setApplyName(UserContext.currentName());

        // 只要越过"已派车未装车"节点（LOADING/SHIPPED/EXCEPTION），改期一律审批
        boolean requireApproval = !CcConstants.BATCH_DISPATCHED.equals(status);
        rs.setRequireApproval(requireApproval);

        if (requireApproval) {
            // 已装车（含在途/异常挂起）：先落待审批，受影响分析随单列出供审批判断；批次暂不变
            rs.setStatus(CcConstants.RESCHEDULE_PENDING);
            rescheduleMapper.insert(rs);
            buildImpacts(rs, batch, org);
            opLogService.log(CcConstants.ENT_RESCHEDULE, rs.getId(), batch.getId(),
                    CcConstants.ACT_BATCH_RESCHEDULE,
                    String.format("批次 %s 改期申请 %s：已进入装车/在途（状态=%s），必须审批；"
                                    + "新计划发运=%s，按 %s 时窗 V%d 重算预计到达=%s（承诺到达=%s 保持不变）；"
                                    + "已列出受影响温控区间/承运通知/待办责任人。原因：%s",
                            batch.getBatchNo(), rs.getRescheduleNo(), status,
                            fmt(rs.getNewPlanShipTime()), org.getOrgName(), rs.getRuleVersionNo(),
                            fmt(rs.getNewEstimatedArrival()), fmt(batch.getCommittedArrivalTime()),
                            rs.getReason()));
            return rs;
        }

        // 未装车：直接生效
        rs.setStatus(CcConstants.RESCHEDULE_AUTO);
        rs.setAppliedTime(LocalDateTime.now());
        rescheduleMapper.insert(rs);
        buildImpacts(rs, batch, org);
        applyReschedule(batch, order, org, rs);
        opLogService.log(CcConstants.ENT_RESCHEDULE, rs.getId(), batch.getId(),
                CcConstants.ACT_BATCH_RESCHEDULE,
                String.format("批次 %s 改期 %s：未装车直接生效；计划发运 %s -> %s，预计到达 %s -> %s"
                                + "（按 %s 时窗 V%d 与 %d 条节假日/闭馆规则解析；原承诺到达=%s 未改写）。原因：%s",
                        batch.getBatchNo(), rs.getRescheduleNo(),
                        fmt(rs.getOldPlanShipTime()), fmt(rs.getNewPlanShipTime()),
                        fmt(rs.getOldEstimatedArrival()), fmt(rs.getNewEstimatedArrival()),
                        org.getOrgName(), rs.getRuleVersionNo(),
                        resolution.getAppliedHolidayIds().size(),
                        fmt(batch.getCommittedArrivalTime()), rs.getReason()));
        return rs;
    }

    // ---------------- 审批 ----------------

    @Transactional
    public BatchReschedule approve(Long rescheduleId, String reviewNote) {
        return review(rescheduleId, true, reviewNote);
    }

    @Transactional
    public BatchReschedule reject(Long rescheduleId, String reviewNote) {
        return review(rescheduleId, false, reviewNote);
    }

    private BatchReschedule review(Long rescheduleId, boolean approved, String reviewNote) {
        BatchReschedule rs = requireReschedule(rescheduleId);
        if (!CcConstants.RESCHEDULE_PENDING.equals(rs.getStatus())) {
            throw new BusinessException(String.format(
                    "改期单 %s 当前状态=%s，仅待审批（PENDING）改期可审查",
                    rs.getRescheduleNo(), rs.getStatus()));
        }
        TransportBatch batch = requireActiveBatch(rs.getBatchId());
        rs.setReviewBy(UserContext.currentId());
        rs.setReviewName(UserContext.currentName());
        rs.setReviewTime(LocalDateTime.now());
        rs.setReviewNote(reviewNote.trim());

        if (!approved) {
            rs.setStatus(CcConstants.RESCHEDULE_REJECTED);
            rescheduleMapper.updateById(rs);
            // 驳回：批次/发货单任何计划字段不变，受影响明细保留可查
            opLogService.log(CcConstants.ENT_RESCHEDULE, rs.getId(), rs.getBatchId(),
                    CcConstants.ACT_RESCHEDULE_REJECT,
                    String.format("批次 %s 装车后改期单 %s 驳回：计划发运/预计到达/承诺时间均不变。意见：%s",
                            batch.getBatchNo(), rs.getRescheduleNo(), rs.getReviewNote()));
            return rs;
        }

        rs.setStatus(CcConstants.RESCHEDULE_APPROVED);
        rs.setAppliedTime(LocalDateTime.now());
        rescheduleMapper.updateById(rs);

        ReceiveOrg org = windowService.findByName(rs.getReceiverOrg());
        if (org == null) {
            throw new BusinessException("改期引用的收货机构主档已不存在: " + rs.getReceiverOrg());
        }
        ShipmentOrder order = orderMapper.selectById(batch.getOrderId());
        applyReschedule(batch, order, org, rs);

        opLogService.log(CcConstants.ENT_RESCHEDULE, rs.getId(), rs.getBatchId(),
                CcConstants.ACT_RESCHEDULE_APPROVE,
                String.format("批次 %s 装车后改期单 %s 审批同意并生效：预计到达 %s -> %s"
                                + "（按 %s 时窗 V%d 解析；承诺到达=%s 始终未改写），承运通知已投递、待办已生成。意见：%s",
                        batch.getBatchNo(), rs.getRescheduleNo(),
                        fmt(rs.getOldEstimatedArrival()), fmt(rs.getNewEstimatedArrival()),
                        org.getOrgName(), rs.getRuleVersionNo(),
                        fmt(batch.getCommittedArrivalTime()), rs.getReviewNote()));
        return rs;
    }

    /**
     * 把改期结果应用到批次/发货单（只改预计到达；承诺时间永不入更新列）。
     * - 未发运（DISPATCHED/LOADING）：同步计划发运时间到发货单；
     * - 已发运（SHIPPED/EXCEPTION）：实际发运已成事实，不回写发货单计划，仅更新预计到达。
     */
    private void applyReschedule(TransportBatch batch, ShipmentOrder order, ReceiveOrg org, BatchReschedule rs) {
        boolean beforeShip = CcConstants.BATCH_DISPATCHED.equals(batch.getStatus())
                || CcConstants.BATCH_LOADING.equals(batch.getStatus());
        if (beforeShip && order != null) {
            order.setPlanShipTime(rs.getNewPlanShipTime());
            orderMapper.updateById(order);
        }
        batch.setEstimatedArrivalTime(rs.getNewEstimatedArrival());
        batchMapper.updateById(batch);

        // 参与本次已生效结论的节假日/临时闭馆规则当场冻结（旧结论此后不可改动）
        windowService.freezeHolidays(splitIds(rs.getHolidayIdsSnap()), batch.getId());

        // 承运通知 + 待办在生效事务内生成（通知失败不回滚改期，失败行留痕可重试——与 V6 同口径）
        sendCarrierNotification(rs, batch, null);
        createTodos(rs, batch, org);
    }

    // ---------------- 受影响分析 ----------------

    /** 温控区间 / 承运通知对象 / 待办责任人（审批前即随单列出，供审批判断） */
    private void buildImpacts(BatchReschedule rs, TransportBatch batch, ReceiveOrg org) {
        // 1) 受影响温控区间：有路线分段快照逐段列出；无路线列全程档案温区（segmentSeq=0）
        List<RouteSegmentSnap> snaps = batch.getRouteVersionId() == null
                ? new ArrayList<>() : routeService.listSnapshotsByBatch(batch.getId());
        if (snaps.isEmpty()) {
            addImpact(rs, batch, CcConstants.IMPACT_TEMP_ZONE, null, 0,
                    batch.getTempMinSnap(), batch.getTempMaxSnap(),
                    batch.getResponsibleParty(), null,
                    String.format("全程档案温区 %s~%s℃ 的在途温控安排随新预计到达 %s 调整",
                            batch.getTempMinSnap(), batch.getTempMaxSnap(), fmt(rs.getNewEstimatedArrival())));
        } else {
            for (RouteSegmentSnap s : snaps) {
                addImpact(rs, batch, CcConstants.IMPACT_TEMP_ZONE, s.getId(), s.getSegmentSeq(),
                        s.getTempMin(), s.getTempMax(), s.getOwnerName(), s.getOwnerPhone(),
                        String.format("里程 %s~%skm 第%d段允许温区 %s~%s℃，责任人=%s：到达计划改变需重新核对温控衔接",
                                s.getFromKm(), s.getToKm(), s.getSegmentSeq(),
                                s.getTempMin(), s.getTempMax(), s.getOwnerName()));
            }
        }

        // 2) 承运通知对象：车辆（承运商/驾驶员）+ 中选锁价承运商
        Vehicle vehicle = vehicleMapper.selectById(batch.getVehicleId());
        String carrierName = vehicle == null ? null : vehicle.getCarrierName();
        String driverName = vehicle == null ? null : vehicle.getDriverName();
        String driverPhone = vehicle == null ? null : vehicle.getDriverPhone();
        Long lockedPriceId = batch.getLockedPriceId();
        if (lockedPriceId != null) {
            LockedPrice lp = lockedPriceMapper.selectById(lockedPriceId);
            if (lp != null && (carrierName == null || carrierName.trim().isEmpty())) {
                carrierName = lp.getCarrierNameSnap();
            }
        }
        String carrierDetail = String.format(
                "承运方 %s（驾驶员=%s，电话=%s）须按新计划发运 %s / 新预计到达 %s 执行；%s",
                nz(carrierName), nz(driverName), nz(driverPhone),
                fmt(rs.getNewPlanShipTime()), fmt(rs.getNewEstimatedArrival()),
                lockedPriceId == null ? "本批次未走锁价"
                        : String.format("关联锁价单ID=%d，运输服务约定不变，仅时刻调整", lockedPriceId));
        RescheduleImpact carrierImpact = addImpact(rs, batch, CcConstants.IMPACT_CARRIER_NOTICE,
                lockedPriceId, null, null, null,
                carrierName, driverPhone, carrierDetail);

        // 3) 待办责任人：调度 / 承运 / 收货（+ 温控异常时质量复核）
        addImpact(rs, batch, CcConstants.IMPACT_TODO_OWNER, null, null, null, null,
                CcConstants.ROLE_DISPATCHER, null, "调度：更新发车计划并跟踪新预计到达，确认时窗可接收");
        addImpact(rs, batch, CcConstants.IMPACT_TODO_OWNER, null, null, null, null,
                CcConstants.ROLE_CARRIER, nz(driverName),
                "承运/驾驶员：按新计划发运时刻到厂装车并保持全程温控");
        addImpact(rs, batch, CcConstants.IMPACT_TODO_OWNER, null, null, null, null,
                CcConstants.ROLE_RECEIVER, org.getContactName(),
                String.format("收货机构 %s：按新预计到达 %s（%s）安排收货人力与冷库库位",
                        org.getOrgName(), fmt(rs.getNewEstimatedArrival()), nz(org.getContactName())));
        if (Boolean.TRUE.equals(batch.getHasTempExcursion())) {
            addImpact(rs, batch, CcConstants.IMPACT_TODO_OWNER, null, null, null, null,
                    CcConstants.ROLE_QA, null,
                    "质量复核：该批次途中曾温度越限，改期后须复核在途温控合规性与异常处置时限");
        }
    }

    private RescheduleImpact addImpact(BatchReschedule rs, TransportBatch batch, String type, Long refId,
                                       Integer segmentSeq, java.math.BigDecimal tempMin,
                                       java.math.BigDecimal tempMax, String ownerName,
                                       String ownerPhone, String detail) {
        RescheduleImpact impact = new RescheduleImpact();
        impact.setRescheduleId(rs.getId());
        impact.setBatchId(batch.getId());
        impact.setImpactType(type);
        impact.setRefId(refId);
        impact.setSegmentSeq(segmentSeq);
        impact.setTempMin(tempMin);
        impact.setTempMax(tempMax);
        impact.setOwnerName(ownerName);
        impact.setOwnerPhone(ownerPhone);
        impact.setDetail(detail);
        impactMapper.insert(impact);
        return impact;
    }

    // ---------------- 承运通知（只追加，可重试） ----------------

    private void sendCarrierNotification(BatchReschedule rs, TransportBatch batch, String retryReason) {
        int attempt = nextAttemptNo(rs.getId());
        Vehicle vehicle = vehicleMapper.selectById(batch.getVehicleId());
        String targetName = vehicle == null ? batch.getResponsibleOrg()
                : vehicle.getCarrierName() + (vehicle.getDriverName() == null ? "" : "/" + vehicle.getDriverName());
        String targetPhone = vehicle == null ? null : vehicle.getDriverPhone();

        CarrierNotifyLog logRow = new CarrierNotifyLog();
        logRow.setRescheduleId(rs.getId());
        logRow.setBatchId(batch.getId());
        logRow.setAttemptNo(attempt);
        logRow.setTargetName(targetName);
        logRow.setTargetPhone(targetPhone);
        logRow.setSendTime(LocalDateTime.now());
        logRow.setRetryReason(retryReason);

        ExceptionNotifyTransport.SendResult result;
        if (carrierNotifyEndpoint == null || carrierNotifyEndpoint.trim().isEmpty()) {
            // 站内留痕必达
            logRow.setChannel(CcConstants.NOTIFY_CHANNEL_SITE);
            result = ExceptionNotifyTransport.SendResult.ok();
        } else {
            logRow.setChannel(CcConstants.NOTIFY_CHANNEL_WEBHOOK);
            logRow.setEndpoint(carrierNotifyEndpoint.trim());
            String payload = buildPayload(rs, batch, targetName, targetPhone);
            result = notifyTransport.send(carrierNotifyEndpoint.trim(), payload);
        }
        logRow.setSendStatus(result.isSuccess() ? CcConstants.NOTIFY_SUCCESS : CcConstants.NOTIFY_FAILED);
        logRow.setFailReason(result.isSuccess() ? null : result.getFailReason());
        notifyMapper.insert(logRow);

        opLogService.log(CcConstants.ENT_RESCHEDULE, rs.getId(), rs.getBatchId(),
                retryReason == null ? CcConstants.ACT_CARRIER_NOTIFY : CcConstants.ACT_CARRIER_NOTIFY_RETRY,
                String.format("批次 %s 改期单 %s 第%d次承运通知 -> %s（%s）：%s%s",
                        batch.getBatchNo(), rs.getRescheduleNo(), attempt, targetName,
                        logRow.getChannel(), logRow.getSendStatus(),
                        result.isSuccess() ? "" : "，失败原因=" + result.getFailReason()));
    }

    /** 手动重投上一次失败的承运通知（重试必须填原因，生成新一行 attemptNo 递增） */
    @Transactional
    public CarrierNotifyLog retryNotify(Long notifyLogId, String retryReason) {
        CarrierNotifyLog last = notifyMapper.selectById(notifyLogId);
        if (last == null) {
            throw new BusinessException("承运通知记录不存在: " + notifyLogId);
        }
        BatchReschedule rs = requireReschedule(last.getRescheduleId());
        TransportBatch batch = requireActiveBatch(last.getBatchId());
        sendCarrierNotification(rs, batch, retryReason.trim());
        return notifyMapper.selectList(new QueryWrapper<CarrierNotifyLog>()
                .eq("reschedule_id", rs.getId()).orderByDesc("attempt_no").last("LIMIT 1")).get(0);
    }

    private int nextAttemptNo(Long rescheduleId) {
        List<CarrierNotifyLog> rows = notifyMapper.selectList(new QueryWrapper<CarrierNotifyLog>()
                .eq("reschedule_id", rescheduleId).orderByDesc("attempt_no"));
        return rows.isEmpty() ? 1 : rows.get(0).getAttemptNo() + 1;
    }

    private String buildPayload(BatchReschedule rs, TransportBatch batch, String targetName, String targetPhone) {
        return String.format(
                "{\"rescheduleNo\":\"%s\",\"batchNo\":\"%s\",\"receiverOrg\":\"%s\","
                        + "\"newPlanShipTime\":\"%s\",\"newEstimatedArrival\":\"%s\","
                        + "\"committedArrivalUnchanged\":true,\"target\":\"%s\",\"targetPhone\":\"%s\",\"reason\":\"%s\"}",
                rs.getRescheduleNo(), batch.getBatchNo(), rs.getReceiverOrg(),
                rs.getNewPlanShipTime(), rs.getNewEstimatedArrival(),
                targetName, nz(targetPhone), escape(rs.getReason()));
    }

    // ---------------- 待办 ----------------

    private void createTodos(BatchReschedule rs, TransportBatch batch, ReceiveOrg org) {
        List<RescheduleImpact> ownerImpacts = listImpacts(rs.getId()).stream()
                .filter(i -> CcConstants.IMPACT_TODO_OWNER.equals(i.getImpactType()))
                .collect(Collectors.toList());
        for (RescheduleImpact imp : ownerImpacts) {
            RescheduleTodo todo = new RescheduleTodo();
            todo.setRescheduleId(rs.getId());
            todo.setBatchId(batch.getId());
            todo.setOwnerRole(imp.getOwnerName()); // 责任人岗位落在 ownerRole
            todo.setContent(imp.getDetail());
            todo.setStatus(CcConstants.TODO_PENDING);
            // 收货待办落到机构联系人（其余岗位按岗位待办，具体责任人在通知/影响明细中可查）
            if (CcConstants.ROLE_RECEIVER.equals(imp.getOwnerName())) {
                todo.setOwnerName(org.getContactName());
            }
            todoMapper.insert(todo);
        }
        // 承运待办落到具体驾驶员
        Vehicle vehicle = vehicleMapper.selectById(batch.getVehicleId());
        if (vehicle != null && vehicle.getDriverName() != null) {
            // 驾驶员姓名补充到承运岗位待办（无该待办时不单独建行，避免与影响清单口径不一致）
            List<RescheduleTodo> carrierTodos = todoMapper.selectList(new QueryWrapper<RescheduleTodo>()
                    .eq("reschedule_id", rs.getId())
                    .eq("owner_role", CcConstants.ROLE_CARRIER)
                    .eq("status", CcConstants.TODO_PENDING));
            for (RescheduleTodo t : carrierTodos) {
                if (t.getOwnerName() == null) {
                    t.setOwnerName(vehicle.getDriverName());
                    todoMapper.updateById(t);
                }
            }
        }
    }

    /** 待办确认（PENDING -> DONE，只写确认字段，事实保留） */
    @Transactional
    public RescheduleTodo confirmTodo(Long todoId, String note) {
        RescheduleTodo todo = todoMapper.selectById(todoId);
        if (todo == null) {
            throw new BusinessException("改期待办不存在: " + todoId);
        }
        if (CcConstants.TODO_DONE.equals(todo.getStatus())) {
            throw new BusinessException("待办已确认，不能重复确认");
        }
        todo.setStatus(CcConstants.TODO_DONE);
        todo.setConfirmNote(note.trim());
        todo.setConfirmBy(UserContext.currentId());
        todo.setConfirmName(UserContext.currentName());
        todo.setConfirmTime(LocalDateTime.now());
        todoMapper.updateById(todo);

        opLogService.log(CcConstants.ENT_RESCHEDULE, todo.getRescheduleId(), todo.getBatchId(),
                CcConstants.ACT_TODO_CONFIRM,
                String.format("改期待办#%d（%s）由 %s 确认完成：%s",
                        todo.getId(), todo.getOwnerRole(), UserContext.currentName(), note));
        return todo;
    }

    // ---------------- 查询 ----------------

    public BatchReschedule requireReschedule(Long id) {
        BatchReschedule rs = rescheduleMapper.selectById(id);
        if (rs == null) {
            throw new BusinessException("改期单不存在: " + id);
        }
        return rs;
    }

    public List<BatchReschedule> listByBatch(Long batchId) {
        return rescheduleMapper.selectList(new QueryWrapper<BatchReschedule>()
                .eq("batch_id", batchId).orderByAsc("id"));
    }

    public List<BatchReschedule> listPending() {
        return rescheduleMapper.selectList(new QueryWrapper<BatchReschedule>()
                .eq("status", CcConstants.RESCHEDULE_PENDING).orderByAsc("id"));
    }

    public List<RescheduleImpact> listImpacts(Long rescheduleId) {
        return impactMapper.selectList(new QueryWrapper<RescheduleImpact>()
                .eq("reschedule_id", rescheduleId).orderByAsc("id"));
    }

    public List<CarrierNotifyLog> listNotifications(Long rescheduleId) {
        return notifyMapper.selectList(new QueryWrapper<CarrierNotifyLog>()
                .eq("reschedule_id", rescheduleId).orderByAsc("attempt_no"));
    }

    public List<RescheduleTodo> listTodos(Long rescheduleId) {
        return todoMapper.selectList(new QueryWrapper<RescheduleTodo>()
                .eq("reschedule_id", rescheduleId).orderByAsc("id"));
    }

    public List<RescheduleTodo> listPendingTodos(String ownerRole) {
        QueryWrapper<RescheduleTodo> qw = new QueryWrapper<RescheduleTodo>()
                .eq("status", CcConstants.TODO_PENDING).orderByAsc("id");
        if (ownerRole != null && !ownerRole.trim().isEmpty()) {
            qw.eq("owner_role", ownerRole.trim());
        }
        return todoMapper.selectList(qw);
    }

    /** 改期单完整聚合（受影响明细 + 承运通知链 + 待办） */
    public Map<String, Object> detail(Long rescheduleId) {
        BatchReschedule rs = requireReschedule(rescheduleId);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("reschedule", rs);
        map.put("impacts", listImpacts(rescheduleId));
        map.put("notifications", listNotifications(rescheduleId));
        map.put("todos", listTodos(rescheduleId));
        return map;
    }

    // ---------------- 内部工具 ----------------

    private BatchReschedule findPending(Long batchId) {
        return rescheduleMapper.selectOne(new QueryWrapper<BatchReschedule>()
                .eq("batch_id", batchId)
                .eq("status", CcConstants.RESCHEDULE_PENDING)
                .last("LIMIT 1"));
    }

    /**
     * 推进卡口：该批次存在停留待审批（PENDING）的装车后改期单时，
     * 发运/撤回等推进动作一律拦截，先由审批岗同意或驳回（避免在途时刻未定即发运、撤回留下孤立待办单）。
     */
    public void requireNoPending(Long batchId) {
        BatchReschedule pending = findPending(batchId);
        if (pending != null) {
            throw new BusinessException(String.format(
                    "批次的装车后改期单 %s 仍停留待审批（PENDING），请先审批同意或驳回后再推进",
                    pending.getRescheduleNo()));
        }
    }

    private TransportBatch requireActiveBatch(Long batchId) {
        TransportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BusinessException("运输批次不存在: " + batchId);
        }
        if (CcConstants.BATCH_REVOKED.equals(batch.getStatus())) {
            throw new BusinessException("批次已撤回作废，不能改期/装车: " + batch.getBatchNo());
        }
        return batch;
    }

    private String joinIds(List<Long> ids) {
        return ids == null || ids.isEmpty() ? null
                : ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private List<Long> splitIds(String snap) {
        List<Long> ids = new ArrayList<>();
        if (snap == null || snap.trim().isEmpty()) {
            return ids;
        }
        for (String part : snap.split(",")) {
            String p = part.trim();
            if (!p.isEmpty()) {
                ids.add(Long.valueOf(p));
            }
        }
        return ids;
    }

    private String fmt(LocalDateTime t) {
        return t == null ? "" : t.format(FMT);
    }

    private String nz(String s) {
        return s == null || s.trim().isEmpty() ? "-" : s;
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
