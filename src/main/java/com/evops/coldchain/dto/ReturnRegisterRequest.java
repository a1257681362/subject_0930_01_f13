package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;

/**
 * 退货登记请求（V11）。
 * 按原销售运输批次登记退货：退货原因三类（REJECTION 收货拒签 / TEMP_EXCURSION 温度超限 /
 * PACKAGE_DAMAGE 包装破损），登记当刻把原批次与药品档案关键字段快照固化，原批次只读关联。
 */
public class ReturnRegisterRequest {
    @NotNull(message = "原销售运输批次ID不能为空")
    private Long sourceBatchId;
    /** 退货原因：REJECTION / TEMP_EXCURSION / PACKAGE_DAMAGE */
    @NotBlank(message = "退货原因类型不能为空")
    private String reasonType;
    /** 原因明细（必须留下依据） */
    @NotBlank(message = "退货原因明细不能为空")
    private String reasonDetail;
    /** 关联运输异常ID（可空；提供时异常类型必须与退货原因匹配且属于同一批次） */
    private Long exceptionId;
    @NotNull(message = "退货数量不能为空")
    @Positive(message = "退货数量必须大于0")
    private Integer returnQty;

    public Long getSourceBatchId() { return sourceBatchId; }
    public void setSourceBatchId(Long sourceBatchId) { this.sourceBatchId = sourceBatchId; }
    public String getReasonType() { return reasonType; }
    public void setReasonType(String reasonType) { this.reasonType = reasonType; }
    public String getReasonDetail() { return reasonDetail; }
    public void setReasonDetail(String reasonDetail) { this.reasonDetail = reasonDetail; }
    public Long getExceptionId() { return exceptionId; }
    public void setExceptionId(Long exceptionId) { this.exceptionId = exceptionId; }
    public Integer getReturnQty() { return returnQty; }
    public void setReturnQty(Integer returnQty) { this.returnQty = returnQty; }
}
