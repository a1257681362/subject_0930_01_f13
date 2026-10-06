package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 运输批次改期请求（V10）。
 * 按新的计划发运时间，依收货机构当前时窗规则（工作日区间+节假日/临时闭馆）重新计算
 * 预计到达；承诺到达时间不在请求中、也不会被改写。
 */
public class BatchRescheduleRequest {
    @NotNull(message = "运输批次ID不能为空")
    private Long batchId;
    @NotNull(message = "新的计划发运时间不能为空")
    private LocalDateTime newPlanShipTime;
    @NotBlank(message = "改期原因不能为空")
    private String reason;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public LocalDateTime getNewPlanShipTime() { return newPlanShipTime; }
    public void setNewPlanShipTime(LocalDateTime newPlanShipTime) { this.newPlanShipTime = newPlanShipTime; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
