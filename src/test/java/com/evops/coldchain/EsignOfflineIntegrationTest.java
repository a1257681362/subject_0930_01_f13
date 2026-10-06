package com.evops.coldchain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.evops.coldchain.dto.DispatchRequest;
import com.evops.coldchain.dto.DrugCreateRequest;
import com.evops.coldchain.dto.OrderCreateRequest;
import com.evops.coldchain.dto.ProbeBindRequest;
import com.evops.coldchain.dto.ProbeCreateRequest;
import com.evops.coldchain.dto.VehicleCreateRequest;
import com.evops.coldchain.entity.SignCompensation;
import com.evops.coldchain.entity.SignReceipt;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.SignCompensationMapper;
import com.evops.coldchain.mapper.SignCorrectionMapper;
import com.evops.coldchain.mapper.SignReceiptMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.coldchain.service.DrugService;
import com.evops.coldchain.service.OrderService;
import com.evops.coldchain.service.ProbeService;
import com.evops.coldchain.service.TransportBatchService;
import com.evops.coldchain.service.VehicleService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 电子签收与离线回执（V12）验收测试。
 *
 * 覆盖：离线草稿保存、恢复网络后按回执编号分次合并上传签名/照片/定位并激活签收、
 * 设备时间与服务器时间并列展示、requestId 幂等重放、同一批次两份不同签收冻结批次并
 * 主管裁决（裁决前签收/结算/撤回停止）、内容一致重复件不冻结、人工修正只追加不覆盖
 * 原始回执、批量同步部分成功时电子签收侧与离线回执侧补偿记录成对彼此对应并随补传闭环。
 *
 * 随机端口真实启服务，Shiro HTTP Basic 完整生效；不回滚，验证真实持久化。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EsignOfflineIntegrationTest {

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
    @Autowired private SignReceiptMapper receiptMapper;
    @Autowired private SignCorrectionMapper correctionMapper;
    @Autowired private SignCompensationMapper compensationMapper;

    // ====================================================================
    // 1) 离线草稿 -> 恢复网络分次合并上传 -> 要素齐全激活签收；双时间并列；幂等重放
    // ====================================================================
    @Test
    void offlineDraft_mergesByReceiptNo_andSignsWithDualTimes() throws Exception {
        long batchId = shipNewBatch(20);
        TransportBatch frozen = batchMapper.selectById(batchId);
        String receiptNo = "RC-OFF-" + System.nanoTime();
        LocalDateTime deviceTime = LocalDateTime.now().minusHours(3).withNano(0);

        // 离线：司机保存签收草稿（只有回执编号/签收人/设备时间/核对字段，无签名/照片/定位）
        Map<String, Object> draft = baseUpload(batchId, receiptNo, "张收货", deviceTime, frozen);
        draft.put("requestId", "up-" + receiptNo + "-1");
        JsonNode d = postAs("carrier", "carrier123", "/esign/receipts", draft);
        assertTrue(d.path("success").asBoolean(), d.toString());
        assertEquals("DRAFT", d.path("data").path("receiptStatus").asText());
        assertNull(batchMapper.selectById(batchId).getSignTime(), "草稿未齐全前批次不得签收");

        // 恢复网络：同一回执编号补传签名 -> 仍 DRAFT（缺照片/定位）
        Map<String, Object> sigOnly = new LinkedHashMap<>();
        sigOnly.put("requestId", "up-" + receiptNo + "-2");
        sigOnly.put("batchId", batchId);
        sigOnly.put("receiptNo", receiptNo);
        sigOnly.put("source", "OFFLINE");
        sigOnly.put("signatureName", "sig.png");
        sigOnly.put("signatureUrl", "https://files/sig/" + receiptNo + ".png");
        JsonNode s = postAs("carrier", "carrier123", "/esign/receipts", sigOnly);
        assertEquals("DRAFT", s.path("data").path("receiptStatus").asText());

        // 再补传照片+定位 -> 要素齐全，自动激活签收
        Map<String, Object> rest = new LinkedHashMap<>();
        rest.put("requestId", "up-" + receiptNo + "-3");
        rest.put("batchId", batchId);
        rest.put("receiptNo", receiptNo);
        rest.put("locationAddress", "杭州市第一人民医院药库");
        rest.put("photos", photoList(photo("p1-" + receiptNo), photo("p2-" + receiptNo)));
        JsonNode r = postAs("carrier", "carrier123", "/esign/receipts", rest);
        assertTrue(r.path("success").asBoolean(), r.toString());
        assertEquals("EFFECTIVE", r.path("data").path("receiptStatus").asText());
        assertTrue(r.path("data").path("activated").asBoolean());

        // 批次据此签收闭环；签收时间=服务器时间，设备时间在回执上并列
        TransportBatch signed = batchMapper.selectById(batchId);
        assertEquals(CcConstants.BATCH_SIGNED, signed.getStatus());
        assertNotNull(signed.getSignTime());
        SignReceipt receipt = receiptMapper.selectOne(
                new QueryWrapper<SignReceipt>().eq("receipt_no", receiptNo));
        assertEquals(deviceTime, receipt.getDeviceTime());
        assertNotNull(receipt.getServerTime());

        // 详情：设备时间/服务器时间/批次签收时间 三栏并列展示
        JsonNode detail = getAs("carrier", "carrier123", "/esign/receipts/" + receipt.getId());
        assertEquals(deviceTime.toString(), detail.path("signTimes").path("deviceTime").asText());
        assertNotNull(detail.path("signTimes").path("serverTime").asText());
        assertNotNull(detail.path("signTimes").path("batchSignTime").asText());
        assertEquals(2, detail.path("photos").size());

        // 幂等：同 requestId 同载荷重放 -> 返回首次结果，不新增行
        JsonNode replay = postAs("carrier", "carrier123", "/esign/receipts", rest);
        assertTrue(replay.path("data").path("replay").asBoolean());
        assertEquals(receipt.getId().longValue(), replay.path("data").path("receiptId").asLong());
        assertEquals(1, receiptMapper.selectCount(
                new QueryWrapper<SignReceipt>().eq("receipt_no", receiptNo)).intValue());

        // 幂等：同 requestId 不同载荷 -> 拒绝
        Map<String, Object> tampered = new LinkedHashMap<>(rest);
        tampered.put("locationAddress", "另一个地址");
        assertStop(postAs("carrier", "carrier123", "/esign/receipts", tampered), "同一requestId不允许");

        // 同一回执编号核心内容不一致（同号不同单）-> 拒绝
        Map<String, Object> conflict = new LinkedHashMap<>(rest);
        conflict.put("requestId", "up-" + receiptNo + "-4");
        conflict.put("signBy", "李冒签");
        assertStop(postAs("carrier", "carrier123", "/esign/receipts", conflict), "同号不同单");
    }

    // ====================================================================
    // 2) 同一批次两份不同签收 -> 冻结批次（签收/结算/撤回停止）-> 主管裁决
    // ====================================================================
    @Test
    void conflictingReceipts_freezeBatch_untilSupervisorAdjudicates() throws Exception {
        long batchId = shipNewBatch(15);
        TransportBatch frozen = batchMapper.selectById(batchId);
        String nano = String.valueOf(System.nanoTime());

        // 第一份签收（完整要素）-> 生效，批次闭环
        Map<String, Object> a = baseUpload(batchId, "RC-A-" + nano, "张收货",
                LocalDateTime.now().minusHours(2).withNano(0), frozen);
        a.put("requestId", "up-A-" + nano);
        a.put("signatureUrl", "https://files/sig/A-" + nano + ".png");
        a.put("locationAddress", "市一院药库");
        a.put("photos", photoList(photo("pa-" + nano)));
        JsonNode ra = postAs("carrier", "carrier123", "/esign/receipts", a);
        assertEquals("EFFECTIVE", ra.path("data").path("receiptStatus").asText());
        assertEquals(CcConstants.BATCH_SIGNED, batchMapper.selectById(batchId).getStatus());

        // 第二份内容不同的签收 -> 批次冻结，待主管裁决
        Map<String, Object> b = baseUpload(batchId, "RC-B-" + nano, "李冒签",
                LocalDateTime.now().minusHours(1).withNano(0), frozen);
        b.put("requestId", "up-B-" + nano);
        b.put("signatureUrl", "https://files/sig/B-" + nano + ".png");
        b.put("locationAddress", "另一地址");
        b.put("photos", photoList(photo("pb-" + nano)));
        JsonNode rb = postAs("carrier", "carrier123", "/esign/receipts", b);
        assertTrue(rb.path("data").path("frozen").asBoolean(), rb.toString());
        TransportBatch frozenBatch = batchMapper.selectById(batchId);
        assertEquals(CcConstants.BATCH_FROZEN, frozenBatch.getStatus());
        assertEquals(CcConstants.BATCH_SIGNED, frozenBatch.getSignFrozenFrom());

        // 冻结期间：签收/结算/撤回全部停止
        assertStop(postAs("dispatcher", "dispatcher123", "/batches/sign",
                signBody(batchId, frozen)), "两份不同签收");
        assertStop(postAs("dispatcher", "dispatcher123", "/batches/settle",
                map("batchId", batchId, "settleRemark", "试结算")), "两份不同签收");
        assertStop(postAs("dispatcher", "dispatcher123", "/batches/revoke",
                map("batchId", batchId, "reason", "试撤回")), "两份不同签收");

        // 非主管（调度）无权裁决：403
        ResponseEntity<String> forbidden = postEntity("dispatcher", "dispatcher123",
                "/esign/receipts/adjudicate",
                map("batchId", batchId, "effectiveReceiptId",
                        rb.path("data").path("receiptId").asLong(), "reason", "尝试裁决"));
        assertEquals(403, forbidden.getStatusCode().value());

        // 主管裁决：维持第一份（A 生效，B 作废但原始记录保留）
        JsonNode adj = postAs("admin", "admin123", "/esign/receipts/adjudicate",
                map("batchId", batchId, "effectiveReceiptId",
                        ra.path("data").path("receiptId").asLong(), "reason", "现场核实A为真实签收"));
        assertTrue(adj.path("success").asBoolean(), adj.toString());
        TransportBatch after = batchMapper.selectById(batchId);
        assertEquals(CcConstants.BATCH_SIGNED, after.getStatus());
        assertNull(after.getSignFrozenFrom());
        assertEquals("张收货", after.getSignBy());

        SignReceipt bReceipt = receiptMapper.selectById(rb.path("data").path("receiptId").asLong());
        assertEquals(CcConstants.RECEIPT_REJECTED, bReceipt.getStatus());
        assertEquals("李冒签", bReceipt.getSignBy(), "作废回执原始要素必须保留");
        assertNotNull(bReceipt.getAdjudicateNote());
        // 裁决作废的回执不能再合并上传
        Map<String, Object> again = new LinkedHashMap<>(b);
        again.put("requestId", "up-B2-" + nano);
        assertStop(postAs("carrier", "carrier123", "/esign/receipts", again), "裁决作废");

        // 台账：esign 段冻结状态解除，两份回执都可追溯
        JsonNode ledger = getAs("dispatcher", "dispatcher123", "/batches/" + batchId + "/ledger");
        assertFalse(ledger.path("esign").path("frozen").asBoolean());
        assertEquals(2, ledger.path("esign").path("receipts").size());
    }

    // ====================================================================
    // 3) 内容一致的重复回执 -> DUPLICATE，不触发冻结
    // ====================================================================
    @Test
    void identicalReceipt_markedDuplicate_withoutFreezing() throws Exception {
        long batchId = shipNewBatch(10);
        TransportBatch frozen = batchMapper.selectById(batchId);
        String nano = String.valueOf(System.nanoTime());
        LocalDateTime deviceTime = LocalDateTime.now().minusHours(1).withNano(0);

        Map<String, Object> a = baseUpload(batchId, "RC-D1-" + nano, "张收货", deviceTime, frozen);
        a.put("requestId", "up-D1-" + nano);
        a.put("signatureUrl", "https://files/sig/D-" + nano + ".png");
        a.put("locationAddress", "市二院药库");
        a.put("photos", photoList(photo("pd1-" + nano)));
        assertEquals("EFFECTIVE", postAs("carrier", "carrier123", "/esign/receipts", a)
                .path("data").path("receiptStatus").asText());

        // 另一回执编号、核心内容完全一致（司机重复建档）-> DUPLICATE，批次不冻结
        Map<String, Object> dup = baseUpload(batchId, "RC-D2-" + nano, "张收货", deviceTime, frozen);
        dup.put("requestId", "up-D2-" + nano);
        dup.put("signatureUrl", "https://files/sig/D-" + nano + ".png");
        dup.put("locationAddress", "市二院药库");
        dup.put("photos", photoList(photo("pd2-" + nano)));
        JsonNode rd = postAs("carrier", "carrier123", "/esign/receipts", dup);
        assertEquals("DUPLICATE", rd.path("data").path("receiptStatus").asText());
        assertTrue(rd.path("data").path("duplicate").asBoolean());
        assertEquals(CcConstants.BATCH_SIGNED, batchMapper.selectById(batchId).getStatus());
    }

    // ====================================================================
    // 4) 人工修正只追加修正记录，绝不覆盖原始回执
    // ====================================================================
    @Test
    void manualCorrection_appendsOnly_neverOverwritesOriginalReceipt() throws Exception {
        long batchId = shipNewBatch(8);
        TransportBatch frozen = batchMapper.selectById(batchId);
        String nano = String.valueOf(System.nanoTime());

        Map<String, Object> a = baseUpload(batchId, "RC-C-" + nano, "张收货",
                LocalDateTime.now().withNano(0), frozen);
        a.put("requestId", "up-C-" + nano);
        a.put("signatureUrl", "https://files/sig/C-" + nano + ".png");
        a.put("locationAddress", "旧地址");
        a.put("photos", photoList(photo("pc-" + nano)));
        JsonNode ra = postAs("carrier", "carrier123", "/esign/receipts", a);
        long receiptId = ra.path("data").path("receiptId").asLong();

        // 两次修正：签收人 + 地址
        assertTrue(postAs("dispatcher", "dispatcher123", "/esign/receipts/corrections",
                map("receiptId", receiptId, "fieldName", "signBy",
                        "newValue", "张收货（本人补签）", "reason", "签收人姓名补全"))
                .path("success").asBoolean());
        assertTrue(postAs("dispatcher", "dispatcher123", "/esign/receipts/corrections",
                map("receiptId", receiptId, "fieldName", "locationAddress",
                        "newValue", "市三院新址药库", "reason", "地址笔误更正"))
                .path("success").asBoolean());

        // 原始回执一行未动
        SignReceipt original = receiptMapper.selectById(receiptId);
        assertEquals("张收货", original.getSignBy());
        assertEquals("旧地址", original.getLocationAddress());

        // 修正记录只追加；有效值=原值+最新修正
        assertEquals(2, correctionMapper.selectCount(
                new QueryWrapper<com.evops.coldchain.entity.SignCorrection>()
                        .eq("receipt_id", receiptId)).intValue());
        JsonNode detail = getAs("dispatcher", "dispatcher123", "/esign/receipts/" + receiptId);
        assertEquals("张收货（本人补签）", detail.path("effectiveValues").path("signBy").asText());
        assertEquals("市三院新址药库", detail.path("effectiveValues").path("locationAddress").asText());
        assertEquals("张收货", detail.path("receipt").path("signBy").asText(), "原始回执不得被覆盖");
        assertEquals(2, detail.path("corrections").size());

        // 设备时间等原始要素不允许修正
        assertStop(postAs("dispatcher", "dispatcher123", "/esign/receipts/corrections",
                map("receiptId", receiptId, "fieldName", "deviceTime",
                        "newValue", "2026-01-01", "reason", "试图改时间")), "不允许修正字段");
    }

    // ====================================================================
    // 5) 批量同步部分成功：电子签收侧与离线回执侧补偿记录成对彼此对应，补传后一并闭环
    // ====================================================================
    @Test
    void partialSync_pairedCompensations_resolveTogetherAfterReupload() throws Exception {
        long batch1 = shipNewBatch(6);
        long batch2 = shipNewBatch(9);
        TransportBatch frozen2 = batchMapper.selectById(batch2);
        String nano = String.valueOf(System.nanoTime());
        String okNo = "RC-S1-" + nano;
        String failNo = "RC-S2-" + nano;
        String partialNo = "RC-S3-" + nano;

        // 同步三张回执：一张完整成功、一张批次不存在（失败）、一张照片要素缺失（部分成功）
        Map<String, Object> ok = baseUpload(batch1, okNo, "张收货",
                LocalDateTime.now().minusHours(2).withNano(0), batchMapper.selectById(batch1));
        ok.put("signatureUrl", "https://files/sig/S1-" + nano + ".png");
        ok.put("locationAddress", "市一院");
        ok.put("photos", photoList(photo("ps1-" + nano)));

        Map<String, Object> bad = baseUpload(999999999L, failNo, "张收货",
                LocalDateTime.now().minusHours(1).withNano(0), frozen2);

        Map<String, Object> partial = baseUpload(batch2, partialNo, "王收货",
                LocalDateTime.now().minusMinutes(30).withNano(0), frozen2);
        partial.put("signatureUrl", "https://files/sig/S3-" + nano + ".png");
        partial.put("locationAddress", "市二院");
        partial.put("photos", photoList(photo("ps3-" + nano), badPhoto()));

        Map<String, Object> syncReq = new LinkedHashMap<>();
        syncReq.put("requestId", "sync-" + nano);
        syncReq.put("receipts", list(ok, bad, partial));
        JsonNode sync = postAs("carrier", "carrier123", "/esign/sync", syncReq);
        assertTrue(sync.path("success").asBoolean(), sync.toString());
        assertEquals("PARTIAL", sync.path("data").path("result").asText());
        assertEquals(1, sync.path("data").path("succeeded").asInt());
        assertEquals(1, sync.path("data").path("partial").asInt());
        assertEquals(1, sync.path("data").path("failed").asInt());

        // 补偿记录：失败回执 + 部分成功回执 各一对（电子签收侧↔离线回执侧，pair_id 互指）
        List<SignCompensation> failComps = compensationMapper.selectList(
                new QueryWrapper<SignCompensation>().eq("receipt_no", failNo));
        assertEquals(2, failComps.size());
        assertPaired(failComps);
        List<SignCompensation> partialComps = compensationMapper.selectList(
                new QueryWrapper<SignCompensation>().eq("receipt_no", partialNo));
        assertEquals(2, partialComps.size());
        assertPaired(partialComps);
        assertEquals(CcConstants.COMP_OPEN, failComps.get(0).getStatus());

        // 同步幂等重放：同 requestId 同载荷 -> 首次结果，补偿不重复开立
        JsonNode replay = postAs("carrier", "carrier123", "/esign/sync", syncReq);
        assertTrue(replay.path("data").path("replay").asBoolean());
        assertEquals(2, compensationMapper.selectCount(
                new QueryWrapper<SignCompensation>().eq("receipt_no", failNo)).intValue());

        // 补传：失败回执按正确批次完整上传 -> 成功，两侧补偿一并闭环
        Map<String, Object> fix = baseUpload(batch2, failNo, "张收货",
                LocalDateTime.now().minusHours(1).withNano(0), frozen2);
        fix.put("requestId", "up-fix-" + nano);
        fix.put("signatureUrl", "https://files/sig/S2-" + nano + ".png");
        fix.put("locationAddress", "市二院");
        fix.put("photos", photoList(photo("ps2-" + nano)));
        JsonNode rf = postAs("carrier", "carrier123", "/esign/receipts", fix);
        assertTrue(rf.path("success").asBoolean(), rf.toString());
        List<SignCompensation> resolved = compensationMapper.selectList(
                new QueryWrapper<SignCompensation>().eq("receipt_no", failNo).orderByAsc("id"));
        assertEquals(CcConstants.COMP_RESOLVED, resolved.get(0).getStatus());
        assertEquals(CcConstants.COMP_RESOLVED, resolved.get(1).getStatus());
        assertNotNull(resolved.get(0).getResolveTime());
        assertNotNull(resolved.get(1).getResolveTime());

        // 台账补偿接口：电子签收侧与离线回执侧彼此对应可查
        JsonNode comps = getAs("dispatcher", "dispatcher123",
                "/esign/compensations?batchId=" + batch2);
        boolean sawEsign = false, sawOffline = false;
        for (JsonNode c : comps) {
            if (CcConstants.COMP_ESIGN.equals(c.path("side").asText())) {
                sawEsign = true;
            }
            if (CcConstants.COMP_OFFLINE.equals(c.path("side").asText())) {
                sawOffline = true;
            }
            assertTrue(c.path("pairId").asLong() > 0);
        }
        assertTrue(sawEsign && sawOffline, "电子签收侧与离线回执侧补偿记录必须彼此对应");
    }

    // ---------------- 全链路建样：建档->订单->车辆->持证探头->派车->发运 ----------------

    private long shipNewBatch(int shipQty) {
        long nano = System.nanoTime();

        DrugCreateRequest dc = new DrugCreateRequest();
        dc.setDrugName("V12验收药品-" + nano);
        dc.setApprovalNo("国药准字V12-" + Math.abs(nano % 1000000));
        dc.setTempMin(new BigDecimal("2.00"));
        dc.setTempMax(new BigDecimal("8.00"));
        dc.setPackageSpec("1支/盒");
        Long drugId = drugService.create(dc).getId();

        OrderCreateRequest oc = new OrderCreateRequest();
        oc.setDrugId(drugId);
        oc.setReceiverOrg("V12验收医院");
        oc.setShipQty(shipQty);
        Long orderId = orderService.create(oc).getId();

        VehicleCreateRequest vr = new VehicleCreateRequest();
        vr.setPlateNo("测C·冷" + Math.abs(nano % 1000000));
        vr.setCarrierName("V12验收物流-" + Math.abs(nano % 100000));
        vr.setDriverName("王司机");
        Long vehicleId = vehicleService.create(vr).getId();

        ProbeCreateRequest pc = new ProbeCreateRequest();
        pc.setSerialNo("SN-V12-" + nano);
        pc.setModel("TP-200");
        pc.setRangeMin(new BigDecimal("-40.00"));
        pc.setRangeMax(new BigDecimal("60.00"));
        pc.setCalibrateOrg("华东计量院");
        pc.setValidUntil(LocalDateTime.now().plusYears(1));
        pc.setCertNo("CERT-V12-" + nano);
        pc.setCalibrateDate(LocalDateTime.now().minusDays(1));
        pc.setCertFileName(nano + ".txt");
        pc.setCertFileContent("V12校准证书-" + nano);
        long probeId = probeService.createProbe(pc).getId();

        ProbeBindRequest pb = new ProbeBindRequest();
        pb.setRequestId("v12bind-" + nano);
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

    // ---------------- 请求体/断言/HTTP 辅助 ----------------

    /** 离线草稿基础要素：回执编号/签收人/设备时间/核对字段（无签名/照片/定位） */
    private Map<String, Object> baseUpload(long batchId, String receiptNo, String signBy,
                                           LocalDateTime deviceTime, TransportBatch frozen) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("batchId", batchId);
        m.put("receiptNo", receiptNo);
        m.put("source", "OFFLINE");
        m.put("signBy", signBy);
        m.put("receivedApprovalNo", frozen.getApprovalNoSnap());
        m.put("receivedPackageSpec", frozen.getPackageSpecSnap());
        m.put("confirmQty", frozen.getShipQty());
        m.put("deviceTime", deviceTime.toString());
        return m;
    }

    private Map<String, Object> photo(String name) {
        return map("photoName", name, "photoUrl", "https://files/photo/" + name + ".jpg");
    }

    private Map<String, Object> badPhoto() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("photoName", "缺地址照片");
        return m;
    }

    private List<Map<String, Object>> photoList(Map<String, Object>... photos) {
        List<Map<String, Object>> l = new ArrayList<>();
        for (Map<String, Object> p : photos) {
            l.add(p);
        }
        return l;
    }

    private List<Map<String, Object>> list(Map<String, Object>... items) {
        List<Map<String, Object>> l = new ArrayList<>();
        for (Map<String, Object> i : items) {
            l.add(i);
        }
        return l;
    }

    private Map<String, Object> signBody(long batchId, TransportBatch frozen) {
        return map("batchId", batchId,
                "signBy", "院药库",
                "receivedApprovalNo", frozen.getApprovalNoSnap(),
                "receivedPackageSpec", frozen.getPackageSpecSnap(),
                "confirmQty", frozen.getShipQty());
    }

    private void assertPaired(List<SignCompensation> pair) {
        SignCompensation esign = pair.get(0).getSide().equals(CcConstants.COMP_ESIGN)
                ? pair.get(0) : pair.get(1);
        SignCompensation offline = pair.get(0).getSide().equals(CcConstants.COMP_OFFLINE)
                ? pair.get(0) : pair.get(1);
        assertEquals(offline.getId(), esign.getPairId(), "电子签收侧补偿必须互指离线回执侧");
        assertEquals(esign.getId(), offline.getPairId(), "离线回执侧补偿必须互指电子签收侧");
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private void assertStop(JsonNode resp, String keyword) {
        assertFalse(resp.path("success").asBoolean(), "应被拦截: " + resp);
        assertTrue(resp.path("message").asText().contains(keyword),
                "停点说明应包含[" + keyword + "]，实际：" + resp.path("message").asText());
    }

    private JsonNode postAs(String user, String pass, String path, Object body) {
        try {
            String resp = http.withBasicAuth(user, pass)
                    .postForObject("http://localhost:" + port + BASE + path, jsonEntity(body), String.class);
            return M.readTree(resp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ResponseEntity<String> postEntity(String user, String pass, String path, Object body) {
        return http.withBasicAuth(user, pass)
                .postForEntity("http://localhost:" + port + BASE + path, jsonEntity(body), String.class);
    }

    private HttpEntity<Object> jsonEntity(Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
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
