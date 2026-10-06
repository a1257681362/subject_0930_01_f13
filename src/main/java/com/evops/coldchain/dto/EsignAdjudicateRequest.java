package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 签收冲突主管裁决请求：同一批次出现两份不同签收冻结批次后，
 * 由主管选定生效回执，其余回执置 REJECTED（原始行全部保留）。
 */
public class EsignAdjudicateRequest {
    @NotNull(message = "运输批次ID不能为空")
    private Long batchId;
    @NotNull(message = "生效回执ID不能为空")
    private Long effectiveReceiptId;
    @NotBlank(message = "裁决意见不能为空")
    private String reason;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public Long getEffectiveReceiptId() { return effectiveReceiptId; }
    public void setEffectiveReceiptId(Long effectiveReceiptId) { this.effectiveReceiptId = effectiveReceiptId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
