package com.evops.coldchain.controller;

import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.TraceAuthGrantRequest;
import com.evops.coldchain.dto.TraceNodeCreateRequest;
import com.evops.coldchain.dto.TraceNodeReviseRequest;
import com.evops.coldchain.entity.TraceConcurrentLog;
import com.evops.coldchain.entity.TraceNode;
import com.evops.coldchain.entity.TraceOrgAuth;
import com.evops.coldchain.service.OpLogService;
import com.evops.coldchain.service.TraceOrgAuthService;
import com.evops.coldchain.service.TraceService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 药品批次追溯图（V13）。
 *
 * 固定链路：采购 -> 仓储 -> 发运 -> 中转 -> 签收 -> 退货。
 * 节点先草稿后发布，发布快照固化、补录不改已发布快照；
 * 同一操作并发由数据库唯一约束仲裁（一生效一拒绝，双方留审计）；
 * 查询/反查/导出按岗位授权机构收窄、联系方式按授权明文/脱敏；
 * 导出 CSV 标明数据截止时间与全部过滤条件。
 */
@RestController
@RequestMapping("/api/cold-chain/trace")
public class TraceController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TraceService traceService;
    private final TraceOrgAuthService orgAuthService;
    private final OpLogService opLogService;

    public TraceController(TraceService traceService, TraceOrgAuthService orgAuthService,
                           OpLogService opLogService) {
        this.traceService = traceService;
        this.orgAuthService = orgAuthService;
        this.opLogService = opLogService;
    }

    // ---------------- 节点维护 ----------------

    /** 登记节点（同一操作并发：先提交生效，落败拒绝并审计） */
    @PostMapping("/nodes")
    public ApiResponse<TraceNode> createNode(@Valid @RequestBody TraceNodeCreateRequest req) {
        return ApiResponse.ok(traceService.createNode(req));
    }

    /** 修订草稿节点（已发布节点快照冻结，拒绝修订） */
    @PostMapping("/nodes/{id}/revise")
    public ApiResponse<TraceNode> reviseNode(@PathVariable("id") Long id,
                                             @Valid @RequestBody TraceNodeReviseRequest req) {
        return ApiResponse.ok(traceService.revise(id, req));
    }

    /** 发布节点（发布即固化当时状态快照，此后补录不可改） */
    @PostMapping("/nodes/{id}/publish")
    public ApiResponse<TraceNode> publishNode(@PathVariable("id") Long id) {
        return ApiResponse.ok(traceService.publish(id));
    }

    /** 节点详情（按授权机构过滤；无授权 403 语义的业务拒绝） */
    @GetMapping("/nodes/{id}")
    public ApiResponse<Map<String, Object>> nodeDetail(@PathVariable("id") Long id) {
        return ApiResponse.ok(traceService.nodeView(id));
    }

    // ---------------- 追溯图 / 反查 ----------------

    /** 按批次ID取追溯图 */
    @GetMapping("/batches/{batchId}")
    public ApiResponse<Map<String, Object>> chainByBatchId(@PathVariable("batchId") Long batchId) {
        return ApiResponse.ok(traceService.chainByBatchId(batchId));
    }

    /** 按批次号取追溯图 */
    @GetMapping("/by-batch-no")
    public ApiResponse<Map<String, Object>> chainByBatchNo(@RequestParam("batchNo") String batchNo) {
        return ApiResponse.ok(traceService.chainByBatchNo(batchNo));
    }

    /** 按封签号反查（跨批次命中节点及链路） */
    @GetMapping("/by-seal")
    public ApiResponse<Map<String, Object>> reverseBySeal(@RequestParam("sealNo") String sealNo) {
        return ApiResponse.ok(traceService.reverseBySeal(sealNo));
    }

    // ---------------- 并发审计 ----------------

    /** 并发仲裁审计（可按 opKey 过滤） */
    @GetMapping("/concurrent-logs")
    public ApiResponse<List<TraceConcurrentLog>> concurrentLogs(
            @RequestParam(value = "opKey", required = false) String opKey) {
        return ApiResponse.ok(traceService.listConcurrentLogs(opKey));
    }

    // ---------------- 岗位授权 ----------------

    /** 授予岗位账号可见机构范围与联系方式明文口径 */
    @PostMapping("/auths")
    public ApiResponse<TraceOrgAuth> grantAuth(@Valid @RequestBody TraceAuthGrantRequest req) {
        TraceOrgAuth auth = orgAuthService.grant(req.getUsername(), req.getOrgCode().trim(),
                Boolean.TRUE.equals(req.getContactVisible()), req.getGrantReason());
        opLogService.log(CcConstants.ENT_TRACE_AUTH, auth.getId(), null, CcConstants.ACT_TRACE_AUTH_GRANT,
                String.format("授予账号[%s]追溯机构[%s]：联系方式%s。原因=%s",
                        auth.getUsername(), auth.getOrgCode(),
                        Boolean.TRUE.equals(auth.getContactVisible()) ? "明文可见" : "脱敏",
                        auth.getGrantReason() == null ? "" : auth.getGrantReason()));
        return ApiResponse.ok(auth);
    }

    /** 收回岗位账号的某机构授权 */
    @PostMapping("/auths/revoke")
    public ApiResponse<Void> revokeAuth(@RequestParam("username") String username,
                                        @RequestParam("orgCode") String orgCode) {
        TraceOrgAuth auth = orgAuthService.revoke(username, orgCode);
        opLogService.log(CcConstants.ENT_TRACE_AUTH, auth.getId(), null, CcConstants.ACT_TRACE_AUTH_REVOKE,
                String.format("收回账号[%s]对追溯机构[%s]的查看授权（已发布追溯节点不受影响）",
                        username, orgCode));
        return ApiResponse.ok(null);
    }

    /** 查看当前账号的授权范围与联系方式口径 */
    @GetMapping("/auths/mine")
    public ApiResponse<List<TraceOrgAuth>> myAuths() {
        return ApiResponse.ok(orgAuthService.myAuths());
    }

    // ---------------- 导出（标明数据截止时间与过滤条件） ----------------

    /**
     * 追溯节点 CSV 导出（需 coldchain:trace:export）。
     * 文件头标明：数据截止时间（服务器时间）、全部过滤条件、导出账号、联系方式口径；
     * 只导出当前账号授权机构范围内数据；逐次导出写审计流水。
     */
    @GetMapping("/export")
    @SuppressWarnings("unchecked")
    public void export(@RequestParam(value = "batchNo", required = false) String batchNo,
                       @RequestParam(value = "sealNo", required = false) String sealNo,
                       @RequestParam(value = "nodeType", required = false) String nodeType,
                       @RequestParam(value = "orgCode", required = false) String orgCode,
                       HttpServletResponse response) throws IOException {
        Map<String, Object> data = traceService.exportData(batchNo, sealNo, nodeType, orgCode);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("rows");
        Map<String, Object> filter = (Map<String, Object>) data.get("filter");
        LocalDateTime cutoff = (LocalDateTime) data.get("dataCutoffTime");

        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"trace_chain.csv\"");
        response.getOutputStream().write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        PrintWriter writer = new PrintWriter(new java.io.OutputStreamWriter(response.getOutputStream(),
                java.nio.charset.StandardCharsets.UTF_8));

        // —— 导出头部：数据截止时间 + 全部过滤条件 + 账号 + 联系方式口径 ——
        writer.println("# 药品批次追溯图导出（CSV）");
        writer.println("数据截止时间," + csv(cutoff.format(TS)) + "（该时间之后发布/补录的数据不在本文件内）");
        writer.println("过滤条件-批次号," + csv(filter.get("batchNo")));
        writer.println("过滤条件-封签号," + csv(filter.get("sealNo")));
        writer.println("过滤条件-节点类型," + csv(filter.get("nodeType")));
        writer.println("过滤条件-机构代码," + csv(filter.get("orgCode")));
        writer.println("授权机构范围," + csv(String.valueOf(filter.get("authorizedOrgCodes"))));
        writer.println("导出账号," + csv(data.get("username")));
        writer.println("联系方式口径," + "按岗位授权：明文或脱敏（前3后2，中间星号），见contactPhoneMasked列");
        writer.println("因机构授权过滤掉的节点数," + csv(data.get("filteredOutByOrgAuth")));
        writer.println();
        writer.println("批次号,节点编号,节点类型,节点序号,节点状态,封签号,业务发生时间,机构代码,机构名称,"
                + "联系人,联系方式,联系方式是否脱敏,当时状态快照,是否补录,补录原因,修订版本,"
                + "发布时批次状态,快照摘要,发布人,发布时间");
        for (Map<String, Object> r : rows) {
            writer.println(String.join(",",
                    csv(r.get("batchNo")), csv(r.get("nodeNo")),
                    csv(TraceService.nodeTypeName(String.valueOf(r.get("nodeType")))),
                    csv(r.get("nodeSeq")), csv(r.get("status")), csv(r.get("sealNo")),
                    csv(r.get("occurredTime")), csv(r.get("orgCode")), csv(r.get("orgName")),
                    csv(r.get("contactName")), csv(r.get("contactPhone")),
                    csv(Boolean.TRUE.equals(r.get("contactPhoneMasked")) ? "是" : "否"),
                    csv(r.get("statusSnap")),
                    csv(Boolean.TRUE.equals(r.get("backfill")) ? "是" : "否"),
                    csv(r.get("backfillReason")), csv(r.get("revision")),
                    csv(r.get("batchStatusSnap")), csv(r.get("snapshotHash")),
                    csv(r.get("publishedName")), csv(r.get("publishedTime"))));
        }
        writer.flush();

        opLogService.log(CcConstants.ENT_TRACE_NODE, null, null, CcConstants.ACT_TRACE_EXPORT,
                String.format("导出追溯图 CSV：数据截止=%s，过滤条件[批次号=%s, 封签=%s, 节点类型=%s, 机构=%s]，"
                                + "授权范围=%s，导出 %d 条（因机构授权过滤 %d 条）",
                        cutoff.format(TS), filter.get("batchNo"), filter.get("sealNo"),
                        filter.get("nodeType"), filter.get("orgCode"),
                        filter.get("authorizedOrgCodes"), rows.size(),
                        data.get("filteredOutByOrgAuth")));
    }

    private String csv(Object value) {
        if (value == null) {
            return "";
        }
        String s = String.valueOf(value);
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
