package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 退货批次（V11 冷链药品退货主表）。
 * 按原销售运输批次登记退货，登记当刻把批次号/药品名称/批准文号/温区/包装规格
 * 逐字段快照固化；原销售批次只读关联，退货流程绝不回写原批次任何字段——
 * 原运输批次即便被撤回置为无效，退货台账仍按快照展示（页面/导出/后台同口径）。
 */
@TableName("t_cc_return_batch")
public class ReturnBatch extends BaseEntity {
    /** 退货单号：TH+8位序列，稳定唯一 */
    private String returnNo;
    /** 原销售（运输）批次ID：只读关联 */
    private Long sourceBatchId;
    /** 原批次号快照 */
    private String sourceBatchNo;
    private Long orderId;
    private Long drugId;

    // —— 登记当刻快照（原批次撤回/档案变更后退货台账口径不变）——
    private String drugNameSnap;
    private String approvalNoSnap;
    private BigDecimal tempMinSnap;
    private BigDecimal tempMaxSnap;
    private String packageSpecSnap;

    /** 退货原因：REJECTION 收货拒签 / TEMP_EXCURSION 温度超限 / PACKAGE_DAMAGE 包装破损 */
    private String reasonType;
    private String reasonDetail;
    /** 关联运输异常（可空；异常类型必须与退货原因匹配） */
    private Long exceptionId;
    private Integer returnQty;

    /** REGISTERED/ISOLATED/INSPECTED/DECISION_PENDING/RELEASED/DESTROYED/PENDING_REVIEW */
    private String status;
    /** 当前库位（入隔离后非空） */
    private String currentLocation;
    private Long isolatedBy;
    private String isolatedByName;
    private LocalDateTime isolatedTime;
    /** 终态决定 RELEASE / DESTROY（转待查非终态） */
    private String finalDecision;
    private LocalDateTime finalTime;

    public String getReturnNo() { return returnNo; }
    public void setReturnNo(String returnNo) { this.returnNo = returnNo; }
    public Long getSourceBatchId() { return sourceBatchId; }
    public void setSourceBatchId(Long sourceBatchId) { this.sourceBatchId = sourceBatchId; }
    public String getSourceBatchNo() { return sourceBatchNo; }
    public void setSourceBatchNo(String sourceBatchNo) { this.sourceBatchNo = sourceBatchNo; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Long getDrugId() { return drugId; }
    public void setDrugId(Long drugId) { this.drugId = drugId; }
    public String getDrugNameSnap() { return drugNameSnap; }
    public void setDrugNameSnap(String drugNameSnap) { this.drugNameSnap = drugNameSnap; }
    public String getApprovalNoSnap() { return approvalNoSnap; }
    public void setApprovalNoSnap(String approvalNoSnap) { this.approvalNoSnap = approvalNoSnap; }
    public BigDecimal getTempMinSnap() { return tempMinSnap; }
    public void setTempMinSnap(BigDecimal tempMinSnap) { this.tempMinSnap = tempMinSnap; }
    public BigDecimal getTempMaxSnap() { return tempMaxSnap; }
    public void setTempMaxSnap(BigDecimal tempMaxSnap) { this.tempMaxSnap = tempMaxSnap; }
    public String getPackageSpecSnap() { return packageSpecSnap; }
    public void setPackageSpecSnap(String packageSpecSnap) { this.packageSpecSnap = packageSpecSnap; }
    public String getReasonType() { return reasonType; }
    public void setReasonType(String reasonType) { this.reasonType = reasonType; }
    public String getReasonDetail() { return reasonDetail; }
    public void setReasonDetail(String reasonDetail) { this.reasonDetail = reasonDetail; }
    public Long getExceptionId() { return exceptionId; }
    public void setExceptionId(Long exceptionId) { this.exceptionId = exceptionId; }
    public Integer getReturnQty() { return returnQty; }
    public void setReturnQty(Integer returnQty) { this.returnQty = returnQty; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCurrentLocation() { return currentLocation; }
    public void setCurrentLocation(String currentLocation) { this.currentLocation = currentLocation; }
    public Long getIsolatedBy() { return isolatedBy; }
    public void setIsolatedBy(Long isolatedBy) { this.isolatedBy = isolatedBy; }
    public String getIsolatedByName() { return isolatedByName; }
    public void setIsolatedByName(String isolatedByName) { this.isolatedByName = isolatedByName; }
    public LocalDateTime getIsolatedTime() { return isolatedTime; }
    public void setIsolatedTime(LocalDateTime isolatedTime) { this.isolatedTime = isolatedTime; }
    public String getFinalDecision() { return finalDecision; }
    public void setFinalDecision(String finalDecision) { this.finalDecision = finalDecision; }
    public LocalDateTime getFinalTime() { return finalTime; }
    public void setFinalTime(LocalDateTime finalTime) { this.finalTime = finalTime; }
}
