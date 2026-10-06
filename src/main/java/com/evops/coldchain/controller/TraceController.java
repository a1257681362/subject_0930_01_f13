package com.evops.coldchain.controller;

import com.evops.coldchain.dto.TraceGrantRequest;
import com.evops.coldchain.dto.TraceGrantRevokeRequest;
import com.evops.coldchain.dto.TraceNodePublishRequest;
import com.evops.coldchain.dto.TraceSupplementRequest;
import com.evops.coldchain.entity.TraceNode;
import com.evops.coldchain.entity.TraceOpAudit;
import com.evops.coldchain.entity.TraceOrgGrant;
import com.evops.coldchain.mapper.TraceNodeMapper;
import com.evops.coldchain.mapper.TraceOpAuditMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.service.TraceService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
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
 * 六节点链路（采购/仓储/发运/中转/签收/退货）+ 状态快照；按批次或封签反查；
 * 机构与联系方式按 t_cc_trace_org_grant 授权掩码；导出 CSV 标明数据截止时间与过滤条件。
 */
@RestController
@RequestMapping("/api/cold-chain/trace")
public class TraceController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Resource
    private TraceService traceService;
    @Resource
    private TraceNodeMapper nodeMapper;
    @Resource
    private TraceOpAuditMapper auditMapper;

    /** 手工发布追溯节点 */
    @PostMapping("/nodes/publish")
    public ApiResponse<Map<String, Object>> publish(@Valid @RequestBody TraceNodePublishRequest req) {
        return ApiResponse.ok(traceService.publishManual(req, false));
    }

    /** 漏记节点补建（source=BACKFILL，可填历史发生时间；仍是不可变快照） */
    @PostMapping("/nodes/backfill")
    public ApiResponse<Map<String, Object>> backfill(@Valid @RequestBody TraceNodePublishRequest req) {
        return ApiResponse.ok(traceService.publishManual(req, true));
    }

    /** 已发布节点追加补充（NOTE/FILE/CORRECTION_REF；不改变已发布快照） */
    @PostMapping("/nodes/supplement")
    public ApiResponse<Map<String, Object>> supplement(@Valid @RequestBody TraceSupplementRequest req) {
        return ApiResponse.ok(traceService.supplement(req));
    }

    /** 按批次ID查看追溯图 */
    @GetMapping("/graph/batch/{batchId}")
    public ApiResponse<Map<String, Object>> graphByBatch(@PathVariable("batchId") Long batchId,
                                                         @RequestParam(value = "nodeType", required = false) String nodeType,
                                                         @RequestParam(value = "org", required = false) String org) {
        return ApiResponse.ok(traceService.graphByBatch(batchId, nodeType, org));
    }

    /** 按批次号反查追溯链路 */
    @GetMapping("/graph/by-batch-no")
    public ApiResponse<Map<String, Object>> graphByBatchNo(@RequestParam("batchNo") String batchNo,
                                                           @RequestParam(value = "nodeType", required = false) String nodeType,
                                                           @RequestParam(value = "org", required = false) String org) {
        return ApiResponse.ok(traceService.graphByBatchNo(batchNo, nodeType, org));
    }

    /** 按封签号反查：命中封签锚点 -> 所属节点 -> 整条批次链路 */
    @GetMapping("/graph/by-seal")
    public ApiResponse<Map<String, Object>> graphBySeal(@RequestParam("sealNo") String sealNo,
                                                        @RequestParam(value = "nodeType", required = false) String nodeType,
                                                        @RequestParam(value = "org", required = false) String org) {
        return ApiResponse.ok(traceService.graphBySealNo(sealNo, nodeType, org));
    }

    /** 追溯节点详情（含封签锚点与补充记录，授权掩码同追溯图口径） */
    @GetMapping("/nodes/{id}")
    public ApiResponse<Map<String, Object>> nodeDetail(@PathVariable("id") Long id) {
        return ApiResponse.ok(traceService.nodeViewById(id));
    }

    /** 并发仲裁/导出/授权审计流水（按批次可选） */
    @GetMapping("/audits")
    public ApiResponse<List<TraceOpAudit>> audits(@RequestParam(value = "batchId", required = false) Long batchId) {
        QueryWrapper<TraceOpAudit> qw = new QueryWrapper<TraceOpAudit>().orderByDesc("id").last("LIMIT 200");
        if (batchId != null) {
            qw.eq("batch_id", batchId);
        }
        return ApiResponse.ok(auditMapper.selectList(qw));
    }

    /** 岗位机构授权列表 */
    @GetMapping("/grants")
    public ApiResponse<List<TraceOrgGrant>> grants(@RequestParam(value = "username", required = false) String username) {
        return ApiResponse.ok(traceService.listGrants(username));
    }

    /** 授权维护（决定岗位可见机构与联系方式明文） */
    @PostMapping("/grants")
    public ApiResponse<TraceOrgGrant> grant(@Valid @RequestBody TraceGrantRequest req) {
        return ApiResponse.ok(traceService.grant(req));
    }

    /** 撤销授权（只置 REVOKED 留痕，已发布节点事实保留） */
    @PostMapping("/grants/revoke")
    public ApiResponse<TraceOrgGrant> revokeGrant(@Valid @RequestBody TraceGrantRevokeRequest req) {
        return ApiResponse.ok(traceService.revokeGrant(req));
    }

    /**
     * 追溯结果 CSV 导出（需 coldchain:trace:export）。
     * 文件头标明数据截止时间与全部过滤条件；机构/联系方式与追溯图同一授权掩码口径；逐次审计。
     */
    @GetMapping("/export")
    @SuppressWarnings("unchecked")
    public void export(@RequestParam(value = "batchId", required = false) Long batchId,
                       @RequestParam(value = "batchNo", required = false) String batchNo,
                       @RequestParam(value = "sealNo", required = false) String sealNo,
                       @RequestParam(value = "nodeType", required = false) String nodeType,
                       @RequestParam(value = "org", required = false) String org,
                       HttpServletResponse response) throws IOException {
        Map<String, Object> graph;
        if (batchId != null) {
            graph = traceService.graphByBatch(batchId, nodeType, org);
        } else if (batchNo != null && !batchNo.trim().isEmpty()) {
            graph = traceService.graphByBatchNo(batchNo, nodeType, org);
        } else if (sealNo != null && !sealNo.trim().isEmpty()) {
            graph = traceService.graphBySealNo(sealNo, nodeType, org);
        } else {
            throw new IllegalArgumentException("导出必须提供 batchId / batchNo / sealNo 之一");
        }
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");
        Map<String, Object> filters = (Map<String, Object>) graph.get("filters");
        LocalDateTime cutoff = (LocalDateTime) graph.get("dataCutoffTime");

        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"trace_graph.csv\"");
        response.getOutputStream().write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        PrintWriter writer = new PrintWriter(new java.io.OutputStreamWriter(response.getOutputStream(),
                java.nio.charset.StandardCharsets.UTF_8));
        writer.println("# 药品批次追溯图导出（冷链 V13）");
        writer.println("# 数据截止时间," + cutoff.format(TS));
        writer.println("# 过滤条件-批次号," + csv(filters.get("batchNo"))
                + ",过滤条件-封签号," + csv(filters.get("sealNo"))
                + ",过滤条件-节点类型," + csv(filters.get("nodeType"))
                + ",过滤条件-机构," + csv(filters.get("orgName"))
                + ",查询岗位," + csv(filters.get("operator")));
        writer.println("# 节点总数," + graph.get("nodeTotal") + ",导出节点数," + graph.get("nodeReturned")
                + ",被过滤条件排除," + graph.get("filteredOut"));
        writer.println("# 说明,未授权机构与联系方式已按岗位授权掩码；节点快照为发布当刻固化值，补录不改变已发布快照");
        writer.println("链路序号,节点号,节点类型,批次号,业务键,业务状态,来源,业务发生时间,发布时间,发布人ID,"
                + "机构(按授权),联系人(按授权),联系电话(按授权),机构授权可见,联系方式明文可见,封签号,备注");
        for (Map<String, Object> n : nodes) {
            List<Map<String, Object>> seals = (List<Map<String, Object>>) n.get("seals");
            String sealText = joinSeals(seals);
            writer.println(String.join(",",
                    csv(n.get("chainSeq")), csv(n.get("nodeNo")),
                    csv(n.get("nodeTypeName")) + "(" + csv(n.get("nodeType")) + ")",
                    csv(n.get("batchNo")), csv(n.get("bizKey")), csv(n.get("bizStatus")),
                    csv(n.get("source")), csv(n.get("occurredTime")), csv(n.get("publishTime")),
                    csv(n.get("publishBy")), csv(n.get("orgName")), csv(n.get("contactName")),
                    csv(n.get("contactPhone")), csv(n.get("authorized")), csv(n.get("contactVisible")),
                    sealText, csv(n.get("remark"))));
        }
        writer.flush();

        Map<String, Object> auditFilters = new java.util.LinkedHashMap<>(filters);
        auditFilters.put("batchId", graph.get("batchId"));
        auditFilters.put("dataCutoffTime", cutoff.format(TS));
        traceService.auditExport(auditFilters, nodes.size());
    }

    private String joinSeals(List<Map<String, Object>> seals) {
        if (seals == null || seals.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> s : seals) {
            if (sb.length() > 0) {
                sb.append(";");
            }
            sb.append(s.get("sealNo")).append("(").append(s.get("sealStatus")).append(")");
        }
        return sb.toString();
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
