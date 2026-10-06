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
import com.evops.coldchain.entity.TraceConcurrentLog;
import com.evops.coldchain.entity.TraceNode;
import com.evops.coldchain.mapper.TraceConcurrentLogMapper;
import com.evops.coldchain.mapper.TraceNodeMapper;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 药品批次追溯图（V13）验收测试。
 *
 * 覆盖：
 * 1. 采购/仓储/发运/中转/签收/退货六节点链路，节点保存当时状态快照；
 * 2. 发布后快照冻结：草稿可修订，已发布节点拒绝修订/补录改写（PUBLISHED_IMMUTABLE），
 *    补录只能另追加节点，原快照 hash 不变；
 * 3. 岗位只能看到授权机构范围内的节点，联系方式按授权明文/脱敏（前3后2）；
 * 4. 按批次号取链、按封签号跨批次反查；
 * 5. 导出 CSV 标明数据截止时间与全部过滤条件，逐次留导出审计；
 * 6. 冷链药品运输同一操作并发：相同 opKey 唯一生效（WINNER），其余拒绝（REJECTED/CONCURRENT），
 *    双方都写并发审计；串行同载荷重放幂等返回；
 * 7. Shiro 401/403/200。
 *
 * 随机端口真实启服务，Shiro HTTP Basic 完整生效；不回滚，验证真实持久化。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TraceChainIntegrationTest {

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

    @Autowired private TraceNodeMapper nodeMapper;
    @Autowired private TraceConcurrentLogMapper concMapper;
    @Autowired private com.evops.coldchain.mapper.TransportBatchMapper batchMapper;

    // ====================================================================
    // 1) 六节点链路 + 2) 发布快照冻结、补录不改已发布快照
    // ====================================================================
    @Test
    void sixNodeChain_snapshotFrozen_backfillCannotChange() throws Exception {
        long batchId = shipNewBatch();
        String occurred = LocalDateTime.now().minusDays(1).withNano(0).toString();

        // 采购节点（调度，授权机构 PURCHASE-001），创建即发布
        JsonNode purchase = createNode("dispatcher", "dispatcher123", nodeBody(batchId,
                "PURCHASE", "PURCHASE-001", "上海医药采购站", "张采购", "13800001111",
                null, occurred, "采购到货 20 盒，批准文号核对一致，冷链不断链", "op-pur-" + batchId, false, null, true));
        assertTrue(purchase.path("success").asBoolean(), purchase.toString());
        long purchaseId = purchase.path("data").path("id").asLong();
        assertEquals("PUBLISHED", purchase.path("data").path("status").asText());
        assertNotNull(purchase.path("data").path("snapshotHash").asText(null));
        String hash1 = nodeMapper.selectById(purchaseId).getSnapshotHash();
        assertNotNull(hash1);
        // 发布快照包含发布当刻批次状态
        assertTrue(nodeMapper.selectById(purchaseId).getSnapshotJson().contains("batchStatusAtPublish"));

        // 其余节点（仓储/发运/中转/签收/退货），覆盖六类
        assertTrue(createNode("dispatcher", "dispatcher123", nodeBody(batchId,
                "WAREHOUSE", "WH-CENTRAL", "中心冷库", "李库管", "13800002222",
                null, occurred, "入库 20 盒，库温 3.1℃", "op-wh-" + batchId, false, null, true))
                .path("success").asBoolean());
        assertTrue(createNode("carrier", "carrier123", nodeBody(batchId,
                "DISPATCH", "WH-CENTRAL", "中心冷库月台", "王司机", "13800003333",
                null, occurred, "装车发运，在途温度 4.0℃", "op-dp-" + batchId, false, null, false))
                .path("success").asBoolean());
        assertTrue(createNode("carrier", "carrier123", nodeBody(batchId,
                "TRANSIT", "TRANSIT-HZ", "杭州中转仓", "赵中转", "13800004444",
                "SEAL-V13-" + batchId, occurred, "到仓 20 箱，封签完好，交接温度 4.2℃",
                "op-tr-" + batchId, false, null, true)).path("success").asBoolean());
        assertTrue(createNode("receiver", "receiver123", nodeBody(batchId,
                "SIGN", "HOSP-HZ-01", "杭州一院药库", "陈验收", "13800005555",
                "SEAL-V13-" + batchId, occurred, "签收入库 20 盒，温区核对合格",
                "op-sg-" + batchId, false, null, true)).path("success").asBoolean());
        assertTrue(createNode("dispatcher", "dispatcher123", nodeBody(batchId,
                "RETURN", "WH-CENTRAL", "中心冷库退货区", "李库管", "13800002222",
                null, occurred, "2 盒破损退回隔离", "op-rt-" + batchId, false, null, true))
                .path("success").asBoolean());

        // 链路按固定次序返回（调度可见 PURCHASE-001/WH-CENTRAL 四个节点，中转/签收机构不可见）
        JsonNode chain = getAs("dispatcher", "dispatcher123", "/trace/batches/" + batchId);
        assertEquals(4, chain.path("nodeCount").asInt());
        assertEquals(2, chain.path("filteredOutByOrgAuth").asInt(), "中转/签收两节点被机构授权过滤");
        assertEquals("PURCHASE", chain.path("nodes").get(0).path("nodeType").asText());
        assertEquals("RETURN", chain.path("nodes").get(3).path("nodeType").asText());
        assertNotNull(chain.path("dataCutoffTime").asText(null), "结果必须带数据截止时间");

        // 已发布采购节点：修订被拒（PUBLISHED_IMMUTABLE），快照 hash 不变
        Map<String, Object> revise = map("statusSnap", "试图改写采购事实", "reviseReason", "篡改尝试");
        JsonNode revResp = postAs("dispatcher", "dispatcher123", "/trace/nodes/" + purchaseId + "/revise", revise);
        assertFalse(revResp.path("success").asBoolean());
        assertTrue(revResp.path("message").asText().contains("已发布"), revResp.toString());
        assertEquals(hash1, nodeMapper.selectById(purchaseId).getSnapshotHash(), "已发布快照不得被改写");
        assertEquals(1, nodeMapper.selectById(purchaseId).getRevision().intValue());

        // 重复发布同样被拒（已发布重复发布是 success=false 的业务失败）
        JsonNode pubBody = postAs("dispatcher", "dispatcher123",
                "/trace/nodes/" + purchaseId + "/publish", map());
        assertFalse(pubBody.path("success").asBoolean());
        assertTrue(pubBody.path("message").asText().contains("已发布"));

        // 草稿节点（发运 DRAFT）可修订，revision 递增
        long dispatchId = nodeMapper.selectOne(new QueryWrapper<TraceNode>()
                .eq("batch_id", batchId).eq("node_type", "DISPATCH")).getId();
        JsonNode revOk = postAs("dispatcher", "dispatcher123", "/trace/nodes/" + dispatchId + "/revise",
                map("statusSnap", "装车发运（修订），在途温度 4.1℃", "reviseReason", "温度笔误修订"));
        assertTrue(revOk.path("success").asBoolean(), revOk.toString());
        assertEquals(2, revOk.path("data").path("revision").asInt());

        // 补录只能另追加节点：再补录一个采购节点（#2），原 #1 快照不变
        JsonNode backfill = createNode("dispatcher", "dispatcher123", nodeBody(batchId,
                "PURCHASE", "PURCHASE-001", "上海医药采购站", "张采购", "13800001111",
                null, LocalDateTime.now().minusDays(2).withNano(0).toString(),
                "补录：采购时附带检验报告编号 JC-2026-001", "op-pur-bf-" + batchId, true,
                "随货单据延迟归档，补登检验报告", true));
        assertTrue(backfill.path("success").asBoolean(), backfill.toString());
        assertTrue(backfill.path("data").path("backfill").asBoolean());
        assertEquals(2, backfill.path("data").path("nodeSeq").asInt(), "同类型第二个节点序号=2");
        assertEquals(hash1, nodeMapper.selectById(purchaseId).getSnapshotHash(), "补录不改变已发布快照");

        // PUBLISHED_IMMUTABLE 拒绝写了并发审计
        long immutableLogs = concMapper.selectCount(new QueryWrapper<TraceConcurrentLog>()
                .eq("node_id", purchaseId).eq("reject_reason", "PUBLISHED_IMMUTABLE"));
        assertTrue(immutableLogs >= 2, "修订/重复发布的被拒操作都应留审计");
    }

    // ====================================================================
    // 3) 岗位机构隔离 + 联系方式明文/脱敏
    // ====================================================================
    @Test
    void orgAuthScoping_andContactMasking() throws Exception {
        long batchId = shipNewBatch();
        String occurred = LocalDateTime.now().minusHours(6).withNano(0).toString();
        assertTrue(createNode("dispatcher", "dispatcher123", nodeBody(batchId,
                "PURCHASE", "PURCHASE-001", "采购站", "张采购", "13912345678",
                null, occurred, "采购状态", "auth-pur-" + batchId, false, null, true))
                .path("success").asBoolean());
        assertTrue(createNode("receiver", "receiver123", nodeBody(batchId,
                "SIGN", "HOSP-HZ-01", "杭州一院", "陈验收", "13787654321",
                null, occurred, "签收状态", "auth-sg-" + batchId, false, null, true))
                .path("success").asBoolean());

        // 承运岗未被授权 PURCHASE-001：登记该机构节点被拒绝
        JsonNode denied = createNode("carrier", "carrier123", nodeBody(batchId,
                "PURCHASE", "PURCHASE-001", "采购站", "x", "13900000000",
                null, occurred, "越权登记", "auth-deny-" + batchId, false, null, true));
        assertFalse(denied.path("success").asBoolean());
        assertTrue(denied.path("message").asText().contains("机构授权隔离"));

        // dispatcher：只见采购节点（1 个），联系方式明文
        JsonNode dispChain = getAs("dispatcher", "dispatcher123", "/trace/batches/" + batchId);
        assertEquals(1, dispChain.path("nodeCount").asInt());
        assertEquals("13912345678", dispChain.path("nodes").get(0).path("contactPhone").asText());
        assertFalse(dispChain.path("nodes").get(0).path("contactPhoneMasked").asBoolean());

        // receiver：只见签收节点
        JsonNode recvChain = getAs("receiver", "receiver123", "/trace/batches/" + batchId);
        assertEquals(1, recvChain.path("nodeCount").asInt());
        assertEquals("SIGN", recvChain.path("nodes").get(0).path("nodeType").asText());

        // qa：全域可见（2 个），但联系方式脱敏（前3后2）
        JsonNode qaChain = getAs("qa", "qa123", "/trace/batches/" + batchId);
        assertEquals(2, qaChain.path("nodeCount").asInt());
        for (JsonNode n : qaChain.path("nodes")) {
            assertTrue(n.path("contactPhoneMasked").asBoolean());
            String phone = n.path("contactPhone").asText();
            assertTrue(phone.startsWith("139") || phone.startsWith("137"), phone);
            assertTrue(phone.endsWith("78") || phone.endsWith("21"), phone);
            assertTrue(phone.contains("*"), "脱敏电话应含星号: " + phone);
        }

        // admin：全域可见且联系方式明文
        JsonNode adminChain = getAs("admin", "admin123", "/trace/batches/" + batchId);
        assertEquals(2, adminChain.path("nodeCount").asInt());
        for (JsonNode n : adminChain.path("nodes")) {
            assertFalse(n.path("contactPhoneMasked").asBoolean());
        }
    }

    // ====================================================================
    // 4) 按封签号反查（跨批次 + 授权过滤）
    // ====================================================================
    @Test
    void reverseLookupBySeal_crossBatch_andAuthFiltered() throws Exception {
        long b1 = shipNewBatch();
        long b2 = shipNewBatch();
        String seal = "SEAL-REV-" + System.nanoTime();
        String occurred = LocalDateTime.now().minusHours(3).withNano(0).toString();
        // 两个批次的中转节点登记同一封签（跨批次流转）
        assertTrue(createNode("carrier", "carrier123", nodeBody(b1,
                "TRANSIT", "TRANSIT-HZ", "杭州中转仓", "赵中转", "13800004444",
                seal, occurred, "中转核验封签", "rev-tr1-" + b1, false, null, true))
                .path("success").asBoolean());
        assertTrue(createNode("carrier", "carrier123", nodeBody(b2,
                "TRANSIT", "TRANSIT-HZ", "杭州中转仓", "赵中转", "13800004444",
                seal, occurred, "中转核验封签", "rev-tr2-" + b2, false, null, true))
                .path("success").asBoolean());

        // 承运岗：按封签命中 2 条，每条带回所属链路
        JsonNode carrier = getAs("carrier", "carrier123", "/trace/by-seal?sealNo=" + seal);
        assertEquals("SEAL", carrier.path("lookupType").asText());
        assertEquals(2, carrier.path("matchCount").asInt());
        assertNotNull(carrier.path("dataCutoffTime").asText(null));
        assertEquals(seal, carrier.path("matches").get(0).path("matchedNode").path("sealNo").asText());
        assertTrue(carrier.path("matches").get(0).path("chain").path("nodeCount").asInt() >= 1);

        // 调度岗未被授权 TRANSIT-HZ：封签反查 0 命中（授权范围外不可见）
        JsonNode dispatcher = getAs("dispatcher", "dispatcher123", "/trace/by-seal?sealNo=" + seal);
        assertEquals(0, dispatcher.path("matchCount").asInt());

        // 按批次号反查链路
        String batchNo = batchMapper.selectById(b1).getBatchNo();
        JsonNode byNo = getAs("admin", "admin123", "/trace/by-batch-no?batchNo=" + batchNo);
        assertEquals(b1, byNo.path("batchId").asLong());
        assertEquals(batchNo, byNo.path("batchNo").asText());
    }

    // ====================================================================
    // 5) 导出：标明数据截止时间与过滤条件 + 联系方式脱敏 + 导出审计
    // ====================================================================
    @Test
    void exportCsv_markedCutoffAndFilters_andAudited() throws Exception {
        long batchId = shipNewBatch();
        String occurred = LocalDateTime.now().minusHours(2).withNano(0).toString();
        assertTrue(createNode("dispatcher", "dispatcher123", nodeBody(batchId,
                "PURCHASE", "PURCHASE-001", "采购站", "张采购", "13912340000",
                null, occurred, "采购导出节点", "exp-pur-" + batchId, false, null, true))
                .path("success").asBoolean());

        ResponseEntity<String> resp = http.withBasicAuth("qa", "qa123").getForEntity(
                "http://localhost:" + port + BASE + "/trace/export?nodeType=PURCHASE&orgCode=PURCHASE-001",
                String.class);
        assertEquals(200, resp.getStatusCode().value());
        String csv = resp.getBody();
        assertNotNull(csv);
        assertTrue(csv.startsWith("\uFEFF"), "CSV 应带 UTF-8 BOM");
        assertTrue(csv.contains("数据截止时间"), "导出必须标明数据截止时间");
        assertTrue(csv.contains("过滤条件-节点类型"), "导出必须标明过滤条件");
        assertTrue(csv.contains("PURCHASE"), "过滤条件值需在头部");
        assertTrue(csv.contains("授权机构范围"), "导出必须标明账号授权范围");
        assertTrue(csv.contains("导出账号"), "导出必须标明导出账号");
        assertTrue(csv.contains("qa"), "导出账号为 qa");
        assertTrue(csv.contains("采购导出节点"));
        // qa 全域但联系方式脱敏：11位电话=前3+6个星号+后2
        assertTrue(csv.contains("139******00"), "qa 导出电话应脱敏: " + csv);
        assertTrue(csv.contains("是"), "contactPhoneMasked 列应为“是”");

        // 逐次导出写审计流水
        long exports = 0;
        List<com.evops.coldchain.entity.OpLog> logs = opLogs();
        for (com.evops.coldchain.entity.OpLog l : logs) {
            if (CcConstants.ACT_TRACE_EXPORT.equals(l.getAction())) {
                exports++;
                assertTrue(l.getSummary().contains("数据截止"));
                assertTrue(l.getSummary().contains("过滤条件"));
            }
        }
        assertTrue(exports >= 1, "导出必须写审计流水");

        // dispatcher 无导出权限
        assertEquals(403, http.withBasicAuth("dispatcher", "dispatcher123")
                .getForEntity("http://localhost:" + port + BASE + "/trace/export", String.class)
                .getStatusCode().value());
    }

    @Autowired private com.evops.coldchain.mapper.OpLogMapper opLogMapper;
    private List<com.evops.coldchain.entity.OpLog> opLogs() {
        return opLogMapper.selectList(new QueryWrapper<com.evops.coldchain.entity.OpLog>().orderByAsc("id"));
    }

    // ====================================================================
    // 6) 同一操作并发：唯一生效 + 落败拒绝 + 双方审计；串行重放幂等
    // ====================================================================
    @Test
    void concurrentSameOp_singleWin_othersRejected_audited() throws Exception {
        long batchId = shipNewBatch();
        String opKey = "conc-op-" + batchId + "-" + System.nanoTime();
        String occurred = LocalDateTime.now().withNano(0).toString();

        int threads = 3;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Thread> pool = new ArrayList<>();
        List<String> results = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    barrier.await();
                    // 三方发起“同一操作”：内容完全一致、仅 opKey 相同（谁先生效由数据库仲裁）
                    Map<String, Object> body = nodeBody(batchId, "WAREHOUSE", "WH-CENTRAL",
                            "中心冷库", "并发库管",
                            "13800009999", null, occurred, "并发同一操作：封签核验确认",
                            opKey, false, null, false);
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    ResponseEntity<String> r = http.withBasicAuth("dispatcher", "dispatcher123")
                            .postForEntity("http://localhost:" + port + BASE + "/trace/nodes",
                                    new HttpEntity<>(body, headers), String.class);
                    JsonNode node = M.readTree(r.getBody());
                    results.add(r.getBody());
                    if (node.path("success").asBoolean()) {
                        ok.incrementAndGet();
                    } else if (node.path("message").asText().contains("CONCURRENT")) {
                        rejected.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, "T" + i);
            pool.add(t);
        }
        for (Thread t : pool) { t.start(); }
        for (Thread t : pool) { t.join(30000); }

        // 恰好一生效、其余被拒
        assertEquals(1, ok.get(), "并发同操作应恰好一生效: " + results);
        assertEquals(threads - 1, rejected.get(), "其余并发请求应被拒绝: " + results);

        // 节点表只有一行（被拒请求不落节点）
        List<TraceNode> nodes = nodeMapper.selectList(new QueryWrapper<TraceNode>().eq("op_key", opKey));
        assertEquals(1, nodes.size());

        // 双方都有审计：1 WINNER + 2 REJECTED(CONCURRENT)，能说明谁生效谁被拒
        List<TraceConcurrentLog> logs = concMapper.selectList(new QueryWrapper<TraceConcurrentLog>()
                .eq("op_key", opKey).orderByAsc("id"));
        long winners = logs.stream().filter(l -> "WINNER".equals(l.getResult())).count();
        long losers = logs.stream().filter(l -> "REJECTED".equals(l.getResult())).count();
        assertEquals(1, winners);
        assertEquals(threads - 1, losers);
        for (TraceConcurrentLog l : logs) {
            assertEquals("dispatcher", l.getOperatorName());
            assertNotNull(l.getRequestSummary());
            if ("REJECTED".equals(l.getResult())) {
                assertEquals("CONCURRENT", l.getRejectReason());
                assertEquals(nodes.get(0).getId(), l.getNodeId(), "被拒审计指向生效节点");
            }
        }

        // 并发结束后串行同载荷重放：默认仍按重复发起拒绝；显式声明幂等重放才回放同一节点
        Map<String, Object> plainRetry = nodeBody(batchId, "WAREHOUSE", "WH-CENTRAL",
                "中心冷库", "并发库管", "13800009999", null, occurred,
                "并发同一操作：封签核验确认", opKey, false, null, false);
        JsonNode plainRetryResp = postAs("dispatcher", "dispatcher123", "/trace/nodes", plainRetry);
        assertFalse(plainRetryResp.path("success").asBoolean(), "未声明重放的重复请求应拒绝");
        assertTrue(plainRetryResp.path("message").asText().contains("CONCURRENT"));

        Map<String, Object> replay = nodeBody(batchId, "WAREHOUSE", "WH-CENTRAL",
                "中心冷库", "并发库管", "13800009999", null, occurred,
                "并发同一操作：封签核验确认", opKey, false, null, false);
        replay.put("idempotentReplay", true);
        JsonNode replayResp = postAs("dispatcher", "dispatcher123", "/trace/nodes", replay);
        assertTrue(replayResp.path("success").asBoolean(), replayResp.toString());
        assertEquals(nodes.get(0).getId(), replayResp.path("data").path("id").asLong());
        // 幂等回放不新增节点；被拒的并发/重复请求也不落节点
        assertEquals(1, nodeMapper.selectCount(new QueryWrapper<TraceNode>().eq("op_key", opKey)).longValue());

        // 串行同键不同载荷：拒绝（DIFFERENT_PAYLOAD）并留审计
        Map<String, Object> different = nodeBody(batchId, "WAREHOUSE", "WH-CENTRAL",
                "中心冷库", "另一个人", "13800000000", null, occurred,
                "同一 opKey 的不同内容", opKey, false, null, false);
        JsonNode diffResp = postAs("dispatcher", "dispatcher123", "/trace/nodes", different);
        assertFalse(diffResp.path("success").asBoolean());
        assertTrue(diffResp.path("message").asText().contains("DIFFERENT_PAYLOAD"));
        TraceConcurrentLog diffLog = concMapper.selectOne(new QueryWrapper<TraceConcurrentLog>()
                .eq("op_key", opKey).eq("reject_reason", "DIFFERENT_PAYLOAD")
                .orderByDesc("id").last("LIMIT 1"));
        assertNotNull(diffLog, "同键不同载荷必须留被拒审计");
        assertEquals("REJECTED", diffLog.getResult());
    }

    // ====================================================================
    // 7) Shiro 权限：401 未认证 / 403 无权限 / 200 正常
    // ====================================================================
    @Test
    void shiroPermissions_401_403_200() {
        long batchId = 0L;
        try {
            batchId = shipNewBatch();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        // 未认证 -> 401
        assertEquals(401, http.getForEntity(
                "http://localhost:" + port + BASE + "/trace/batches/" + batchId, String.class)
                .getStatusCode().value());

        // 设备网关账号无任何追溯权限 -> 403（查看/页面/登记/导出/授权）
        assertEquals(403, http.withBasicAuth("device", "device123")
                .getForEntity("http://localhost:" + port + BASE + "/trace/batches/" + batchId, String.class)
                .getStatusCode().value());
        assertEquals(403, http.withBasicAuth("device", "device123")
                .getForEntity("http://localhost:" + port + "/ui/cold-chain/trace", String.class)
                .getStatusCode().value());
        assertEquals(403, http.withBasicAuth("device", "device123")
                .getForEntity("http://localhost:" + port + BASE + "/trace/export", String.class)
                .getStatusCode().value());

        // qa：可查看/导出，但无节点维护权与授权管理权
        assertEquals(200, http.withBasicAuth("qa", "qa123")
                .getForEntity("http://localhost:" + port + BASE + "/trace/batches/" + batchId, String.class)
                .getStatusCode().value());
        Map<String, Object> body = nodeBody(batchId, "PURCHASE", "PURCHASE-001", "x", "x",
                "1", null, LocalDateTime.now().withNano(0).toString(), "qa 越权建节点",
                "qa-deny-" + System.nanoTime(), false, null, true);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        assertEquals(403, http.withBasicAuth("qa", "qa123")
                .postForEntity("http://localhost:" + port + BASE + "/trace/nodes",
                        new HttpEntity<>(body, headers), String.class).getStatusCode().value());
        assertEquals(403, http.withBasicAuth("qa", "qa123")
                .postForEntity("http://localhost:" + port + BASE + "/trace/auths",
                        new HttpEntity<>(map("username", "qa", "orgCode", "WH-CENTRAL"), headers),
                        String.class).getStatusCode().value());

        // admin 维护授权成功；收回授权不影响已发布节点
        ResponseEntity<String> grant = http.withBasicAuth("admin", "admin123")
                .postForEntity("http://localhost:" + port + BASE + "/trace/auths",
                        new HttpEntity<>(map("username", "carrier", "orgCode", "PURCHASE-001",
                                "contactVisible", false, "grantReason", "临时授权核验"), headers),
                        String.class);
        assertEquals(200, grant.getStatusCode().value());

        // dispatcher 可访问追溯页面
        assertEquals(200, http.withBasicAuth("dispatcher", "dispatcher123")
                .getForEntity("http://localhost:" + port + "/ui/cold-chain/trace", String.class)
                .getStatusCode().value());
    }

    // ---------------- 全链路建样：建档->订单->车辆->持证探头->派车->发运 ----------------

    private long shipNewBatch() {
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
        oc.setShipQty(20);
        Long orderId = orderService.create(oc).getId();

        VehicleCreateRequest vr = new VehicleCreateRequest();
        vr.setPlateNo("测B·溯" + Math.abs(nano % 1000000));
        vr.setCarrierName("V13验收物流-" + Math.abs(nano % 100000));
        vr.setDriverName("王司机");
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

        DispatchRequest dr = new DispatchRequest();
        dr.setOrderId(orderId);
        dr.setVehicleId(vehicleId);
        Long batchId = batchService.dispatch(dr).getId();
        batchService.ship(batchId);
        return batchId;
    }

    private Map<String, Object> nodeBody(long batchId, String nodeType, String orgCode, String orgName,
                                         String contactName, String contactPhone, String sealNo,
                                         String occurredTime, String statusSnap, String opKey,
                                         boolean backfill, String backfillReason, boolean publish) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("opKey", opKey);
        m.put("batchId", batchId);
        m.put("nodeType", nodeType);
        m.put("orgCode", orgCode);
        m.put("orgName", orgName);
        m.put("contactName", contactName);
        m.put("contactPhone", contactPhone);
        if (sealNo != null) { m.put("sealNo", sealNo); }
        m.put("occurredTime", occurredTime);
        m.put("statusSnap", statusSnap);
        m.put("backfill", backfill);
        if (backfillReason != null) { m.put("backfillReason", backfillReason); }
        m.put("publish", publish);
        return m;
    }

    private JsonNode createNode(String user, String pass, Map<String, Object> body) throws Exception {
        return postAs(user, pass, "/trace/nodes", body);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private JsonNode postAs(String user, String pass, String path, Object body) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            String resp = http.withBasicAuth(user, pass)
                    .postForObject("http://localhost:" + port + BASE + path,
                            new HttpEntity<>(body, headers), String.class);
            return M.readTree(resp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ResponseEntity<String> postRaw(String user, String pass, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.withBasicAuth(user, pass)
                .postForEntity("http://localhost:" + port + BASE + path,
                        new HttpEntity<>(body, headers), String.class);
    }

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
