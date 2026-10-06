package com.evops.coldchain;

import com.evops.coldchain.dto.BatchRescheduleRequest;
import com.evops.coldchain.dto.DispatchRequest;
import com.evops.coldchain.dto.DrugCreateRequest;
import com.evops.coldchain.dto.HolidayRuleRequest;
import com.evops.coldchain.dto.OrderCreateRequest;
import com.evops.coldchain.dto.ProbeBindRequest;
import com.evops.coldchain.dto.ProbeCreateRequest;
import com.evops.coldchain.dto.ReceiveOrgCreateRequest;
import com.evops.coldchain.dto.RouteCreateRequest;
import com.evops.coldchain.dto.RouteDraftRequest;
import com.evops.coldchain.dto.SegmentDraftRequest;
import com.evops.coldchain.dto.SignRequest;
import com.evops.coldchain.dto.VehicleCreateRequest;
import com.evops.coldchain.dto.WindowRuleDraftRequest;
import com.evops.coldchain.entity.BatchReschedule;
import com.evops.coldchain.entity.CarrierNotifyLog;
import com.evops.coldchain.entity.DeliveryWindowRule;
import com.evops.coldchain.entity.DeliveryWindowSlot;
import com.evops.coldchain.entity.HolidayRule;
import com.evops.coldchain.entity.ReceiveOrg;
import com.evops.coldchain.entity.RescheduleImpact;
import com.evops.coldchain.entity.RescheduleTodo;
import com.evops.coldchain.entity.Route;
import com.evops.coldchain.entity.ShipmentOrder;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.HolidayRuleMapper;
import com.evops.coldchain.service.BatchRescheduleService;
import com.evops.coldchain.service.DeliveryWindowService;
import com.evops.coldchain.service.DrugService;
import com.evops.coldchain.service.LedgerService;
import com.evops.coldchain.service.OrderService;
import com.evops.coldchain.service.ProbeService;
import com.evops.coldchain.service.RouteService;
import com.evops.coldchain.service.TransportBatchService;
import com.evops.coldchain.service.VehicleService;
import com.evops.coldchain.support.TestSubjects;
import com.evops.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冷链运输 V10 验收：送达时窗管理。
 *
 * 覆盖：
 * 1) 工作日区间版本化：草稿/发布/热替换只出新版本，旧版本区间行保留；
 * 2) 派车按"计划发运+运输时长"经工作日区间与节假日解析并固化承诺/预计到达；
 * 3) 未装车改期直接生效：重算预计到达、同步发货单计划发运，承诺时间不改写；
 * 4) 节假日/临时闭馆独立保存；参与已生效结论后冻结，不能作废/改动旧结论；
 * 5) 装车（及在途）后改期必须审批：PENDING 不改任何字段，驳回不变，同意后应用并
 *    列出受影响温控区间（路线分段）/承运通知/待办责任人，生成通知与待办；
 * 6) 通知重试逐次留痕、待办确认只进不退、签收（送达时窗完成）后禁止改期；
 * 7) 权限：401 未认证 / 403 无权限 / 200 正常；台账聚合改期历史可追查。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DeliveryWindowRescheduleIntegrationTest {

    private static final String BASE = "/api/cold-chain";
    // 2026-10-08 周四（10-09 周五 / 10-10 周六 / 10-11 周日 / 10-12 周一）
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 8, 15, 0);

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate http;

    @Autowired private DeliveryWindowService windowService;
    @Autowired private BatchRescheduleService rescheduleService;
    @Autowired private TransportBatchService batchService;
    @Autowired private OrderService orderService;
    @Autowired private DrugService drugService;
    @Autowired private VehicleService vehicleService;
    @Autowired private ProbeService probeService;
    @Autowired private RouteService routeService;
    @Autowired private LedgerService ledgerService;
    @Autowired private TestSubjects testSubjects;
    @Autowired private HolidayRuleMapper holidayMapper;

    // ====================================================================
    // 1) 工作日区间版本化 + ETA 解析 + 未装车改期承诺不变
    // ====================================================================
    @Test
    void weekdaySlots_versionedEta_autoRescheduleKeepsCommitted() {
        long nano = System.nanoTime();
        Fixture f = new Fixture(nano, "V10验收医院A-" + nano, 60);
        // 工作日区间：周一~周五 09:00-17:00
        publishV1(f.org.getId(), Arrays.asList(slot(1, "09:00", "17:00"),
                slot(2, "09:00", "17:00"), slot(3, "09:00", "17:00"),
                slot(4, "09:00", "17:00"), slot(5, "09:00", "17:00")));

        // 同工作日区间重叠必须拒绝
        WindowRuleDraftRequest bad = draft(Arrays.asList(slot(1, "09:00", "12:00"),
                slot(1, "11:00", "15:00")));
        BusinessException overlap = assertThrows(BusinessException.class,
                () -> windowService.saveDraft(f.org.getId(), bad));
        assertTrue(overlap.getMessage().contains("重叠"));

        // 派车：周四 15:00 + 60 分钟 = 周四 16:00，落在区间内 -> 承诺=预计=周四 16:00
        TransportBatch batch = f.dispatch(T0, null);
        assertEquals(T0.plusMinutes(60), batch.getCommittedArrivalTime());
        assertEquals(T0.plusMinutes(60), batch.getEstimatedArrivalTime());
        assertEquals(1, batch.getWindowRuleVersionNo());

        // 未装车改期：新计划 周日 12:00（+60 分钟）-> 周日无区间 -> 周一 09:00；直接生效
        BatchReschedule auto = reschedule(batch.getId(),
                LocalDateTime.of(2026, 10, 11, 12, 0), "收货方周日不收货，顺延发运");
        assertEquals(CcConstants.RESCHEDULE_AUTO, auto.getStatus());
        assertEquals(LocalDateTime.of(2026, 10, 12, 9, 0), auto.getNewEstimatedArrival());

        batch = batchService.requireBatch(batch.getId());
        // 承诺时间原样不动，预计到达被重算
        assertEquals(T0.plusMinutes(60), batch.getCommittedArrivalTime(), "承诺到达时间不可被改期改写");
        assertEquals(LocalDateTime.of(2026, 10, 12, 9, 0), batch.getEstimatedArrivalTime());
        // 未发运：发货单计划发运同步更新
        ShipmentOrder order = orderService.requireOrder(batch.getOrderId());
        assertEquals(LocalDateTime.of(2026, 10, 11, 12, 0), order.getPlanShipTime());

        // 自动生效改期同样列出受影响明细、投递承运通知并生成待办责任人
        List<RescheduleImpact> autoImpacts = rescheduleService.listImpacts(auto.getId());
        assertTrue(autoImpacts.stream().anyMatch(i -> CcConstants.IMPACT_TEMP_ZONE.equals(i.getImpactType())));
        List<CarrierNotifyLog> autoNotifies = rescheduleService.listNotifications(auto.getId());
        assertEquals(1, autoNotifies.size());
        assertEquals(CcConstants.NOTIFY_SUCCESS, autoNotifies.get(0).getSendStatus());
        List<RescheduleTodo> autoTodos = rescheduleService.listTodos(auto.getId());
        assertEquals(3, autoTodos.size());

        // 规则调整只能出新版本：发布 V2（08:30~16:30），V1 置 SUPERSEDED 且区间行原样保留
        DeliveryWindowRule v2 = publishV1(f.org.getId(), Arrays.asList(slot(1, "08:30", "16:30"),
                slot(2, "08:30", "16:30"), slot(3, "08:30", "16:30"),
                slot(4, "08:30", "16:30"), slot(5, "08:30", "16:30")));
        assertEquals(2, v2.getVersionNo());
        DeliveryWindowRule v1 = windowService.listRules(f.org.getId()).stream()
                .filter(r -> r.getVersionNo() == 1).findFirst().orElseThrow(AssertionError::new);
        assertEquals(CcConstants.SUPERSEDED, v1.getStatus());
        List<DeliveryWindowSlot> v1Slots = windowService.listSlots(v1.getId());
        assertEquals(5, v1Slots.size(), "旧版本工作日区间行保留不改动");
        assertEquals(540, v1Slots.get(0).getStartMinute()); // 09:00
        // 老批次仍锚定 V1，承诺不变
        TransportBatch oldBatch = batchService.requireBatch(batch.getId());
        assertEquals(v1.getId(), oldBatch.getWindowRuleId());
        assertEquals(T0.plusMinutes(60), oldBatch.getCommittedArrivalTime());
        // 新派车批次按 V2 解析（新发货单/车辆，复用同一收货机构）
        Fixture f2 = new Fixture(nano + 7919, f.org);
        TransportBatch batch2 = f2.dispatch(T0, null);
        assertEquals(2, batch2.getWindowRuleVersionNo());
        // T0 周四 15:00 + 60 分钟 = 16:00，落在 V2（08:30~16:30）区间内
        assertEquals(T0.plusMinutes(60), batch2.getCommittedArrivalTime());
    }

    // ====================================================================
    // 2) 节假日旧结论冻结：参与解析后不可作废；新增规则才能调整
    // ====================================================================
    @Test
    void holidayAndClosure_independentSave_frozenAfterApplied() {
        long nano = System.nanoTime();
        Fixture f = new Fixture(nano, "V10验收医院B-" + nano, 60);
        publishV1(f.org.getId(), Arrays.asList(slot(1, "09:00", "17:00"),
                slot(2, "09:00", "17:00"), slot(3, "09:00", "17:00"),
                slot(4, "09:00", "17:00"), slot(5, "09:00", "17:00")));

        // 节假日（周五整日闭馆）在派车前登记；本次承诺走周四，不触及该节假日 -> 未冻结
        HolidayRule fridayHoliday = addHoliday(f.org.getId(), CcConstants.HOLIDAY_HOLIDAY,
                LocalDate.of(2026, 10, 9), null, null, "国庆调休");
        TransportBatch batch = f.dispatch(T0, null);
        fridayHoliday = holidayMapper.selectById(fridayHoliday.getId());
        assertFalse(fridayHoliday.getFrozen(), "未参与结论的节假日本次不应冻结");

        // 未装车改期到周五：物理到达周五被节假日跳过 -> 周一 09:00，节假日参与结论
        BatchReschedule auto = reschedule(batch.getId(),
                LocalDateTime.of(2026, 10, 9, 14, 0), "高速管制推迟一天");
        assertEquals(LocalDateTime.of(2026, 10, 12, 9, 0), auto.getNewEstimatedArrival());
        fridayHoliday = holidayMapper.selectById(fridayHoliday.getId());
        assertTrue(fridayHoliday.getFrozen(), "参与已生效改期结论的节假日必须冻结");
        assertEquals(batch.getId(), fridayHoliday.getFrozenBatchId());
        assertEquals(String.valueOf(fridayHoliday.getId()), auto.getHolidayIdsSnap());

        // 旧结论冻结：不能作废，也不能通过"作废再新增同一天"篡改
        final Long frozenHolidayId = fridayHoliday.getId();
        BusinessException frozen = assertThrows(BusinessException.class,
                () -> windowService.voidHoliday(frozenHolidayId, "想改掉旧结论"));
        assertTrue(frozen.getMessage().contains("冻结"));

        // 临时闭馆独立保存：未参与任何结论的闭馆可以作废（只置状态不删行）
        HolidayRule closure = addClosure(f.org.getId(),
                LocalDateTime.of(2026, 11, 1, 0, 0),
                LocalDateTime.of(2026, 11, 2, 0, 0), "库房盘点临时闭馆");
        assertEquals(CcConstants.HOLIDAY_ACTIVE, closure.getStatus());
        windowService.voidHoliday(closure.getId(), "盘点取消");
        closure = holidayMapper.selectById(closure.getId());
        assertEquals(CcConstants.HOLIDAY_VOID, closure.getStatus());
        assertNotNull(closure.getVoidTime());

        // 单日临时闭馆（CLOSED+holidayDate）同样独立可维护
        HolidayRule oneDayClosed = addHoliday(f.org.getId(), CcConstants.HOLIDAY_CLOSED,
                LocalDate.of(2026, 11, 3), null, null, "院庆半日闭馆");
        assertEquals(CcConstants.HOLIDAY_CLOSED, oneDayClosed.getRuleKind());

        // 非法配置：节假日缺日期 / 闭馆区间终点早于起点
        assertThrows(BusinessException.class, () -> addHoliday(f.org.getId(),
                CcConstants.HOLIDAY_HOLIDAY, null, null, null, "无日期节假日"));
        assertThrows(BusinessException.class, () -> addClosure(f.org.getId(),
                LocalDateTime.of(2026, 11, 5, 10, 0),
                LocalDateTime.of(2026, 11, 5, 9, 0), "反向闭馆"));
    }

    // ====================================================================
    // 3) 装车后改期必须审批：影响清单/承运通知/待办责任人，驳回不变、同意生效
    // ====================================================================
    @Test
    void loadingAndShippedReschedule_requiresApproval_impactsNotifyTodos() {
        long nano = System.nanoTime();
        Fixture f = new Fixture(nano, "V10验收医院C-" + nano, 120);
        publishV1(f.org.getId(), Arrays.asList(slot(1, "08:00", "18:00"),
                slot(2, "08:00", "18:00"), slot(3, "08:00", "18:00"),
                slot(4, "08:00", "18:00"), slot(5, "08:00", "18:00")));

        // 配一条 100km 路线（秋季版，单一温控区间，责任人=鲁库管），发运时逐段快照
        Route route = createAutumnRoute(nano);
        TransportBatch batch = f.dispatch(T0, route.getId());
        LocalDateTime committed = batch.getCommittedArrivalTime();

        // 装车登记
        TransportBatch loading = rescheduleService.markLoading(batch.getId());
        assertEquals(CcConstants.BATCH_LOADING, loading.getStatus());
        assertNotNull(loading.getLoadingTime());
        // 重复装车拒绝
        final Long loadedBatchId = batch.getId();
        assertThrows(BusinessException.class, () -> rescheduleService.markLoading(loadedBatchId));

        // 装车后改期 -> PENDING，批次/发货单暂不变
        BatchReschedule pending = reschedule(batch.getId(),
                LocalDateTime.of(2026, 10, 9, 9, 0), "车辆备货延误");
        assertEquals(CcConstants.RESCHEDULE_PENDING, pending.getStatus());
        assertTrue(pending.getRequireApproval());
        batch = batchService.requireBatch(batch.getId());
        assertEquals(committed, batch.getEstimatedArrivalTime(), "待审批期间预计到达不得变化");
        assertEquals(committed, batch.getCommittedArrivalTime());
        // 审批前已列出受影响明细（此时未发运，按全程档案温区；尚无通知/待办）
        List<RescheduleImpact> impacts = rescheduleService.listImpacts(pending.getId());
        assertTrue(impacts.stream().anyMatch(i -> CcConstants.IMPACT_TEMP_ZONE.equals(i.getImpactType())));
        assertTrue(impacts.stream().anyMatch(i -> CcConstants.IMPACT_CARRIER_NOTICE.equals(i.getImpactType())));
        assertTrue(impacts.stream().anyMatch(i -> CcConstants.IMPACT_TODO_OWNER.equals(i.getImpactType())));
        assertEquals(0, rescheduleService.listNotifications(pending.getId()).size());
        assertEquals(0, rescheduleService.listTodos(pending.getId()).size());

        // 待审批改期未决，发运/签收等推进一律拦截
        final Long pendingBatchId = batch.getId();
        BusinessException shipBlocked = assertThrows(BusinessException.class,
                () -> batchService.ship(pendingBatchId));
        assertTrue(shipBlocked.getMessage().contains("停留待审批"));

        // 驳回：任何计划字段不变，可重新申请
        reviewAs("qa", () -> rescheduleService.reject(pending.getId(), "先按原计划执行"));
        BatchReschedule rejected = rescheduleService.requireReschedule(pending.getId());
        assertEquals(CcConstants.RESCHEDULE_REJECTED, rejected.getStatus());
        batch = batchService.requireBatch(batch.getId());
        assertEquals(committed, batch.getEstimatedArrivalTime());

        // 绑定探头后发运（在途），再改期 -> 仍必须审批；此时温控区间按路线分段快照列出
        Long probeId = bindValidProbe(f.vehicleId, "req-v10-" + nano, "SN-V10-" + nano);
        batch = batchService.ship(batch.getId());
        assertEquals(CcConstants.BATCH_SHIPPED, batch.getStatus());
        assertNotNull(probeId);

        BatchReschedule pending2 = reschedule(batch.getId(),
                LocalDateTime.of(2026, 10, 9, 9, 0), "备货延误确认改期");
        assertEquals(CcConstants.RESCHEDULE_PENDING, pending2.getStatus());
        List<RescheduleImpact> impacts2 = rescheduleService.listImpacts(pending2.getId());
        List<RescheduleImpact> zones = impacts2.stream()
                .filter(i -> CcConstants.IMPACT_TEMP_ZONE.equals(i.getImpactType()))
                .collect(Collectors.toList());
        assertEquals(1, zones.size(), "路线单一分段，应列出 1 个受影响温控区间");
        assertEquals(1, zones.get(0).getSegmentSeq());
        assertEquals("鲁库管", zones.get(0).getOwnerName());
        RescheduleImpact carrier = impacts2.stream()
                .filter(i -> CcConstants.IMPACT_CARRIER_NOTICE.equals(i.getImpactType())).findFirst()
                .orElseThrow(AssertionError::new);
        assertEquals("V10验收物流-" + nano, carrier.getOwnerName());
        List<String> todoRoles = impacts2.stream()
                .filter(i -> CcConstants.IMPACT_TODO_OWNER.equals(i.getImpactType()))
                .map(RescheduleImpact::getOwnerName).collect(Collectors.toList());
        assertTrue(todoRoles.containsAll(Arrays.asList(
                CcConstants.ROLE_DISPATCHER, CcConstants.ROLE_CARRIER, CcConstants.ROLE_RECEIVER)));

        // 待审批期间重复改期拒绝
        final Long shippedBatchId = batch.getId();
        assertThrows(BusinessException.class, () -> reschedule(shippedBatchId,
                LocalDateTime.of(2026, 10, 10, 9, 0), "又改"));

        // QA 同意 -> 预计到达更新（承诺不动）、通知投递、待办生成
        LocalDateTime newEta = pending2.getNewEstimatedArrival();
        reviewAs("qa", () -> rescheduleService.approve(pending2.getId(), "同意按新时刻执行，通知承运与收货"));
        batch = batchService.requireBatch(batch.getId());
        assertEquals(committed, batch.getCommittedArrivalTime(), "承诺到达始终不改写");
        assertEquals(newEta, batch.getEstimatedArrivalTime());
        BatchReschedule approved = rescheduleService.requireReschedule(pending2.getId());
        assertEquals(CcConstants.RESCHEDULE_APPROVED, approved.getStatus());
        assertNotNull(approved.getAppliedTime());

        List<CarrierNotifyLog> notifies = rescheduleService.listNotifications(pending2.getId());
        assertEquals(1, notifies.size());
        assertEquals(CcConstants.NOTIFY_SUCCESS, notifies.get(0).getSendStatus());
        assertEquals(CcConstants.NOTIFY_CHANNEL_SITE, notifies.get(0).getChannel());
        assertEquals(1, notifies.get(0).getAttemptNo());
        List<RescheduleTodo> todos = rescheduleService.listTodos(pending2.getId());
        assertEquals(3, todos.size());
        assertTrue(todos.stream().allMatch(t -> CcConstants.TODO_PENDING.equals(t.getStatus())));
        assertTrue(todos.stream().anyMatch(t -> CcConstants.ROLE_CARRIER.equals(t.getOwnerRole())
                && "冷司机".equals(t.getOwnerName())));

        // 通知重试：独立新增一行，attemptNo 递增，失败/成功行都保留
        CarrierNotifyLog retry = rescheduleService.retryNotify(notifies.get(0).getId(), "承运方要求再次短信确认");
        assertEquals(2, retry.getAttemptNo());
        assertEquals(CcConstants.NOTIFY_SUCCESS, retry.getSendStatus());
        assertEquals(2, rescheduleService.listNotifications(pending2.getId()).size());

        // 待办确认只进不退
        RescheduleTodo dispatcherTodo = todos.stream()
                .filter(t -> CcConstants.ROLE_DISPATCHER.equals(t.getOwnerRole())).findFirst()
                .orElseThrow(AssertionError::new);
        reviewAs("dispatcher", () -> rescheduleService.confirmTodo(dispatcherTodo.getId(), "已重排发车计划"));
        assertThrows(BusinessException.class, () -> reviewAs("dispatcher",
                () -> rescheduleService.confirmTodo(dispatcherTodo.getId(), "重复确认")));

        // 改期历史可追查：AUTO 无（本批次均审批）、驳回 + 同意 两张 + 装车/通知/待办流水
        List<BatchReschedule> history = rescheduleService.listByBatch(batch.getId());
        assertEquals(2, history.size());
        Map<String, Object> ledger = ledgerService.batchLedger(batch.getId());
        @SuppressWarnings("unchecked")
        Map<String, Object> dw = (Map<String, Object>) ledger.get("deliveryWindow");
        assertEquals(committed, dw.get("committedArrivalTime"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rsList = (List<Map<String, Object>>) dw.get("reschedules");
        assertEquals(2, rsList.size());

        // 送达时窗完成（签收闭环）后禁止再改期
        SignRequest sign = new SignRequest();
        sign.setBatchId(batch.getId());
        sign.setSignBy("王收货");
        sign.setReceivedApprovalNo(batch.getApprovalNoSnap());
        sign.setReceivedPackageSpec(batch.getPackageSpecSnap());
        sign.setConfirmQty(batch.getShipQty());
        batchService.sign(sign);
        final Long signedBatchId = batch.getId();
        BusinessException done = assertThrows(BusinessException.class, () -> reschedule(signedBatchId,
                LocalDateTime.of(2026, 10, 12, 9, 0), "签收后改期"));
        assertTrue(done.getMessage().contains("送达时窗已完成"));
    }

    // ====================================================================
    // 4) 权限：401 未认证 / 403 无权限 / 200 正常
    // ====================================================================
    @Test
    void permissionCheck_http401And403And200() {
        long nano = System.nanoTime();
        Fixture f = new Fixture(nano, "V10验收医院D-" + nano, 60);
        publishV1(f.org.getId(), Arrays.asList(slot(1, "09:00", "17:00")));
        TransportBatch batch = f.dispatch(T0, null);

        String loadingUrl = url("/batches/" + batch.getId() + "/loading");
        // 401 未认证
        ResponseEntity<String> noAuth = http.withBasicAuth("nobody", "x")
                .postForEntity(loadingUrl, jsonEntity("{}"), String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, noAuth.getStatusCode());
        // 403：receiver 无装车/改期权限
        ResponseEntity<String> forbidden = http.withBasicAuth("receiver", "receiver123")
                .postForEntity(loadingUrl, jsonEntity("{}"), String.class);
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());
        // 200：dispatcher 可装车
        ResponseEntity<String> ok = http.withBasicAuth("dispatcher", "dispatcher123")
                .postForEntity(loadingUrl, jsonEntity("{}"), String.class);
        assertEquals(HttpStatus.OK, ok.getStatusCode());

        // 时窗规则维护：dispatcher 无 coldchain:window:manage -> 403；admin 200
        Map<String, Object> orgBody = new LinkedHashMap<>();
        orgBody.put("orgName", "V10无权医院-" + nano);
        orgBody.put("transportMinutes", 30);
        ResponseEntity<String> manage403 = http.withBasicAuth("dispatcher", "dispatcher123")
                .postForEntity(url("/delivery-windows/orgs/create"), jsonEntity(orgBody), String.class);
        assertEquals(HttpStatus.FORBIDDEN, manage403.getStatusCode());
        ResponseEntity<String> manage200 = http.withBasicAuth("admin", "admin123")
                .postForEntity(url("/delivery-windows/orgs/create"), jsonEntity(orgBody), String.class);
        assertEquals(HttpStatus.OK, manage200.getStatusCode());

        // 装车后改期：carrier 可申请但无权审批（审批路径 403）
        Map<String, Object> rsBody = new LinkedHashMap<>();
        rsBody.put("batchId", batch.getId());
        rsBody.put("newPlanShipTime", LocalDateTime.of(2026, 10, 9, 9, 0).toString());
        rsBody.put("reason", "HTTP 权限验收改期");
        ResponseEntity<String> applyOk = http.withBasicAuth("carrier", "carrier123")
                .postForEntity(url("/reschedules"), jsonEntity(rsBody), String.class);
        assertEquals(HttpStatus.OK, applyOk.getStatusCode());
        BatchReschedule pending = rescheduleService.listByBatch(batch.getId()).stream()
                .filter(r -> CcConstants.RESCHEDULE_PENDING.equals(r.getStatus())).findFirst()
                .orElseThrow(AssertionError::new);
        Map<String, Object> reviewBody = new LinkedHashMap<>();
        reviewBody.put("reviewNote", "越权审批应被拒绝");
        ResponseEntity<String> review403 = http.withBasicAuth("carrier", "carrier123")
                .postForEntity(url("/reschedules/" + pending.getId() + "/approve"),
                        jsonEntity(reviewBody), String.class);
        assertEquals(HttpStatus.FORBIDDEN, review403.getStatusCode());
    }

    // ====================================================================
    // 5) webhook 失败留痕（不回滚改期）+ 重试成功；通知链逐次只追加
    // ====================================================================
    @Test
    void carrierWebhookFail_isRecorded_retrySucceeds() {
        long nano = System.nanoTime();
        Fixture f = new Fixture(nano, "V10验收医院E-" + nano, 60);
        publishV1(f.org.getId(), Arrays.asList(slot(1, "09:00", "17:00"),
                slot(2, "09:00", "17:00"), slot(3, "09:00", "17:00"),
                slot(4, "09:00", "17:00"), slot(5, "09:00", "17:00")));
        TransportBatch batch = f.dispatch(T0, null);

        // 指向一个必然连接失败的 webhook 地址；未装车改期直接生效，通知失败不回滚业务
        org.springframework.test.util.ReflectionTestUtils.setField(
                rescheduleService, "carrierNotifyEndpoint", "http://127.0.0.1:1/v10-carrier-notify");
        BatchReschedule auto;
        try {
            auto = reschedule(batch.getId(), LocalDateTime.of(2026, 10, 9, 9, 0), "webhook 失败留痕验收");
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(
                    rescheduleService, "carrierNotifyEndpoint", "");
        }
        assertEquals(CcConstants.RESCHEDULE_AUTO, auto.getStatus());
        List<CarrierNotifyLog> failed = rescheduleService.listNotifications(auto.getId());
        assertEquals(1, failed.size());
        assertEquals(CcConstants.NOTIFY_FAILED, failed.get(0).getSendStatus());
        assertEquals(CcConstants.NOTIFY_CHANNEL_WEBHOOK, failed.get(0).getChannel());
        assertNotNull(failed.get(0).getFailReason());
        // 改期业务本身已生效（预计到达已重算，承诺不变）
        assertEquals(LocalDateTime.of(2026, 10, 9, 10, 0),
                batchService.requireBatch(batch.getId()).getEstimatedArrivalTime());
        assertEquals(T0.plusMinutes(60), batchService.requireBatch(batch.getId()).getCommittedArrivalTime());

        // 恢复站内通道后手动重试：成功行新增、失败行保留
        CarrierNotifyLog retry = rescheduleService.retryNotify(failed.get(0).getId(), "webhook 恢复，重发一次");
        assertEquals(CcConstants.NOTIFY_SUCCESS, retry.getSendStatus());
        assertEquals(2, retry.getAttemptNo());
        List<CarrierNotifyLog> all = rescheduleService.listNotifications(auto.getId());
        assertEquals(2, all.size());
        assertEquals(CcConstants.NOTIFY_FAILED, all.get(0).getSendStatus());
        assertEquals(CcConstants.NOTIFY_SUCCESS, all.get(1).getSendStatus());
    }

    // ====================================================================
    // 辅助
    // ====================================================================

    /** 测试夹具：药品/车辆/收货机构 + 派车（不发运） */
    private class Fixture {
        final ReceiveOrg org;
        final Long vehicleId;
        ShipmentOrder order;

        /** 新建收货机构与一套药品/发货单/车辆 */
        Fixture(long nano, String orgName, int transportMinutes) {
            this(nano, createReceiveOrg(nano, orgName, transportMinutes));
        }

        /** 复用既有收货机构（验证新版本只影响此后新派车批次） */
        Fixture(long nano, ReceiveOrg existingOrg) {
            this.org = existingOrg;

            DrugCreateRequest dc = new DrugCreateRequest();
            dc.setDrugName("V10验收药品-" + nano);
            dc.setApprovalNo("国药准字V10-" + Math.abs(nano % 1000000));
            dc.setTempMin(new BigDecimal("2.00"));
            dc.setTempMax(new BigDecimal("8.00"));
            dc.setPackageSpec("1支/盒");
            Long drugId = drugService.create(dc).getId();

            OrderCreateRequest orderReq = new OrderCreateRequest();
            orderReq.setDrugId(drugId);
            orderReq.setReceiverOrg(existingOrg.getOrgName());
            orderReq.setShipQty(20);
            orderReq.setPlanShipTime(T0);
            this.order = orderService.create(orderReq);

            VehicleCreateRequest vc = new VehicleCreateRequest();
            vc.setPlateNo("测10·冷" + Math.abs(nano % 1000000));
            vc.setCarrierName("V10验收物流-" + nano);
            vc.setDriverName("冷司机");
            vc.setDriverPhone("13900001010");
            this.vehicleId = vehicleService.create(vc).getId();
        }

        TransportBatch dispatch(LocalDateTime planShipTime, Long routeId) {
            ShipmentOrder fresh = orderService.requireOrder(order.getId());
            fresh.setPlanShipTime(planShipTime);
            DispatchRequest dr = new DispatchRequest();
            dr.setOrderId(fresh.getId());
            dr.setVehicleId(vehicleId);
            dr.setRouteId(routeId);
            return batchService.dispatch(dr);
        }
    }

    private ReceiveOrg createReceiveOrg(long nano, String orgName, int transportMinutes) {
        ReceiveOrgCreateRequest oc = new ReceiveOrgCreateRequest();
        oc.setOrgName(orgName);
        oc.setContactName("库管主任");
        oc.setContactPhone("0571-88888888");
        oc.setTransportMinutes(transportMinutes);
        return windowService.createOrg(oc);
    }

    private WindowRuleDraftRequest.SlotDraft slot(int weekday, String start, String end) {
        WindowRuleDraftRequest.SlotDraft s = new WindowRuleDraftRequest.SlotDraft();
        s.setWeekday(weekday);
        s.setStartTime(start);
        s.setEndTime(end);
        return s;
    }

    private WindowRuleDraftRequest draft(List<WindowRuleDraftRequest.SlotDraft> slots) {
        WindowRuleDraftRequest req = new WindowRuleDraftRequest();
        req.setChangeReason("测试版本调整");
        req.setSlots(slots);
        return req;
    }

    /** 每次调用保存草稿并发布；首版 V1，之后为 V2/V3...（同一机构只保留一个草稿） */
    private DeliveryWindowRule publishV1(Long orgId, List<WindowRuleDraftRequest.SlotDraft> slots) {
        windowService.saveDraft(orgId, draft(slots));
        return windowService.publish(orgId);
    }

    private HolidayRule addHoliday(Long orgId, String kind, LocalDate date,
                                   LocalDateTime from, LocalDateTime to, String name) {
        HolidayRuleRequest req = new HolidayRuleRequest();
        req.setRuleKind(kind);
        req.setHolidayDate(date);
        req.setClosedFrom(from);
        req.setClosedTo(to);
        req.setHolidayName(name);
        return windowService.addHoliday(orgId, req);
    }

    private HolidayRule addClosure(Long orgId, LocalDateTime from, LocalDateTime to, String name) {
        return addHoliday(orgId, CcConstants.HOLIDAY_CLOSED, null, from, to, name);
    }

    private Route createAutumnRoute(long nano) {
        RouteCreateRequest rc = new RouteCreateRequest();
        rc.setRouteName("V10验收路线-" + nano);
        rc.setOrigin("中心仓");
        rc.setDestination("验收医院");
        rc.setTotalKm(new BigDecimal("100.00"));
        Route route = routeService.createRoute(rc);

        SegmentDraftRequest seg = new SegmentDraftRequest();
        seg.setFromKm(new BigDecimal("0.00"));
        seg.setToKm(new BigDecimal("100.00"));
        seg.setTempMin(new BigDecimal("2.00"));
        seg.setTempMax(new BigDecimal("8.00"));
        seg.setBufferMinutes(30);
        seg.setOwnerName("鲁库管");
        List<SegmentDraftRequest> segs = new ArrayList<>();
        segs.add(seg);

        RouteDraftRequest rd = new RouteDraftRequest();
        rd.setSeason(CcConstants.SEASON_AUTUMN);
        rd.setSegments(segs);
        routeService.saveDraft(route.getId(), rd);
        com.evops.coldchain.entity.RouteVersion rv = routeService.listVersions(route.getId()).get(0);
        routeService.publish(rv.getId());
        return route;
    }

    private Long bindValidProbe(Long vehicleId, String requestId, String serialNo) {
        ProbeCreateRequest pc = new ProbeCreateRequest();
        pc.setSerialNo(serialNo);
        pc.setRangeMin(new BigDecimal("-40.00"));
        pc.setRangeMax(new BigDecimal("60.00"));
        pc.setCalibrateOrg("华东计量院");
        pc.setValidUntil(LocalDateTime.now().plusYears(1));
        pc.setCertNo("CERT-" + serialNo);
        pc.setCalibrateDate(LocalDateTime.now().minusDays(1));
        pc.setCertFileName(serialNo + ".txt");
        pc.setCertFileContent("V10校准证书-" + serialNo);
        Long probeId = probeService.createProbe(pc).getId();
        ProbeBindRequest pb = new ProbeBindRequest();
        pb.setRequestId(requestId);
        pb.setVehicleId(vehicleId);
        pb.setProbeId(probeId);
        probeService.bindIdempotent(pb);
        return probeId;
    }

    private BatchReschedule reschedule(Long batchId, LocalDateTime newPlanShipTime, String reason) {
        BatchRescheduleRequest req = new BatchRescheduleRequest();
        req.setBatchId(batchId);
        req.setNewPlanShipTime(newPlanShipTime);
        req.setReason(reason);
        return rescheduleService.apply(req);
    }

    private void reviewAs(String username, Runnable action) {
        long id;
        switch (username) {
            case "qa": id = 6L; break;
            case "dispatcher": id = 2L; break;
            case "carrier": id = 3L; break;
            default: id = 5L;
        }
        testSubjects.runAs(id, username, action);
    }

    private String url(String path) {
        return "http://localhost:" + port + BASE + path;
    }

    private HttpEntity<Map<String, Object>> jsonEntity(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private HttpEntity<String> jsonEntity(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }
}
