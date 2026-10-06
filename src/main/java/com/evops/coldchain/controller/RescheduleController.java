package com.evops.coldchain.controller;

import com.evops.coldchain.dto.BatchRescheduleRequest;
import com.evops.coldchain.dto.CarrierNotifyRetryRequest;
import com.evops.coldchain.dto.RescheduleReviewRequest;
import com.evops.coldchain.dto.TodoConfirmRequest;
import com.evops.coldchain.entity.BatchReschedule;
import com.evops.coldchain.entity.CarrierNotifyLog;
import com.evops.coldchain.entity.RescheduleTodo;
import com.evops.coldchain.service.BatchRescheduleService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 运输批次改期（V10 送达时窗）：
 * - 未装车改期申请即生效（AUTO）；已装车/在途改期必须审批（PENDING -> APPROVED/REJECTED）；
 * - 每次改期列出受影响温控区间、承运通知、待办责任人；历史版本可追查；
 * - 承诺到达时间任何路径都不改写，改期只重算预计到达。
 */
@RestController
@RequestMapping("/api/cold-chain/reschedules")
public class RescheduleController {

    private final BatchRescheduleService rescheduleService;

    public RescheduleController(BatchRescheduleService rescheduleService) {
        this.rescheduleService = rescheduleService;
    }

    /** 改期申请（未装车直接生效；装车后停留待审批） */
    @PostMapping
    public ApiResponse<BatchReschedule> apply(@Valid @RequestBody BatchRescheduleRequest req) {
        return ApiResponse.ok(rescheduleService.apply(req));
    }

    /** 装车后改期审批同意（必须留意见；同意后预计到达更新、承运通知投递、待办生成） */
    @PostMapping("/{id}/approve")
    public ApiResponse<BatchReschedule> approve(@PathVariable("id") Long id,
                                                @Valid @RequestBody RescheduleReviewRequest req) {
        return ApiResponse.ok(rescheduleService.approve(id, req.getReviewNote()));
    }

    /** 装车后改期审批驳回（批次/发货单计划字段与承诺时间均不变） */
    @PostMapping("/{id}/reject")
    public ApiResponse<BatchReschedule> reject(@PathVariable("id") Long id,
                                               @Valid @RequestBody RescheduleReviewRequest req) {
        return ApiResponse.ok(rescheduleService.reject(id, req.getReviewNote()));
    }

    /** 待审批改期列表（已装车/在途改期） */
    @GetMapping("/pending")
    public ApiResponse<List<BatchReschedule>> pending() {
        return ApiResponse.ok(rescheduleService.listPending());
    }

    /** 改期单详情聚合：受影响温控区间/承运通知/待办责任人 */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable("id") Long id) {
        return ApiResponse.ok(rescheduleService.detail(id));
    }

    /** 某批次改期历史（按时间顺序，历史版本可追查） */
    @GetMapping("/batch/{batchId}")
    public ApiResponse<List<BatchReschedule>> listByBatch(@PathVariable("batchId") Long batchId) {
        return ApiResponse.ok(rescheduleService.listByBatch(batchId));
    }

    /** 失败承运通知手动重试（必须填重试原因；生成新一行，失败行保留） */
    @PostMapping("/notifications/{notifyId}/retry")
    public ApiResponse<CarrierNotifyLog> retryNotify(@PathVariable("notifyId") Long notifyId,
                                                     @Valid @RequestBody CarrierNotifyRetryRequest req) {
        return ApiResponse.ok(rescheduleService.retryNotify(notifyId, req.getRetryReason()));
    }

    /** 待办确认（PENDING -> DONE，事实保留） */
    @PostMapping("/todos/{todoId}/confirm")
    public ApiResponse<RescheduleTodo> confirmTodo(@PathVariable("todoId") Long todoId,
                                                   @Valid @RequestBody TodoConfirmRequest req) {
        return ApiResponse.ok(rescheduleService.confirmTodo(todoId, req.getConfirmNote()));
    }

    /** 待办列表（可按责任岗位过滤） */
    @GetMapping("/todos/pending")
    public ApiResponse<List<RescheduleTodo>> pendingTodos(
            @RequestParam(value = "ownerRole", required = false) String ownerRole) {
        return ApiResponse.ok(rescheduleService.listPendingTodos(ownerRole));
    }
}
