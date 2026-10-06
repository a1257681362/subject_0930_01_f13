package com.evops.coldchain.controller;

import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.ReturnDecisionRequest;
import com.evops.coldchain.dto.ReturnInspectRequest;
import com.evops.coldchain.dto.ReturnIsolateRequest;
import com.evops.coldchain.dto.ReturnMoveRequest;
import com.evops.coldchain.dto.ReturnRegisterRequest;
import com.evops.coldchain.dto.ReturnReviewRequest;
import com.evops.coldchain.entity.OpLog;
import com.evops.coldchain.entity.ReturnBatch;
import com.evops.coldchain.entity.ReturnDecision;
import com.evops.coldchain.entity.ReturnInspection;
import com.evops.coldchain.entity.ReturnMovement;
import com.evops.coldchain.service.OpLogService;
import com.evops.coldchain.service.ReturnService;
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
import java.util.List;
import java.util.Map;

/**
 * 冷链药品退货（V11）。
 * 登记（收货拒签/温度超限/包装破损三类原因）-> 入隔离库位（不能再次出库）->
 * 质量检验 -> 处理决定（放行/销毁/转待查，双人复核生效）。
 * 列表/详情/导出与退货批次隔离处理页面读同一视图查询，运输对象置为无效后同口径；
 * 导出逐次写审计流水，只读导出不改写任何退货记录。
 */
@RestController
@RequestMapping("/api/cold-chain/returns")
public class ReturnController {

    private final ReturnService returnService;
    private final OpLogService opLogService;

    public ReturnController(ReturnService returnService, OpLogService opLogService) {
        this.returnService = returnService;
        this.opLogService = opLogService;
    }

    /** 退货登记（三类退货原因，原销售批次快照固化、只读关联） */
    @PostMapping
    public ApiResponse<ReturnBatch> register(@Valid @RequestBody ReturnRegisterRequest req) {
        return ApiResponse.ok(returnService.register(req));
    }

    /** 入隔离库位（此后不能再次出库） */
    @PostMapping("/{id}/isolate")
    public ApiResponse<ReturnBatch> isolate(@PathVariable("id") Long id,
                                            @Valid @RequestBody ReturnIsolateRequest req) {
        return ApiResponse.ok(returnService.isolate(id, req));
    }

    /** 隔离区内库位移动（每次移动记录库位、人员和时间；不能借此出库） */
    @PostMapping("/{id}/move")
    public ApiResponse<ReturnBatch> move(@PathVariable("id") Long id,
                                         @Valid @RequestBody ReturnMoveRequest req) {
        return ApiResponse.ok(returnService.move(id, req));
    }

    /** 质量检验（QA；完成检验才能提出处理决定） */
    @PostMapping("/{id}/inspect")
    public ApiResponse<ReturnInspection> inspect(@PathVariable("id") Long id,
                                                 @Valid @RequestBody ReturnInspectRequest req) {
        return ApiResponse.ok(returnService.inspect(id, req));
    }

    /** 提出处理决定（放行/销毁/转待查；双人复核才生效） */
    @PostMapping("/{id}/decisions")
    public ApiResponse<ReturnDecision> proposeDecision(@PathVariable("id") Long id,
                                                       @Valid @RequestBody ReturnDecisionRequest req) {
        return ApiResponse.ok(returnService.proposeDecision(id, req));
    }

    /** 双人复核（第二账号确认才生效；驳回退回已检验） */
    @PostMapping("/decisions/{decisionId}/review")
    public ApiResponse<ReturnDecision> reviewDecision(@PathVariable("decisionId") Long decisionId,
                                                      @Valid @RequestBody ReturnReviewRequest req) {
        return ApiResponse.ok(returnService.reviewDecision(decisionId, req));
    }

