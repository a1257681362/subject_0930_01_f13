package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

/**
 * 人工修正记录（只追加）：任何人工修正都不覆盖原始回执，
 * 每修正一次追加一行（字段/旧值/新值/原因/修正人），
 * 展示层按“原始值 + 最新修正”并列给出有效值。
 */
@TableName("t_cc_sign_correction")
public class SignCorrection extends CreateOnlyEntity {
    private Long receiptId;
    private Long batchId;
    /** 被修正字段（signBy / signRemark / locationAddress） */
    private String fieldName;
    private String oldValue;
    private String newValue;
    private String reason;
    private String correctByName;

    public Long getReceiptId() { return receiptId; }
    public void setReceiptId(Long receiptId) { this.receiptId = receiptId; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getFieldName() { return fieldName; }
    public void setFieldName(String fieldName) { this.fieldName = fieldName; }
    public String getOldValue() { return oldValue; }
    public void setOldValue(String oldValue) { this.oldValue = oldValue; }
    public String getNewValue() { return newValue; }
    public void setNewValue(String newValue) { this.newValue = newValue; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getCorrectByName() { return correctByName; }
    public void setCorrectByName(String correctByName) { this.correctByName = correctByName; }
}
