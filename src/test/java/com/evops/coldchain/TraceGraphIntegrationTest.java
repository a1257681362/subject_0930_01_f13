package com.evops.coldchain;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.dto.DispatchRequest;
import com.evops.coldchain.dto.DrugCreateRequest;
import com.evops.coldchain.dto.OrderCreateRequest;
import com.evops.coldchain.dto.ProbeBindRequest;
import com.evops.coldchain.dto.ProbeCreateRequest;
import com.evops.coldchain.dto.TraceNodePublishRequest;
import com.evops.coldchain.dto.VehicleCreateRequest;
import com.evops.coldchain.entity.TraceNode;
import com.evops.coldchain.entity.TraceOpAudit;
import com.evops.coldchain.mapper.TraceNodeMapper;
import com.evops.coldchain.mapper.TraceOpAuditMapper;
import com.evops.coldchain.support.TestSubjects;
import com.evops.coldchain.service.DrugService;
import com.evops.coldchain.service.OrderService;
import com.evops.coldchain.service.ProbeService;
import com.evops.coldchain.service.TraceService;
import com.evops.coldchain.service.TransportBatchService;
import com.evops.coldchain.service.VehicleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 药品批次追溯图（V13）验收测试。
 *
 * 覆盖：
 * 1. 采购/仓储/发运/中转/签收/退货六节点自动发布链路，节点保存发布当刻状态快照；
 * 2. 快照不可变：补充只追加不改快照；同操作重复/补建被拒绝（已发布快照不能改变）；
 * 3. requestId 幂等（同键同载荷 replay、同键不同载荷拒绝）；漏记补建 BACKFILL；
 * 4. 冷链同一操作并发发起：恰好一个生效 WIN、另一个拒绝 LOST，双方操作者与拒绝原因均留审计，
 *    LOST 审计不随主事务回滚消失；
 * 5. 岗位机构授权：未授权机构/联系方式掩码，授权后按范围可见，撤销恢复掩码；
 * 6. 按批次号、按封签号反查；
 * 7. 导出 CSV 标明数据截止时间与过滤条件并逐次审计；
 * 8. Shiro 401/403/200。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TraceGraphIntegrationTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String BASE = "/api/cold-chain";

    @LocalServerPort
    private int port;

    @Autowired private TestRestTemplate http;
    @Autowired private DrugService drugService;
    @Autowired private OrderService orderService;
    @Autowired private VehicleService vehicleService;
    @Autowired private ProbeService probeService;
    @Autowired private TransportBatchService batchService;
    @Autowired private TraceService traceService;
    @Autowired private TestSubjects testSubjects;
    @Autowired private TraceNodeMapper nodeMapper;
    @Autowired private TraceOpAuditMapper auditMapper;

    // ====================================================================
    // 1) 六节点全链路自动发布 + 状态快照
    // ====================================================================
    @Test
    void fullChain_sixNodesWithImmutableSnapshots() throws Exception {
        Chain c = buildSignedChainWithTransit(10, 10);

        JsonNode graph = getAs("dispatcher", "dispatcher123",
                "/trace/graph/batch/" + c.batchId);
        assertEquals(6, graph.path("nodeTotal").asInt(), "应形成采购/仓储/发运/中转/签收/退货六个节点");
        assertEquals(6, graph.path("nodeReturned").asInt());
        assertEquals("PURCHASE", graph.path("nodes").get(0).path("nodeType").asText());
        assertEquals("WAREHOUSE", graph.path("nodes").get(1).path("nodeType").asText());
        assertEquals("SHIP", graph.path("nodes").get(2).path("nodeType").asText());
        assertEquals("TRANSIT", graph.path("nodes").get(3).path("nodeType").asText());
        assertEquals("SIGN", graph.path("nodes").get(4).path("nodeType").asText());
        assertEquals("RETURN", graph.path("nodes").get(5).path("nodeType").asText());

        // 采购节点在派车前发布，以订单为链路锚，快照含锁定版本与批准文号
        JsonNode purchase = graph.path("nodes").get(0);
        assertTrue(purchase.path("snapshot").path("pinnedVersionNo").asInt() >= 1);
        assertEquals(c.approvalNo, purchase.path("snapshot").path("approvalNo").asText());
        assertEquals("AUTO", purchase.path("source").asText());

        // 中转节点逐签固化封签锚点（发布当刻全部 CONFIRMED）
        JsonNode transit = graph.path("nodes").get(3);
        assertEquals(10, transit.path("seals").size());
        for (JsonNode s : transit.path("seals")) {
            assertEquals("CONFIRMED", s.path("sealStatus").asText());
        }
        // 发运节点快照含承运责任主体与探头
        JsonNode ship = graph.path("nodes").get(2);
        assertEquals("SHIPPED", ship.path("bizStatus").asText());
        assertNotNull(ship.path("snapshot").path("plateNo").asText(null));

        // 签收节点按快照核对闭环
        JsonNode sign = graph.path("nodes").get(4);
        assertEquals("SIGNED", sign.path("bizStatus").asText());
        assertEquals(c.approvalNo, sign.path("snapshot").path("checkedApprovalNo").asText());

        // 每个节点都有发布当刻固化的状态快照原文
        List<TraceNode> rows = nodeMapper.selectList(new QueryWrapper<TraceNode>()
                .eq("order_id", c.orderId).orderByAsc("occurred_time", "id"));
        assertEquals(6, rows.size());
        for (TraceNode n : rows) {
            assertNotNull(n.getStatusSnapshot());
            assertTrue(n.getStatusSnapshot().contains("{"), "快照应为发布当刻采集的 JSON");
        }

        // 数据截止时间与过滤条件总是随追溯结果返回
        assertNotNull(graph.path("dataCutoffTime").asText(null));
        assertEquals(c.batchNo, graph.path("filters").path("batchNo").asText());
        assertEquals("dispatcher", graph.path("filters").path("operator").asText());
    }

    // ====================================================================
    // 2) 快照不可变：补充只追加；重复发布/补建同键节点被拒绝
    // ====================================================================
    @Test
    void supplementAppendsOnly_andRepublishRejected() throws Exception {
        Chain c = buildSignedChainWithTransit(4, 4);
        JsonNode graph = getAs("dispatcher", "dispatcher123",
                "/trace/graph/batch/" + c.batchId);
        long signNodeId = graph.path("nodes").get(4).path("id").asLong();

        TraceNode before = nodeMapper.selectById(signNodeId);
        String snapshotBefore = before.getStatusSnapshot();

        // 追加补充：只新增补充行，已发布状态快照原值不变
        JsonNode supp = postAs("dispatcher", "dispatcher123", "/trace/nodes/supplement",
                map("requestId", "supp-" + c.nano, "nodeId", signNodeId,
                        "supplementType", "NOTE", "content", "签收后补传纸质回执扫描件编号 RPT-01"));
        assertTrue(supp.path("success").asBoolean(), supp.toString());
        assertFalse(supp.path("data").path("replay").asBoolean());

        TraceNode after = nodeMapper.selectById(signNodeId);
        assertEquals(snapshotBefore, after.getStatusSnapshot(), "补录不能改变已发布快照");

        JsonNode detail = getAs("dispatcher", "dispatcher123", "/trace/nodes/" + signNodeId);
        assertEquals(1, detail.path("supplements").size());
        assertTrue(detail.path("supplements").get(0).path("content").asText().contains("RPT-01"));

        // 对已发布操作再次手工发布（不同 requestId）：被拒绝，指明谁已生效
        JsonNode dup = postAs("dispatcher", "dispatcher123", "/trace/nodes/publish",
                publishBody(c.batchId, "SIGN", null, "dup-" + c.nano, null));
        assertFalse(dup.path("success").asBoolean());
        assertTrue(dup.path("message").asText().contains("已由账号"), dup.toString());
        assertTrue(dup.path("message").asText().contains("生效"), dup.toString());
        // 物理表仍只有 6 个节点，没有第二条签收节点
        long signCount = nodeMapper.selectCount(new QueryWrapper<TraceNode>()
                .eq("batch_id", c.batchId).eq("node_type", "SIGN"));
        assertEquals(1, signCount);
    }

    // ====================================================================
    // 3) requestId 幂等 + 漏记补建 BACKFILL
    // ====================================================================
    @Test
    void idempotentReplay_andBackfillCreatesHistoricalNode() throws Exception {
        Chain c = buildDispatchedChain(); // 仅派车，未发运：无发运节点

        TraceNodePublishRequest body = new TraceNodePublishRequest();
        body.setRequestId("bf-ship-" + c.nano);
        body.setBatchId(c.batchId);
        body.setNodeType("SHIP");
        body.setOccurredTime(LocalDateTime.now().minusDays(1));
        body.setOrgName(c.carrierName);
        body.setContactName("王司机");
        body.setContactPhone("13900000001");
        body.setRemark("断网恢复后补录漏记发运节点");

        // 第一次补建生效，source=BACKFILL，业务发生时间为历史时间
        JsonNode first = postAs("dispatcher", "dispatcher123", "/trace/nodes/backfill", body);
        assertTrue(first.path("success").asBoolean(), first.toString());
        assertEquals("BACKFILL", first.path("data").path("source").asText());
        long nodeId = first.path("data").path("nodeId").asLong();

        // 同 requestId 同载荷重放：replay=true，不新增节点
        JsonNode replay = postAs("dispatcher", "dispatcher123", "/trace/nodes/backfill", body);
        assertTrue(replay.path("success").asBoolean(), replay.toString());
        assertTrue(replay.path("data").path("replay").asBoolean());
        assertEquals(nodeId, replay.path("data").path("nodeId").asLong());
        assertEquals(1, nodeMapper.selectCount(new QueryWrapper<TraceNode>()
                .eq("batch_id", c.batchId).eq("node_type", "SHIP")));

        // 同 requestId 不同载荷：拒绝
        body.setRemark("改成别的内容");
        JsonNode clash = postAs("dispatcher", "dispatcher123", "/trace/nodes/backfill", body);
        assertFalse(clash.path("success").asBoolean());
        assertTrue(clash.path("message").asText().contains("不同内容"));
    }

    // ====================================================================
    // 4) 冷链同一操作并发发起：一生效 WIN、一拒绝 LOST，双方均留审计
    // ====================================================================
    @Test
    void concurrentSameOperation_oneWinsOneLost_bothAudited() throws Exception {
        Chain c = buildDispatchedChain(); // 尚无中转节点
        String bizKey = "JJ-RACE-" + c.nano;

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<JsonNodeLike> winner = new AtomicReference<>();
        AtomicReference<String> loserMessage = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();

        Runnable dispatcherAttempt = () -> testSubjects.runAs(2L, "dispatcher", () -> {
            try {
                ready.countDown();
                start.await();
                TraceNodePublishRequest req = manualBody(c.batchId, "TRANSIT", bizKey,
                        "race-d-" + c.nano, "华东中转仓", "张仓", null);
                Map<String, Object> r = traceService.publishManual(req, false);
                winner.set(new JsonNodeLike(r.get("nodeId") == null ? null
                        : Long.valueOf(String.valueOf(r.get("nodeId"))), "dispatcher"));
            } catch (com.evops.common.BusinessException e) {
                loserMessage.set(e.getMessage());
            } catch (Exception e) {
                error.set(e);
            }
        });
        Runnable adminAttempt = () -> testSubjects.runAs(5L, "admin", () -> {
            try {
                ready.countDown();
                start.await();
                TraceNodePublishRequest req = manualBody(c.batchId, "TRANSIT", bizKey,
                        "race-a-" + c.nano, "华东中转仓", "张仓", null);
                Map<String, Object> r = traceService.publishManual(req, false);
                winner.set(new JsonNodeLike(r.get("nodeId") == null ? null
                        : Long.valueOf(String.valueOf(r.get("nodeId"))), "admin"));
            } catch (com.evops.common.BusinessException e) {
                loserMessage.set(e.getMessage());
            } catch (Exception e) {
                error.set(e);
            }
        });

        Thread t1 = new Thread(dispatcherAttempt, "race-dispatcher");
        Thread t2 = new Thread(adminAttempt, "race-admin");
        t1.start();
        t2.start();
        ready.await();
        start.countDown();
        t1.join(10000);
        t2.join(10000);

        assertEquals(null, error.get(), "并发测试不应出现非预期异常");
        assertNotNull(winner.get(), "必须恰有一个请求生效");
        assertNotNull(loserMessage.get(), "必须恰有一个请求被拒绝");
        assertTrue(loserMessage.get().contains("生效"), "拒绝说明必须指明对方已生效：" + loserMessage.get());
        assertTrue(loserMessage.get().contains("被拒绝"), "拒绝说明必须说明本次被拒绝：" + loserMessage.get());

        // 物理表恰一个节点
        long nodeCount = nodeMapper.selectCount(new QueryWrapper<TraceNode>()
                .eq("batch_id", c.batchId).eq("node_type", "TRANSIT").eq("biz_key", bizKey));
        assertEquals(1, nodeCount);

        // 审计：WIN 与 LOST 各一行，LOST 指向生效节点，且记录双方操作者
        List<TraceOpAudit> raceAudits = auditMapper.selectList(new QueryWrapper<TraceOpAudit>()
                .eq("batch_id", c.batchId).eq("node_type", "TRANSIT")
                .in("result", "WIN", "LOST").orderByAsc("id"));
        long winCount = raceAudits.stream().filter(a -> "WIN".equals(a.getResult())).count();
        long lostCount = raceAudits.stream().filter(a -> "LOST".equals(a.getResult())).count();
        assertEquals(1, winCount);
        assertEquals(1, lostCount);
        TraceOpAudit win = raceAudits.stream().filter(a -> "WIN".equals(a.getResult())).findFirst().get();
        TraceOpAudit lost = raceAudits.stream().filter(a -> "LOST".equals(a.getResult())).findFirst().get();
        assertEquals(win.getNodeId(), lost.getNodeId(), "LOST 审计必须指向对方生效节点");
        assertTrue(("dispatcher".equals(win.getOperatorName()) && "admin".equals(lost.getOperatorName()))
                        || ("admin".equals(win.getOperatorName()) && "dispatcher".equals(lost.getOperatorName())),
                "审计必须保留双方操作者：win=" + win.getOperatorName() + ", lost=" + lost.getOperatorName());
        assertNotNull(lost.getRejectReason());
        assertTrue(lost.getRejectReason().contains("被拒绝"));
    }

    // ====================================================================
    // 5) 机构授权：未授权掩码 → 授权按范围可见 → 撤销恢复掩码
    // ====================================================================
    @Test
    void orgGrantMasksOrgAndContacts_untilGrantedThenRevoked() throws Exception {
        Chain c = buildSignedChainWithTransit(6, 6);

        // 承运岗有查看权限但无任何机构授权：机构名与电话全部掩码
        JsonNode masked = getAs("carrier", "carrier123", "/trace/graph/batch/" + c.batchId);
        boolean sawMaskedPhone = false;
        for (JsonNode n : masked.path("nodes")) {
            assertFalse(n.path("authorized").asBoolean(),
                    n.path("nodeType").asText() + " 不应被未授权账号看到机构明文");
            for (JsonNode p : n.path("snapshot").path("parties")) {
                if (!p.path("phone").isNull() && !"".equals(p.path("phone").asText())) {
                    assertTrue(p.path("phone").asText().contains("******"),
                            "未授权联系方式必须脱敏：" + p.path("phone").asText());
                    sawMaskedPhone = true;
                }
            }
        }
        assertTrue(sawMaskedPhone, "链路中应至少存在一个被脱敏的电话（发运承运司机）");

        // 管理员授予承运岗“承运机构可见、电话明文”
        JsonNode grant = postAs("admin", "admin123", "/trace/grants",
                map("username", "carrier", "orgName", c.carrierName, "contactVisible", true));
        assertTrue(grant.path("success").asBoolean(), grant.toString());
        long grantId = grant.path("data").path("id").asLong();

        JsonNode granted = getAs("carrier", "carrier123", "/trace/graph/batch/" + c.batchId);
        boolean sawPlainPhone = false;
        for (JsonNode n : granted.path("nodes")) {
            if ("SHIP".equals(n.path("nodeType").asText())) {
                assertTrue(n.path("authorized").asBoolean(), "授权后承运发运节点机构应可见");
                assertTrue(n.path("contactVisible").asBoolean());
                assertEquals("13900000001", n.path("contactPhone").asText());
                sawPlainPhone = true;
            } else {
                assertFalse(n.path("authorized").asBoolean(), "其他机构仍应掩码：" + n.path("nodeType").asText());
            }
        }
        assertTrue(sawPlainPhone);

        // 按未授权机构过滤被拒绝
        ResponseEntity<String> forbiddenFilter = getRaw("carrier", "carrier123",
                "/trace/graph/batch/" + c.batchId + "?org=" + enc("V13验收医院"));
        // 该批次收货机构未授权给 carrier：过滤直接拒绝
        JsonNode envelope = M.readTree(forbiddenFilter.getBody());
        assertFalse(envelope.path("success").asBoolean());
        assertTrue(envelope.path("message").asText().contains("未被授权机构"));

        // 撤销授权：恢复掩码（已发布节点事实不变）
        JsonNode revoke = postAs("admin", "admin123", "/trace/grants/revoke",
                map("id", grantId, "reason", "承运合同到期，收回机构联系方式可见权"));
        assertTrue(revoke.path("success").asBoolean(), revoke.toString());
        JsonNode again = getAs("carrier", "carrier123", "/trace/graph/batch/" + c.batchId);
        for (JsonNode n : again.path("nodes")) {
            assertFalse(n.path("authorized").asBoolean(), "撤销后应再次全部掩码");
        }
        // 节点事实不受授权收紧影响：调度视角仍是完整六节点
        assertEquals(6, getAs("dispatcher", "dispatcher123",
                "/trace/graph/batch/" + c.batchId).path("nodeTotal").asInt());
    }

    // ====================================================================
    // 6) 按批次号 / 封签号反查 + 节点类型过滤
    // ====================================================================
    @Test
    void reverseLookup_byBatchNoAndSeal() throws Exception {
        Chain c = buildSignedChainWithTransit(3, 3);

        JsonNode byNo = getAs("dispatcher", "dispatcher123",
                "/trace/graph/by-batch-no?batchNo=" + enc(c.batchNo));
        assertEquals(c.batchId, byNo.path("batchId").asLong());
        assertEquals(6, byNo.path("nodeTotal").asInt());

        // 节点类型过滤：只回收中转节点，被排除数=5，过滤条件回显
        JsonNode onlyTransit = getAs("dispatcher", "dispatcher123",
                "/trace/graph/by-batch-no?batchNo=" + enc(c.batchNo) + "&nodeType=TRANSIT");
        assertEquals(1, onlyTransit.path("nodeReturned").asInt());
        assertEquals(5, onlyTransit.path("filteredOut").asInt());
        assertEquals("TRANSIT", onlyTransit.path("filters").path("nodeType").asText());

        // 封签反查：命中封签锚点所属节点并返回整条链路，命中节点 hitBySeal=true
        String sealNo = c.sealPrefix + "1";
        JsonNode bySeal = getAs("qa", "qa123", "/trace/graph/by-seal?sealNo=" + enc(sealNo));
        assertEquals(c.batchId, bySeal.path("batchId").asLong());
        assertEquals(6, bySeal.path("nodeTotal").asInt());
        assertEquals(sealNo, bySeal.path("filters").path("sealNo").asText());
        boolean hit = false;
        for (JsonNode n : bySeal.path("nodes")) {
            if (n.path("hitBySeal").asBoolean(false)) {
                assertEquals("TRANSIT", n.path("nodeType").asText());
                hit = true;
            }
        }
        assertTrue(hit, "封签反查必须标记命中的中转节点");

        // 不存在的批次号/封签号：明确停点
        assertFalse(getRaw("dispatcher", "dispatcher123",
                "/trace/graph/by-batch-no?batchNo=NO_SUCH_BATCH").getBody().contains("\"success\":true"));
        assertFalse(getRaw("qa", "qa123",
                "/trace/graph/by-seal?sealNo=NO_SUCH_SEAL").getBody().contains("\"success\":true"));
    }

    // ====================================================================
    // 7) 导出：数据截止时间 + 过滤条件，逐次审计
    // ====================================================================
    @Test
    void export_hasCutoffAndFilters_andAudited() throws Exception {
        Chain c = buildSignedChainWithTransit(5, 5);

        ResponseEntity<String> resp = http.withBasicAuth("qa", "qa123").getForEntity(
                "http://localhost:" + port + BASE + "/trace/export?batchId=" + c.batchId, String.class);
        assertEquals(200, resp.getStatusCode().value());
        String csv = resp.getBody();
        assertNotNull(csv);
        assertTrue(csv.startsWith("\uFEFF"), "CSV 应带 UTF-8 BOM");
        assertTrue(csv.contains("数据截止时间"));
        assertTrue(csv.contains("过滤条件-批次号"));
        assertTrue(csv.contains(c.batchNo));
        assertTrue(csv.contains("过滤条件-节点类型"));
        assertTrue(csv.contains("采购"));
        assertTrue(csv.contains("中转"));
        assertTrue(csv.contains("退货"));
        assertTrue(csv.contains("岗位授权掩码"), "导出必须说明授权掩码口径");

        // 封签过滤导出同样带头条件
        ResponseEntity<String> bySeal = http.withBasicAuth("qa", "qa123").getForEntity(
                "http://localhost:" + port + BASE + "/trace/export?sealNo=" + enc(c.sealPrefix + "2"),
                String.class);
        assertEquals(200, bySeal.getStatusCode().value());
        assertTrue(bySeal.getBody().contains("过滤条件-封签号"));
        assertTrue(bySeal.getBody().contains(c.sealPrefix + "2"));

        // 导出逐次写审计流水（至少 2 次）
        long exportAudits = auditMapper.selectCount(new QueryWrapper<TraceOpAudit>()
                .eq("action", CcConstants.ACT_TRACE_EXPORT).eq("batch_id", c.batchId));
        assertTrue(exportAudits >= 2, "每次导出都应留审计，实际=" + exportAudits);
        TraceOpAudit first = auditMapper.selectList(new QueryWrapper<TraceOpAudit>()
                .eq("action", CcConstants.ACT_TRACE_EXPORT).eq("batch_id", c.batchId)
                .orderByAsc("id").last("LIMIT 1")).get(0);
        assertTrue(first.getFilterJson().contains("dataCutoffTime"), "审计须记录数据截止时间");
        assertTrue(first.getFilterJson().contains(c.batchNo), "审计须记录过滤条件");
        assertEquals("qa", first.getOperatorName());
    }

    // ====================================================================
    // 8) Shiro 权限：401 未认证 / 403 无权限 / 200
    // ====================================================================
    @Test
    void shiroPermissions_401_403_200() throws Exception {
        Chain c = buildDispatchedChain();

        // 未认证 401
        assertEquals(401, http.getForEntity(
                "http://localhost:" + port + BASE + "/trace/graph/batch/" + c.batchId, String.class)
                .getStatusCode().value());

        // 设备网关无任何追溯权限：查看 403
        assertEquals(403, http.withBasicAuth("device", "device123").getForEntity(
                "http://localhost:" + port + BASE + "/trace/graph/batch/" + c.batchId, String.class)
                .getStatusCode().value());

        // 承运岗可查看（200）但不能发布/导出/授权（403）
        assertEquals(200, http.withBasicAuth("carrier", "carrier123").getForEntity(
                "http://localhost:" + port + BASE + "/trace/graph/batch/" + c.batchId, String.class)
                .getStatusCode().value());
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Object> publishEntity = new HttpEntity<>(
                publishBody(c.batchId, "SHIP", null, "perm-" + c.nano, null), h);
        assertEquals(403, http.withBasicAuth("carrier", "carrier123").postForEntity(
                "http://localhost:" + port + BASE + "/trace/nodes/publish", publishEntity, String.class)
                .getStatusCode().value());
        assertEquals(403, http.withBasicAuth("carrier", "carrier123").getForEntity(
                "http://localhost:" + port + BASE + "/trace/export?batchId=" + c.batchId, String.class)
                .getStatusCode().value());
        HttpHeaders gh = new HttpHeaders();
        gh.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Object> grantEntity = new HttpEntity<>(
                map("username", "carrier", "orgName", "X", "contactVisible", false), gh);
        assertEquals(403, http.withBasicAuth("dispatcher", "dispatcher123").postForEntity(
                "http://localhost:" + port + BASE + "/trace/grants", grantEntity, String.class)
                .getStatusCode().value());

        // 管理员授权维护 200；QA 导出 200
        assertEquals(200, http.withBasicAuth("admin", "admin123").postForEntity(
                "http://localhost:" + port + BASE + "/trace/grants", grantEntity, String.class)
                .getStatusCode().value());
        assertEquals(200, http.withBasicAuth("qa", "qa123").getForEntity(
                "http://localhost:" + port + BASE + "/trace/export?batchId=" + c.batchId, String.class)
                .getStatusCode().value());
        // 页面 200
        assertEquals(200, http.withBasicAuth("dispatcher", "dispatcher123").getForEntity(
                "http://localhost:" + port + "/ui/cold-chain/traces", String.class)
                .getStatusCode().value());
    }

    // ---------------- 全链路建样 ----------------

    private static final class Chain {
        long nano;
        long drugId;
        long orderId;
        long vehicleId;
        long batchId;
        String batchNo;
        String approvalNo;
        String carrierName;
        String sealPrefix;
    }

    /** 建档→订单（采购节点）→车辆/探头→派车（仓储节点），未发运 */
    private Chain buildDispatchedChain() {
        return buildDispatchedChain(10);
    }

    private Chain buildDispatchedChain(int shipQty) {
        long nano = System.nanoTime();
        DrugCreateRequest dc = new DrugCreateRequest();
        dc.setDrugName("V13追溯药品-" + nano);
        dc.setApprovalNo("国药准字V13-" + Math.abs(nano % 1000000));
        dc.setTempMin(new BigDecimal("2.00"));
        dc.setTempMax(new BigDecimal("8.00"));
        dc.setPackageSpec("1支/盒");
        Long drugId = drugService.create(dc).getId();

        OrderCreateRequest oc = new OrderCreateRequest();
        oc.setDrugId(drugId);
        oc.setReceiverOrg("V13验收医院");
        oc.setSenderOrg("V13中心药库-" + nano);
        oc.setShipQty(shipQty);
        Long orderId = orderService.create(oc).getId();

        Long vehicleId = createVehicleWithProbe(nano);

        DispatchRequest dr = new DispatchRequest();
        dr.setOrderId(orderId);
        dr.setVehicleId(vehicleId);
        Long batchId = batchService.dispatch(dr).getId();

        Chain c = new Chain();
        c.nano = nano;
        c.drugId = drugId;
        c.orderId = orderId;
        c.vehicleId = vehicleId;
        c.batchId = batchId;
        c.batchNo = batchService.requireBatch(batchId).getBatchNo();
        c.approvalNo = dc.getApprovalNo();
        c.carrierName = "V13验收物流-" + Math.abs(nano % 100000);
        return c;
    }

    /** 派车→发运→到仓→拆单（一箱一签）→逐签核验→提交（中转节点）→签收（签收节点）→退货登记（退货节点） */
    private Chain buildSignedChainWithTransit(int shipQty, int boxQty) throws Exception {
        Chain c = buildDispatchedChain(shipQty);
        int qty = shipQty;

        JsonNode ship = postAs("dispatcher", "dispatcher123", "/batches/" + c.batchId + "/ship",
                new LinkedHashMap<>());
        assertTrue(ship.path("success").asBoolean(), ship.toString());

        postAs("dispatcher", "dispatcher123", "/handovers/arrive",
                map("batchId", c.batchId, "arrivedBoxQty", qty, "warehouseName", "华东中转仓"));

        c.sealPrefix = "SF" + Math.abs(c.nano % 1000000) + "-";
        List<String> seals = new ArrayList<>();
        for (int i = 1; i <= qty; i++) {
            seals.add(c.sealPrefix + i);
        }
        JsonNode ho = postAs("dispatcher", "dispatcher123", "/handovers",
                map("batchId", c.batchId, "warehouseName", "华东中转仓", "boxQty", qty,
                        "handoverTemp", 5.20, "receiverName", "张仓",
                        "receiverOrg", "华东中转仓收货组", "sealNos", seals));
        assertTrue(ho.path("success").asBoolean(), ho.toString());
        long handoverId = ho.path("data").path("order").path("id").asLong();

        JsonNode sealsResp = getAs("dispatcher", "dispatcher123", "/handovers/" + handoverId);
        for (JsonNode s : sealsResp.path("seals")) {
            JsonNode v = postAs("dispatcher", "dispatcher123", "/handovers/seals/verify",
                    map("sealId", s.path("id").asLong(), "verifyNote", "封签完好、温度达标"));
            assertTrue(v.path("success").asBoolean(), v.toString());
        }
        JsonNode submit = postAs("dispatcher", "dispatcher123",
                "/handovers/" + handoverId + "/submit", new LinkedHashMap<>());
        assertTrue(submit.path("success").asBoolean(), submit.toString());

        JsonNode sign = postAs("dispatcher", "dispatcher123", "/batches/sign",
                map("batchId", c.batchId, "signBy", "院药库验收员",
                        "receivedApprovalNo", c.approvalNo, "receivedPackageSpec", "1支/盒",
                        "confirmQty", qty));
        assertTrue(sign.path("success").asBoolean(), sign.toString());

        JsonNode ret = postAs("dispatcher", "dispatcher123", "/returns",
                map("sourceBatchId", c.batchId, "reasonType", "TEMP_EXCURSION",
                        "reasonDetail", "途中短时越限，整批退回复检", "returnQty", qty));
        assertTrue(ret.path("success").asBoolean(), ret.toString());
        return c;
    }

    private Long createVehicleWithProbe(long nano) {
        VehicleCreateRequest vr = new VehicleCreateRequest();
        vr.setPlateNo("测B·追" + Math.abs(nano % 1000000));
        vr.setCarrierName("V13验收物流-" + Math.abs(nano % 100000));
        vr.setDriverName("王司机");
        vr.setDriverPhone("13900000001");
        Long vehicleId = vehicleService.create(vr).getId();

        ProbeCreateRequest pc = new ProbeCreateRequest();
        pc.setSerialNo("SN-V13-" + nano);
        pc.setModel("TP-200");
        pc.setRangeMin(new BigDecimal("-40.00"));
        pc.setRangeMax(new BigDecimal("60.00"));
        pc.setCalibrateOrg("华东计量院");
        pc.setValidUntil(LocalDateTime.now().plusYears(1));
        pc.setCertNo("CERT-V13-" + nano);
        pc.setCalibrateDate(LocalDateTime.now().minusDays(1));
        pc.setCertFileName(nano + ".txt");
        pc.setCertFileContent("V13校准证书-" + nano);
        long probeId = probeService.createProbe(pc).getId();

        ProbeBindRequest pb = new ProbeBindRequest();
        pb.setRequestId("v13bind-" + nano);
        pb.setVehicleId(vehicleId);
        pb.setProbeId(probeId);
        probeService.bindIdempotent(pb);
        return vehicleId;
    }

    private TraceNodePublishRequest manualBody(long batchId, String nodeType, String bizKey,
                                               String requestId, String org, String person, String phone) {
        TraceNodePublishRequest req = new TraceNodePublishRequest();
        req.setRequestId(requestId);
        req.setBatchId(batchId);
        req.setNodeType(nodeType);
        req.setBizKey(bizKey);
        req.setOrgName(org);
        req.setContactName(person);
        req.setContactPhone(phone);
        req.setRemark("并发验收-" + requestId);
        return req;
    }

    // ---------------- HTTP 辅助 ----------------

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private Map<String, Object> publishBody(long batchId, String nodeType, String bizKey,
                                            String requestId, String remark) {
        Map<String, Object> m = map("requestId", requestId, "batchId", batchId, "nodeType", nodeType);
        if (bizKey != null) { m.put("bizKey", bizKey); }
        if (remark != null) { m.put("remark", remark); }
        return m;
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonNode postAs(String user, String pass, String path, Object body) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String resp = http.withBasicAuth(user, pass).postForObject(
                    "http://localhost:" + port + BASE + path, new HttpEntity<>(body, headers), String.class);
            return M.readTree(resp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonNode getAs(String user, String pass, String path) throws Exception {
        String resp = http.withBasicAuth(user, pass).getForObject(
                "http://localhost:" + port + BASE + path, String.class);
        JsonNode envelope = M.readTree(resp);
        assertTrue(envelope.path("success").asBoolean(), "GET 应成功: " + resp);
        return envelope.path("data");
    }

    private ResponseEntity<String> getRaw(String user, String pass, String path) {
        return http.withBasicAuth(user, pass).getForEntity(
                "http://localhost:" + port + BASE + path, String.class);
    }

    private static final class JsonNodeLike {
        final Long nodeId;
        final String operator;

        JsonNodeLike(Long nodeId, String operator) {
            this.nodeId = nodeId;
            this.operator = operator;
        }
    }
}