    /** 退货批次列表（与隔离处理页面、列表导出同一视图查询，同口径） */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(value = "status", required = false) String status) {
        return ApiResponse.ok(returnService.listReturnViews(status));
    }

    /** 退货详情（移动留痕 + 检验记录 + 处理决定） */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable("id") Long id) {
        return ApiResponse.ok(returnService.detail(id));
    }

    /** 原销售批次名下的退货批次（只读关联） */
    @GetMapping("/by-batch/{batchId}")
    public ApiResponse<List<ReturnBatch>> listByBatch(@PathVariable("batchId") Long batchId) {
        return ApiResponse.ok(returnService.listBySourceBatch(batchId));
    }

    /**
     * 退货批次列表 CSV 导出（需 coldchain:return:export）。
     * 与隔离处理页面、后台列表接口读同一视图查询：运输对象置为无效后三者同口径。
     */
    @GetMapping("/export")
    public void export(@RequestParam(value = "status", required = false) String status,
                       HttpServletResponse response) throws IOException {
        List<Map<String, Object>> views = returnService.listReturnViews(status);

        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"return_batches.csv\"");
        // UTF-8 BOM，便于 Excel 正确识别中文；输出流显式按 UTF-8 编码
        response.getOutputStream().write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        PrintWriter writer = new PrintWriter(new java.io.OutputStreamWriter(response.getOutputStream(),
                java.nio.charset.StandardCharsets.UTF_8));
        writer.println("退货单号,原批次号,原批次状态,药品名称,批准文号,温区下限,温区上限,包装规格,"
                + "退货原因类型,退货原因,原因明细,退货数量,退货状态,当前库位,入隔离人,入隔离时间,"
                + "终态决定,终态时间,登记时间");
        for (Map<String, Object> view : views) {
            ReturnBatch ret = (ReturnBatch) view.get("returnBatch");
            writer.println(String.join(",",
                    csv(ret.getReturnNo()), csv(ret.getSourceBatchNo()), csv(view.get("sourceBatchStatusName")),
                    csv(ret.getDrugNameSnap()), csv(ret.getApprovalNoSnap()), csv(ret.getTempMinSnap()),
                    csv(ret.getTempMaxSnap()), csv(ret.getPackageSpecSnap()),
                    csv(ret.getReasonType()), csv(view.get("reasonTypeName")), csv(ret.getReasonDetail()),
                    csv(ret.getReturnQty()), csv(view.get("statusName")), csv(ret.getCurrentLocation()),
                    csv(ret.getIsolatedByName()), csv(ret.getIsolatedTime()),
                    csv(ret.getFinalDecision() == null ? "" : ReturnService.decisionTypeName(ret.getFinalDecision())),
                    csv(ret.getFinalTime()), csv(ret.getCreateTime())));
        }
        writer.flush();

        opLogService.log(CcConstants.ENT_RETURN, null, null, CcConstants.ACT_RETURN_EXPORT,
                "导出退货批次列表 CSV：status=" + (status == null ? "全部" : status)
                        + "，共 " + views.size() + " 条（与隔离处理页面/后台接口同一视图查询，只读导出）");
    }

    /**
     * 退货证据链 CSV 导出（需 coldchain:return:export）：
     * 登记快照 -> 移动留痕（库位/人员/时间）-> 质量检验 -> 处理决定双人复核 -> 操作日志，一单到底。
     */
    @GetMapping("/{id}/evidence")
    @SuppressWarnings("unchecked")
    public void evidence(@PathVariable("id") Long id, HttpServletResponse response) throws IOException {
        Map<String, Object> chain = returnService.evidenceChain(id);
        ReturnBatch ret = (ReturnBatch) chain.get("returnBatch");
        List<ReturnMovement> movements = (List<ReturnMovement>) chain.get("movements");
        List<ReturnInspection> inspections = (List<ReturnInspection>) chain.get("inspections");
        List<ReturnDecision> decisions = (List<ReturnDecision>) chain.get("decisions");
        List<OpLog> opLogs = (List<OpLog>) chain.get("opLogs");

        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"return_evidence_" + ret.getReturnNo() + ".csv\"");
        response.getOutputStream().write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        PrintWriter writer = new PrintWriter(new java.io.OutputStreamWriter(response.getOutputStream(),
                java.nio.charset.StandardCharsets.UTF_8));

        writer.println("# 退货登记（原销售批次只读关联，登记快照）");
        writer.println("退货单号,原批次号,原批次当前状态,药品名称,批准文号,温区下限,温区上限,包装规格,"
                + "退货原因,原因明细,关联异常ID,退货数量,当前状态,当前库位,登记时间");
        writer.println(String.join(",",
                csv(ret.getReturnNo()), csv(ret.getSourceBatchNo()), csv(chain.get("sourceBatchStatusName")),
                csv(ret.getDrugNameSnap()), csv(ret.getApprovalNoSnap()), csv(ret.getTempMinSnap()),
                csv(ret.getTempMaxSnap()), csv(ret.getPackageSpecSnap()), csv(chain.get("reasonTypeName")),
                csv(ret.getReasonDetail()), csv(ret.getExceptionId()), csv(ret.getReturnQty()),
                csv(chain.get("statusName")), csv(ret.getCurrentLocation()), csv(ret.getCreateTime())));

        writer.println();
        writer.println("# 移动留痕（每次移动记录库位、人员和时间）");
        writer.println("移动时间,移动类型,源库位,目标库位,操作人ID,操作人,说明");
        for (ReturnMovement m : movements) {
            writer.println(String.join(",",
                    csv(m.getMoveTime()), csv(ReturnService.moveTypeName(m.getMoveType())),
                    csv(m.getFromLocation()), csv(m.getToLocation()),
                    csv(m.getOperatorId()), csv(m.getOperatorName()), csv(m.getNote())));
        }

        writer.println();
        writer.println("# 质量检验（质量人员完成检验才能提出处理决定）");
        writer.println("检验时间,检验结论,检验人ID,检验人,检验说明");
        for (ReturnInspection i : inspections) {
            writer.println(String.join(",",
                    csv(i.getInspectTime()), csv(i.getInspectResult()),
                    csv(i.getInspectorId()), csv(i.getInspectorName()), csv(i.getInspectNote())));
        }

        writer.println();
        writer.println("# 处理决定（双人复核：提出人与复核人必须是不同登录账号）");
        writer.println("决定ID,决定类型,决定理由,目标库位,复核状态,提出人,提出时间,复核人,复核时间,复核意见");
        for (ReturnDecision d : decisions) {
            writer.println(String.join(",",
                    csv(d.getId()), csv(ReturnService.decisionTypeName(d.getDecisionType())), csv(d.getReason()),
                    csv(d.getTargetLocation()), csv(d.getStatus()), csv(d.getProposeName()), csv(d.getProposeTime()),
                    csv(d.getReviewName()), csv(d.getReviewTime()), csv(d.getReviewNote())));
        }

        writer.println();
        writer.println("# 操作日志（关键动作逐次留痕）");
        writer.println("时间,动作,操作者,摘要");
        for (OpLog log : opLogs) {
            writer.println(String.join(",",
                    csv(log.getCreateTime()), csv(log.getAction()), csv(log.getOperator()), csv(log.getSummary())));
        }
        writer.flush();

        opLogService.log(CcConstants.ENT_RETURN, ret.getId(), ret.getSourceBatchId(), CcConstants.ACT_RETURN_EXPORT,
                "导出退货证据链 CSV：" + ret.getReturnNo() + "（移动 " + movements.size() + " 条、检验 "
                        + inspections.size() + " 条、决定 " + decisions.size() + " 条；只读导出，不改写退货记录）");
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
