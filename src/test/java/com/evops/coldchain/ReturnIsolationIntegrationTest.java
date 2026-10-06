package com.evops.coldchain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.dto.DispatchRequest;
import com.evops.coldchain.dto.DrugCreateRequest;
import com.evops.coldchain.dto.OrderCreateRequest;
import com.evops.coldchain.dto.ProbeBindRequest;
import com.evops.coldchain.dto.ProbeCreateRequest;
import com.evops.coldchain.dto.VehicleCreateRequest;
import com.evops.coldchain.entity.OpLog;
import com.evops.coldchain.entity.ReturnBatch;
import com.evops.coldchain.entity.ReturnDecision;
import com.evops.coldchain.entity.ReturnInspection;
import com.evops.coldchain.entity.ReturnMovement;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.OpLogMapper;
import com.evops.coldchain.mapper.ReturnBatchMapper;
import com.evops.coldchain.mapper.ReturnDecisionMapper;
import com.evops.coldchain.mapper.ReturnInspectionMapper;
import com.evops.coldchain.mapper.ReturnMovementMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.coldchain.service.DrugService;
import com.evops.coldchain.service.OrderService;
import com.evops.coldchain.service.ProbeService;
import com.evops.coldchain.service.TransportBatchService;
import com.evops.coldchain.service.VehicleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冷链药品退货（V11）验收测试。
 *
 * 覆盖：收货拒签/温度超限/包装破损分别生成退货原因；登记快照固化、原销售批次只读关联；
 * 退货批次进入隔离库位后不能再次出库（移动仅限隔离区，唯一出库路径=双人复核通过的
 * 放行/销毁决定）；每次移动记录库位/人员/时间；质量人员完成检验才能提出放行/销毁/转待查；
 * 处理决定双人复核（提出人与复核人必须不同账号）；证据链可导出；
 * 运输对象置为无效后页面、导出、后台同口径；Shiro 401/403 权限校验。
 *
 * 随机端口真实启服务，Shiro HTTP Basic 完整生效；不回滚，验证真实持久化。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ReturnIsolationIntegrationTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String BASE = "/api/cold-chain";

    @LocalServerPort
    private int port;

    @Autowired private TestRestTemplate http;

    @Autowired private DrugService drugService;
    @Autowired private VehicleService vehicleService;
    @Autowired private OrderService orderService;
    @Autowired private ProbeService probeService;
    @Autowired private TransportBatchService batchService;

    @Autowired private TransportBatchMapper batchMapper;
    @Autowired private ReturnBatchMapper returnMapper;
    @Autowired private ReturnMovementMapper movementMapper;
    @Autowired private ReturnInspectionMapper inspectionMapper;
    @Autowired private ReturnDecisionMapper decisionMapper;
    @Autowired private OpLogMapper opLogMapper;

    // ====================================================================
    // 1) 三类退货原因分别登记：快照固化、原批次只读关联、关联异常类型必须匹配
    // ====================================================================
    @Test
    void registerThreeReasons_snapshotFrozen_andSourceBatchReadOnly() throws Exception {
        // —— 收货拒签：先拒签产生 REJECTION 异常，再据此登记退货 ——
        long batch1 = shipNewBatch(20);
        JsonNode reject = postAs("dispatcher", "dispatcher123", "/batches/reject",
                map("batchId", batch1, "rejectBy", "院药库验收员", "reason", "随货温度记录缺失，整批拒收"));
        assertTrue(reject.path("success").asBoolean(), reject.toString());
        long rejectionExcId = reject.path("data").path("id").asLong();
        assertEquals("REJECTION", reject.path("data").path("exceptionType").asText());

        TransportBatch before1 = batchMapper.selectById(batch1);
        JsonNode r1 = postAs("receiver", "receiver123", "/returns",
                map("sourceBatchId", batch1, "reasonType", "REJECTION",
                        "reasonDetail", "收货方验收拒签，整批退回", "exceptionId", rejectionExcId,
                        "returnQty", 20));
        assertTrue(r1.path("success").asBoolean(), r1.toString());
        long ret1 = r1.path("data").path("id").asLong();
        assertTrue(r1.path("data").path("returnNo").asText().startsWith("TH"));
        assertEquals("REGISTERED", r1.path("data").path("status").asText());

        // 快照固化：批次号/批准文号/温区/包装规格与原批次一致
        ReturnBatch row1 = returnMapper.selectById(ret1);
        assertEquals(before1.getBatchNo(), row1.getSourceBatchNo());
        assertEquals(before1.getApprovalNoSnap(), row1.getApprovalNoSnap());
        assertEquals(0, before1.getTempMinSnap().compareTo(row1.getTempMinSnap()));
        assertEquals(0, before1.getTempMaxSnap().compareTo(row1.getTempMaxSnap()));
        assertEquals(before1.getPackageSpecSnap(), row1.getPackageSpecSnap());
        assertNotNull(row1.getDrugNameSnap());
        assertEquals(rejectionExcId, row1.getExceptionId().longValue());

        // 原销售批次只读关联：退货登记不回写原批次任何字段
        TransportBatch after1 = batchMapper.selectById(batch1);
        assertEquals(before1.getStatus(), after1.getStatus());
        assertEquals(before1.getUpdateTime(), after1.getUpdateTime());

        // —— 包装破损：人工标记 DAMAGE 异常后登记退货 ——
        long batch2 = shipNewBatch(10);
        JsonNode damage = postAs("dispatcher", "dispatcher123", "/batches/exceptions",
                map("batchId", batch2, "exceptionType", "DAMAGE",
                        "description", "到货外箱挤压破损，内包装受潮"));
        assertTrue(damage.path("success").asBoolean(), damage.toString());
        long damageExcId = damage.path("data").path("id").asLong();
        JsonNode r2 = postAs("dispatcher", "dispatcher123", "/returns",
                map("sourceBatchId", batch2, "reasonType", "PACKAGE_DAMAGE",
                        "reasonDetail", "外包装破损退回", "exceptionId", damageExcId, "returnQty", 10));
        assertTrue(r2.path("success").asBoolean(), r2.toString());
        assertEquals("PACKAGE_DAMAGE", r2.path("data").path("reasonType").asText());

        // —— 温度超限：不关联异常也可登记（异常关联可空） ——
        long batch3 = shipNewBatch(5);
        JsonNode r3 = postAs("dispatcher", "dispatcher123", "/returns",
                map("sourceBatchId", batch3, "reasonType", "TEMP_EXCURSION",
                        "reasonDetail", "途中温度记录仪显示超 8℃ 持续 40 分钟", "returnQty", 5));
        assertTrue(r3.path("success").asBoolean(), r3.toString());
        assertNull(returnMapper.selectById(r3.path("data").path("id").asLong()).getExceptionId());

        // —— 停点：原因类型非法 / 关联异常类型不匹配 / 异常不属于本批次 / 数量超发运量 ——
        assertStop(postAs("dispatcher", "dispatcher123", "/returns",
                map("sourceBatchId", batch3, "reasonType", "DELAY",
                        "reasonDetail", "延误", "returnQty", 1)), "退货原因类型非法");
        assertStop(postAs("dispatcher", "dispatcher123", "/returns",
                map("sourceBatchId", batch2, "reasonType", "REJECTION",
                        "reasonDetail", "错配", "exceptionId", damageExcId, "returnQty", 1)), "不匹配");
        assertStop(postAs("dispatcher", "dispatcher123", "/returns",
                map("sourceBatchId", batch3, "reasonType", "PACKAGE_DAMAGE",
                        "reasonDetail", "跨批次引用", "exceptionId", damageExcId, "returnQty", 1)), "不属于原批次");
        assertStop(postAs("dispatcher", "dispatcher123", "/returns",
                map("sourceBatchId", batch3, "reasonType", "TEMP_EXCURSION",
                        "reasonDetail", "超量", "returnQty", 99)), "超过原批次发运数量");

        // 退货登记动作逐次留痕
        assertTrue(countAction(CcConstants.ACT_RETURN_REGISTER) >= 3);
    }

    // ====================================================================
    // 2) 隔离库位：进入后不能再次出库；每次移动记录库位、人员和时间
    // ====================================================================
    @Test
    void isolationMovement_recordedAndCannotLeaveIsolation() throws Exception {
        long batchId = shipNewBatch(8);
        long retId = registerReturn(batchId, "PACKAGE_DAMAGE", "外箱破损", 8);

        // 未入隔离不能移动
        assertStop(postAs("dispatcher", "dispatcher123", "/returns/" + retId + "/move",
                map("toLocation", "ISO-A-01")), "不允许库位移动");
        // 隔离库位必须以 ISO 开头
        assertStop(postAs("dispatcher", "dispatcher123", "/returns/" + retId + "/isolate",
                map("isolationLocation", "良品仓-A01")), "不是隔离库位");
        // 未检验不能提决定（质量人员完成检验才能选择放行/销毁/转待查）
        assertStop(postAs("qa", "qa123", "/returns/" + retId + "/decisions",
                map("decisionType", "RELEASE", "reason", "未检验先决定", "targetLocation", "合格品库-1")),
                "完成检验");

        // 入隔离：状态推进 + ISOLATE_IN 移动留痕（库位/人员/时间）
        JsonNode iso = postAs("dispatcher", "dispatcher123", "/returns/" + retId + "/isolate",
                map("isolationLocation", "ISO-A-01", "note", "退货收货区清点完毕，转入隔离"));
        assertTrue(iso.path("success").asBoolean(), iso.toString());
        assertEquals("ISOLATED", iso.path("data").path("status").asText());
        ReturnBatch row = returnMapper.selectById(retId);
        assertEquals("ISO-A-01", row.getCurrentLocation());
        assertNotNull(row.getIsolatedTime());
        assertNotNull(row.getIsolatedByName());

        List<ReturnMovement> moves = movementMapper.selectList(new QueryWrapper<ReturnMovement>()
                .eq("return_id", retId).orderByAsc("id"));
        assertEquals(1, moves.size());
        assertEquals("ISOLATE_IN", moves.get(0).getMoveType());
        assertNull(moves.get(0).getFromLocation());
        assertEquals("ISO-A-01", moves.get(0).getToLocation());
        assertEquals("dispatcher", moves.get(0).getOperatorName());
        assertNotNull(moves.get(0).getOperatorId());
        assertNotNull(moves.get(0).getMoveTime());

        // 隔离区内移动：留痕 from -> to
        JsonNode mv = postAs("dispatcher", "dispatcher123", "/returns/" + retId + "/move",
                map("toLocation", "ISO-B-02", "note", "隔离区扩容调整"));
        assertTrue(mv.path("success").asBoolean(), mv.toString());
        assertEquals("ISO-B-02", returnMapper.selectById(retId).getCurrentLocation());
        moves = movementMapper.selectList(new QueryWrapper<ReturnMovement>()
                .eq("return_id", retId).orderByAsc("id"));
        assertEquals(2, moves.size());
        assertEquals("MOVE", moves.get(1).getMoveType());
        assertEquals("ISO-A-01", moves.get(1).getFromLocation());
        assertEquals("ISO-B-02", moves.get(1).getToLocation());

        // 不能再次出库：移动到隔离区外库位一律拦截；原地移动同样拒绝
        assertStop(postAs("dispatcher", "dispatcher123", "/returns/" + retId + "/move",
                map("toLocation", "合格品库-1")), "不能再次出库");
        assertStop(postAs("dispatcher", "dispatcher123", "/returns/" + retId + "/move",
                map("toLocation", "发货月台")), "不能再次出库");
        assertStop(postAs("dispatcher", "dispatcher123", "/returns/" + retId + "/move",
                map("toLocation", "ISO-B-02")), "无需移动");
        // 移动留痕仍是 2 条（被拦截动作不产生移动记录）
        assertEquals(2, movementMapper.selectCount(new QueryWrapper<ReturnMovement>()
                .eq("return_id", retId)).longValue());
        assertTrue(countAction(CcConstants.ACT_RETURN_ISOLATE) >= 1);
        assertTrue(countAction(CcConstants.ACT_RETURN_MOVE) >= 1);
    }

    // ====================================================================
    // 3) 检验门控 + 双人复核：放行 / 销毁 / 转待查；驳回退回已检验
    // ====================================================================
    @Test
    void inspectThenDualReviewDecision_releaseDestroyPendingReview() throws Exception {
        // —— 放行路径：QA 检验 -> QA 提决定 -> 自复核拦截 -> admin 第二人复核通过 ——
        long ret1 = registerIsolated(shipNewBatch(6), "TEMP_EXCURSION", "途中越限 35 分钟", 6);
        // 非 QA 账号不能检验（无 coldchain:return:inspect 权限，Shiro 403）
        assertEquals(403, postRaw("dispatcher", "dispatcher123", "/returns/" + ret1 + "/inspect",
                map("inspectResult", "PASS", "inspectNote", "无权限尝试")).getStatusCode().value());
        JsonNode insp = postAs("qa", "qa123", "/returns/" + ret1 + "/inspect",
                map("inspectResult", "PASS", "inspectNote", "温度数据复核，偏差在可接受范围，检验合格"));
        assertTrue(insp.path("success").asBoolean(), insp.toString());
        assertEquals("INSPECTED", returnMapper.selectById(ret1).getStatus());
        ReturnInspection inspRow = inspectionMapper.selectById(insp.path("data").path("id").asLong());
        assertEquals("qa", inspRow.getInspectorName());
        assertNotNull(inspRow.getInspectTime());

        JsonNode dec1 = postAs("qa", "qa123", "/returns/" + ret1 + "/decisions",
                map("decisionType", "RELEASE", "reason", "检验合格，放行回合格品库", "targetLocation", "合格品库-R1"));
        assertTrue(dec1.path("success").asBoolean(), dec1.toString());
        long dec1Id = dec1.path("data").path("id").asLong();
        assertEquals("DECISION_PENDING", returnMapper.selectById(ret1).getStatus());
        // 决定待复核期间库位冻结
        assertStop(postAs("dispatcher", "dispatcher123", "/returns/" + ret1 + "/move",
                map("toLocation", "ISO-C-01")), "库位冻结");
        // 双人复核：同一账号不能自提自复
        assertStop(postAs("qa", "qa123", "/returns/decisions/" + dec1Id + "/review",
                map("approved", true, "reviewNote", "自己复核自己")), "同一账号");
        // 第二账号复核通过：放行生效，自动生成放行出库移动
        JsonNode rev1 = postAs("admin", "admin123", "/returns/decisions/" + dec1Id + "/review",
                map("approved", true, "reviewNote", "检验记录完整，同意放行"));
        assertTrue(rev1.path("success").asBoolean(), rev1.toString());
        ReturnBatch released = returnMapper.selectById(ret1);
        assertEquals("RELEASED", released.getStatus());
        assertEquals("RELEASE", released.getFinalDecision());
        assertNotNull(released.getFinalTime());
        assertEquals("合格品库-R1", released.getCurrentLocation());
        List<ReturnMovement> moves1 = movementMapper.selectList(new QueryWrapper<ReturnMovement>()
                .eq("return_id", ret1).orderByAsc("id"));
        assertEquals("RELEASE_OUT", moves1.get(moves1.size() - 1).getMoveType());
        assertEquals("ISO-A-01", moves1.get(moves1.size() - 1).getFromLocation());
        assertEquals("合格品库-R1", moves1.get(moves1.size() - 1).getToLocation());
        ReturnDecision dec1Row = decisionMapper.selectById(dec1Id);
        assertEquals("CONFIRMED", dec1Row.getStatus());
        assertEquals("qa", dec1Row.getProposeName());
        assertEquals("admin", dec1Row.getReviewName());
        // 终态后不能再移动/检验/决定
        assertStop(postAs("dispatcher", "dispatcher123", "/returns/" + ret1 + "/move",
                map("toLocation", "ISO-C-01")), "不允许库位移动");
        assertStop(postAs("qa", "qa123", "/returns/" + ret1 + "/inspect",
                map("inspectResult", "PASS", "inspectNote", "终态再检验")), "不能检验");

        // —— 销毁路径 ——
        long ret2 = registerIsolated(shipNewBatch(4), "PACKAGE_DAMAGE", "内包装污染", 4);
        assertTrue(postAs("qa", "qa123", "/returns/" + ret2 + "/inspect",
                map("inspectResult", "FAIL", "inspectNote", "药品受污染，不可再销售")).path("success").asBoolean());
        // 销毁目标不能仍是隔离库位
        JsonNode badTarget = postAs("qa", "qa123", "/returns/" + ret2 + "/decisions",
                map("decisionType", "DESTROY", "reason", "污染报废", "targetLocation", "ISO-X-9"));
        assertStop(badTarget, "仍是隔离库位");
        JsonNode dec2 = postAs("qa", "qa123", "/returns/" + ret2 + "/decisions",
                map("decisionType", "DESTROY", "reason", "污染报废，按危废销毁", "targetLocation", "销毁区-D1"));
        long dec2Id = dec2.path("data").path("id").asLong();
        assertTrue(postAs("admin", "admin123", "/returns/decisions/" + dec2Id + "/review",
                map("approved", true, "reviewNote", "同意销毁")).path("success").asBoolean());
        assertEquals("DESTROYED", returnMapper.selectById(ret2).getStatus());
        List<ReturnMovement> moves2 = movementMapper.selectList(new QueryWrapper<ReturnMovement>()
                .eq("return_id", ret2).orderByAsc("id"));
        assertEquals("DESTROY_OUT", moves2.get(moves2.size() - 1).getMoveType());

        // —— 转待查：留在隔离区，须重新检验后才能再决定 ——
        long ret3 = registerIsolated(shipNewBatch(3), "REJECTION", "客户拒收退回", 3);
        assertTrue(postAs("qa", "qa123", "/returns/" + ret3 + "/inspect",
                map("inspectResult", "FAIL", "inspectNote", "部分指标存疑，需进一步确认")).path("success").asBoolean());
        // 转待查不能带目标库位
        assertStop(postAs("qa", "qa123", "/returns/" + ret3 + "/decisions",
                map("decisionType", "PENDING_REVIEW", "reason", "存疑", "targetLocation", "合格品库-1")),
                "留在隔离区");
        JsonNode dec3 = postAs("qa", "qa123", "/returns/" + ret3 + "/decisions",
                map("decisionType", "PENDING_REVIEW", "reason", "指标存疑，转待查进一步确认"));
        long dec3Id = dec3.path("data").path("id").asLong();
        assertTrue(postAs("admin", "admin123", "/returns/decisions/" + dec3Id + "/review",
                map("approved", true, "reviewNote", "同意转待查")).path("success").asBoolean());
        ReturnBatch pendingRow = returnMapper.selectById(ret3);
        assertEquals("PENDING_REVIEW", pendingRow.getStatus());
        assertEquals("ISO-A-01", pendingRow.getCurrentLocation(), "转待查留在隔离区");
        assertNull(pendingRow.getFinalDecision(), "转待查非终态");
        // 转待查不产生出库移动
        long outMoves = movementMapper.selectCount(new QueryWrapper<ReturnMovement>()
                .eq("return_id", ret3).in("move_type", "RELEASE_OUT", "DESTROY_OUT"));
        assertEquals(0, outMoves);
        // 待查期间不可直接再提决定，须重新检验
        assertStop(postAs("qa", "qa123", "/returns/" + ret3 + "/decisions",
                map("decisionType", "RELEASE", "reason", "未复检直接放行", "targetLocation", "合格品库-2")),
                "完成检验");
        assertTrue(postAs("qa", "qa123", "/returns/" + ret3 + "/inspect",
                map("inspectResult", "PASS", "inspectNote", "复检合格")).path("success").asBoolean());
        JsonNode dec3b = postAs("qa", "qa123", "/returns/" + ret3 + "/decisions",
                map("decisionType", "RELEASE", "reason", "复检合格放行", "targetLocation", "合格品库-2"));
        assertTrue(postAs("admin", "admin123",
                "/returns/decisions/" + dec3b.path("data").path("id").asLong() + "/review",
                map("approved", true, "reviewNote", "复检报告齐全，同意放行")).path("success").asBoolean());
        assertEquals("RELEASED", returnMapper.selectById(ret3).getStatus());

        // —— 复核驳回：退回已检验，可重新提出；历史决定保留 ——
        long ret4 = registerIsolated(shipNewBatch(2), "TEMP_EXCURSION", "越限待评估", 2);
        assertTrue(postAs("qa", "qa123", "/returns/" + ret4 + "/inspect",
                map("inspectResult", "PASS", "inspectNote", "初检合格")).path("success").asBoolean());
        JsonNode dec4 = postAs("qa", "qa123", "/returns/" + ret4 + "/decisions",
                map("decisionType", "RELEASE", "reason", "拟放行", "targetLocation", "合格品库-3"));
        long dec4Id = dec4.path("data").path("id").asLong();
        assertTrue(postAs("admin", "admin123", "/returns/decisions/" + dec4Id + "/review",
                map("approved", false, "reviewNote", "缺少温度曲线附件，驳回补充")).path("success").asBoolean());
        assertEquals("INSPECTED", returnMapper.selectById(ret4).getStatus());
        assertEquals("REJECTED", decisionMapper.selectById(dec4Id).getStatus());
        // 驳回后可重新提出并复核通过
        JsonNode dec4b = postAs("qa", "qa123", "/returns/" + ret4 + "/decisions",
                map("decisionType", "RELEASE", "reason", "已补温度曲线，再次申请放行", "targetLocation", "合格品库-3"));
        assertTrue(postAs("admin", "admin123",
                "/returns/decisions/" + dec4b.path("data").path("id").asLong() + "/review",
                map("approved", true, "reviewNote", "附件齐全，同意")).path("success").asBoolean());
        assertEquals("RELEASED", returnMapper.selectById(ret4).getStatus());
        // 两次决定（含被驳回）都原样保留
        assertEquals(2, decisionMapper.selectCount(new QueryWrapper<ReturnDecision>()
                .eq("return_id", ret4)).longValue());
        assertTrue(countAction(CcConstants.ACT_RETURN_INSPECT) >= 4);
        assertTrue(countAction(CcConstants.ACT_RETURN_REVIEW) >= 6);
    }

    // ====================================================================
    // 4) 证据链导出：登记/移动/检验/双人复核/操作日志一单到底，导出留痕
    // ====================================================================
    @Test
    void evidenceChainExport_containsAllNodes_andExportAudited() throws Exception {
        long batchId = shipNewBatch(6);
        long retId = registerIsolated(batchId, "PACKAGE_DAMAGE", "外箱破损退回", 6);
        assertTrue(postAs("dispatcher", "dispatcher123", "/returns/" + retId + "/move",
                map("toLocation", "ISO-B-09", "note", "隔离区调整")).path("success").asBoolean());
        assertTrue(postAs("qa", "qa123", "/returns/" + retId + "/inspect",
                map("inspectResult", "FAIL", "inspectNote", "破损污染，建议销毁")).path("success").asBoolean());
        JsonNode dec = postAs("qa", "qa123", "/returns/" + retId + "/decisions",
                map("decisionType", "DESTROY", "reason", "污染报废", "targetLocation", "销毁区-D2"));
        assertTrue(postAs("admin", "admin123",
                "/returns/decisions/" + dec.path("data").path("id").asLong() + "/review",
                map("approved", true, "reviewNote", "同意销毁")).path("success").asBoolean());

        ReturnBatch ret = returnMapper.selectById(retId);
        ResponseEntity<String> ev = http.withBasicAuth("qa", "qa123")
                .getForEntity("http://localhost:" + port + BASE + "/returns/" + retId + "/evidence", String.class);
        assertEquals(200, ev.getStatusCode().value());
        String csv = ev.getBody();
        assertNotNull(csv);
        assertTrue(csv.startsWith("\uFEFF"), "CSV 应带 UTF-8 BOM");
        // 登记快照
        assertTrue(csv.contains(ret.getReturnNo()));
        assertTrue(csv.contains(ret.getSourceBatchNo()));
        assertTrue(csv.contains("包装破损"));
        // 移动留痕：库位/人员/时间
        assertTrue(csv.contains("ISO-A-01"));
        assertTrue(csv.contains("ISO-B-09"));
        assertTrue(csv.contains("销毁区-D2"));
        assertTrue(csv.contains("dispatcher"));
        // 检验
        assertTrue(csv.contains("FAIL"));
        assertTrue(csv.contains("破损污染，建议销毁"));
        // 双人复核
        assertTrue(csv.contains("qa"));
        assertTrue(csv.contains("admin"));
        assertTrue(csv.contains("同意销毁"));
        // 操作日志段
        assertTrue(csv.contains("RETURN_REGISTER"));
        assertTrue(csv.contains("RETURN_REVIEW"));

        // 列表导出与后台接口同一视图（同口径）
        ResponseEntity<String> list = http.withBasicAuth("qa", "qa123")
                .getForEntity("http://localhost:" + port + BASE + "/returns/export", String.class);
        assertEquals(200, list.getStatusCode().value());
        assertTrue(list.getBody().contains(ret.getReturnNo()));
        assertTrue(list.getBody().contains("已销毁"));

        // 导出逐次写审计流水
        assertTrue(countAction(CcConstants.ACT_RETURN_EXPORT) >= 2);
    }

    // ====================================================================
    // 5) 运输对象置为无效后：页面、导出、后台同口径
    // ====================================================================
    @Test
    void invalidateSourceBatch_pageExportApiStayConsistent() throws Exception {
        long batchId = shipNewBatch(9);
        long retId = registerIsolated(batchId, "REJECTION", "收货方拒签退回", 9);
        ReturnBatch ret = returnMapper.selectById(retId);

        // 将冷链药品运输对象置为无效（撤回原销售批次）
        JsonNode revoke = postAs("dispatcher", "dispatcher123", "/batches/revoke",
                map("batchId", batchId, "reason", "运输任务取消，批次作废"));
        assertTrue(revoke.path("success").asBoolean(), revoke.toString());
        assertEquals("REVOKED", batchMapper.selectById(batchId).getStatus());

        // —— 后台接口：退货记录保留，原批次状态一致展示为已作废 ——
        JsonNode apiList = getAs("dispatcher", "dispatcher123", "/returns");
        JsonNode apiView = null;
        for (JsonNode v : apiList) {
            if (v.path("returnBatch").path("id").asLong() == retId) {
                apiView = v;
            }
        }
        assertNotNull(apiView, "后台列表必须仍包含该退货批次");
        assertEquals("REVOKED", apiView.path("sourceBatchStatus").asText());
        assertEquals("已作废（无效）", apiView.path("sourceBatchStatusName").asText());
        assertTrue(apiView.path("sourceBatchInvalid").asBoolean());
        assertEquals("已入隔离", apiView.path("statusName").asText());
        assertEquals("收货拒签", apiView.path("reasonTypeName").asText());
        assertEquals(ret.getSourceBatchNo(), apiView.path("returnBatch").path("sourceBatchNo").asText());
        assertEquals("ISO-A-01", apiView.path("returnBatch").path("currentLocation").asText());

        // —— 页面：同一退货单、同一原批次作废徽标、同一状态 ——
        ResponseEntity<String> page = http.withBasicAuth("dispatcher", "dispatcher123")
                .getForEntity("http://localhost:" + port + "/ui/cold-chain/returns", String.class);
        assertEquals(200, page.getStatusCode().value());
        String html = page.getBody();
        assertNotNull(html);
        assertTrue(html.contains("退货批次隔离处理"));
        assertTrue(html.contains(ret.getReturnNo()));
        assertTrue(html.contains("已作废（无效）"));
        assertTrue(html.contains("已入隔离"));
        assertTrue(html.contains("ISO-A-01"));

        // —— 列表导出 CSV：与页面/后台同一行数据 ——
        ResponseEntity<String> csv = http.withBasicAuth("qa", "qa123")
                .getForEntity("http://localhost:" + port + BASE + "/returns/export", String.class);
        assertEquals(200, csv.getStatusCode().value());
        assertTrue(csv.getBody().contains(ret.getReturnNo()));
        assertTrue(csv.getBody().contains("已作废（无效）"));
        assertTrue(csv.getBody().contains("已入隔离"));
        assertTrue(csv.getBody().contains("ISO-A-01"));

        // 退货流程不被原批次作废影响：检验/决定/复核照常推进
        assertTrue(postAs("qa", "qa123", "/returns/" + retId + "/inspect",
                map("inspectResult", "PASS", "inspectNote", "原批次已作废，退货检验照常")).path("success").asBoolean());
        JsonNode dec = postAs("qa", "qa123", "/returns/" + retId + "/decisions",
                map("decisionType", "RELEASE", "reason", "检验合格放行", "targetLocation", "合格品库-9"));
        assertTrue(postAs("admin", "admin123",
                "/returns/decisions/" + dec.path("data").path("id").asLong() + "/review",
                map("approved", true, "reviewNote", "同意")).path("success").asBoolean());
        assertEquals("RELEASED", returnMapper.selectById(retId).getStatus());

        // 原销售批次台账可追溯退货关联（只读）
        JsonNode ledger = getAs("dispatcher", "dispatcher123", "/batches/" + batchId + "/ledger");
        assertEquals(1, ledger.path("returns").size());
        assertEquals(ret.getReturnNo(), ledger.path("returns").get(0).path("returnBatch").path("returnNo").asText());
        assertEquals("RELEASED", ledger.path("returns").get(0).path("returnBatch").path("status").asText());
    }

    // ====================================================================
    // 6) Shiro 权限：401 未认证 / 403 无权限 / 200 正常
    // ====================================================================
    @Test
    void shiroPermissions_401_403_200() throws Exception {
        long batchId = shipNewBatch(4);
        long retId = registerReturn(batchId, "TEMP_EXCURSION", "越限", 4);

        // 未认证 -> 401
        ResponseEntity<String> anon = http.getForEntity(
                "http://localhost:" + port + BASE + "/returns", String.class);
        assertEquals(401, anon.getStatusCode().value());

        // 承运岗无任何退货权限 -> 403（查看/办理/页面）
        assertEquals(403, http.withBasicAuth("carrier", "carrier123")
                .getForEntity("http://localhost:" + port + BASE + "/returns", String.class)
                .getStatusCode().value());
        assertEquals(403, postRaw("carrier", "carrier123", "/returns/" + retId + "/isolate",
                map("isolationLocation", "ISO-A-1")).getStatusCode().value());
        assertEquals(403, http.withBasicAuth("carrier", "carrier123")
                .getForEntity("http://localhost:" + port + "/ui/cold-chain/returns", String.class)
                .getStatusCode().value());

        // 调度可办理但无检验/复核/导出权限 -> 403
        assertEquals(200, http.withBasicAuth("dispatcher", "dispatcher123")
                .getForEntity("http://localhost:" + port + BASE + "/returns", String.class)
                .getStatusCode().value());
        assertEquals(403, postRaw("dispatcher", "dispatcher123", "/returns/" + retId + "/inspect",
                map("inspectResult", "PASS", "inspectNote", "x")).getStatusCode().value());
        assertEquals(403, http.withBasicAuth("dispatcher", "dispatcher123")
                .getForEntity("http://localhost:" + port + BASE + "/returns/export", String.class)
                .getStatusCode().value());

        // QA 可检验但无办理权限（入隔离） -> 403；QA 导出 -> 200
        assertEquals(403, postRaw("qa", "qa123", "/returns/" + retId + "/isolate",
                map("isolationLocation", "ISO-A-1")).getStatusCode().value());
        assertEquals(200, http.withBasicAuth("qa", "qa123")
                .getForEntity("http://localhost:" + port + BASE + "/returns/export", String.class)
                .getStatusCode().value());
        assertEquals(200, http.withBasicAuth("qa", "qa123")
                .getForEntity("http://localhost:" + port + "/ui/cold-chain/returns", String.class)
                .getStatusCode().value());
    }

    // ---------------- 全链路建样：建档->订单->车辆->持证探头->派车->发运 ----------------

    private long shipNewBatch(int shipQty) {
        long nano = System.nanoTime();

        DrugCreateRequest dc = new DrugCreateRequest();
        dc.setDrugName("V11验收药品-" + nano);
        dc.setApprovalNo("国药准字V11-" + Math.abs(nano % 1000000));
        dc.setTempMin(new BigDecimal("2.00"));
        dc.setTempMax(new BigDecimal("8.00"));
        dc.setPackageSpec("1支/盒");
        Long drugId = drugService.create(dc).getId();

        OrderCreateRequest oc = new OrderCreateRequest();
        oc.setDrugId(drugId);
        oc.setReceiverOrg("V11验收医院");
        oc.setShipQty(shipQty);
        Long orderId = orderService.create(oc).getId();

        VehicleCreateRequest vr = new VehicleCreateRequest();
        vr.setPlateNo("测B·退" + Math.abs(nano % 1000000));
        vr.setCarrierName("V11验收物流-" + Math.abs(nano % 100000));
        vr.setDriverName("王司机");
        Long vehicleId = vehicleService.create(vr).getId();

        ProbeCreateRequest pc = new ProbeCreateRequest();
        pc.setSerialNo("SN-V11-" + nano);
        pc.setModel("TP-200");
        pc.setRangeMin(new BigDecimal("-40.00"));
        pc.setRangeMax(new BigDecimal("60.00"));
        pc.setCalibrateOrg("华东计量院");
        pc.setValidUntil(LocalDateTime.now().plusYears(1));
        pc.setCertNo("CERT-V11-" + nano);
        pc.setCalibrateDate(LocalDateTime.now().minusDays(1));
        pc.setCertFileName(nano + ".txt");
        pc.setCertFileContent("V11校准证书-" + nano);
        long probeId = probeService.createProbe(pc).getId();

        ProbeBindRequest pb = new ProbeBindRequest();
        pb.setRequestId("v11bind-" + nano);
        pb.setVehicleId(vehicleId);
        pb.setProbeId(probeId);
        probeService.bindIdempotent(pb);

        DispatchRequest dr = new DispatchRequest();
        dr.setOrderId(orderId);
        dr.setVehicleId(vehicleId);
        Long batchId = batchService.dispatch(dr).getId();
        batchService.ship(batchId);
        return batchId;
    }

    /** 登记退货（调度办理）并返回退货ID */
    private long registerReturn(long batchId, String reasonType, String detail, int qty) throws Exception {
        JsonNode r = postAs("dispatcher", "dispatcher123", "/returns",
                map("sourceBatchId", batchId, "reasonType", reasonType,
                        "reasonDetail", detail, "returnQty", qty));
        assertTrue(r.path("success").asBoolean(), r.toString());
        return r.path("data").path("id").asLong();
    }

    /** 登记 + 入隔离库位 ISO-A-01 */
    private long registerIsolated(long batchId, String reasonType, String detail, int qty) throws Exception {
        long retId = registerReturn(batchId, reasonType, detail, qty);
        JsonNode iso = postAs("dispatcher", "dispatcher123", "/returns/" + retId + "/isolate",
                map("isolationLocation", "ISO-A-01", "note", "退货入隔离"));
        assertTrue(iso.path("success").asBoolean(), iso.toString());
        return retId;
    }

    // ---------------- 请求体/断言/HTTP 辅助 ----------------

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private long countAction(String action) {
        Long n = opLogMapper.selectCount(new QueryWrapper<OpLog>().eq("action", action));
        return n == null ? 0 : n;
    }

    private void assertStop(JsonNode resp, String keyword) {
        assertFalse(resp.path("success").asBoolean(), "应被拦截: " + resp);
        assertTrue(resp.path("message").asText().contains(keyword),
                "停点说明应包含[" + keyword + "]，实际：" + resp.path("message").asText());
    }

    private JsonNode postAs(String user, String pass, String path, Object body) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Object> entity = new HttpEntity<>(body, headers);
            String resp = http.withBasicAuth(user, pass)
                    .postForObject("http://localhost:" + port + BASE + path, entity, String.class);
            return M.readTree(resp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 原始 POST（用于 403 状态断言，不解析业务体） */
    private ResponseEntity<String> postRaw(String user, String pass, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Object> entity = new HttpEntity<>(body, headers);
        return http.withBasicAuth(user, pass)
                .postForEntity("http://localhost:" + port + BASE + path, entity, String.class);
    }

    /** GET 统一返回 ApiResponse 的 data 节点 */
    private JsonNode getAs(String user, String pass, String path) {
        try {
            String resp = http.withBasicAuth(user, pass)
                    .getForObject("http://localhost:" + port + BASE + path, String.class);
            JsonNode envelope = M.readTree(resp);
            assertTrue(envelope.path("success").asBoolean(), "GET 应成功: " + resp);
            return envelope.path("data");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
