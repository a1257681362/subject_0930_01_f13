package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 人工修正请求：只追加一条修正记录（字段/旧值/新值/原因/修正人），
 * 绝不覆盖原始回执；有效值由展示层按“原值 + 最新修正”并列给出。
 */
public class EsignCorrectRequest {
    @NotNull(message = "回执ID不能为空")
    private Long receiptId;
    /** 允许修正的字段：signBy / signRemark / locationAddress */
    @NotBlank(message = "修正字段不能为空")
    private String fieldName;
    @NotBlank(message = "修正后值不能为空")
    private String newValue;
    @NotBlank(message = "修正原因不能为空")
    private String reason;

    public Long getReceiptId() { return receiptId; }
    public void setReceiptId(Long receiptId) { this.receiptId = receiptId; }
    public String getFieldName() { return fieldName; }
    public void setFieldName(String fieldName) { this.fieldName = fieldName; }
    public String getNewValue() { return newValue; }
    public void setNewValue(String newValue) { this.newValue = newValue; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
