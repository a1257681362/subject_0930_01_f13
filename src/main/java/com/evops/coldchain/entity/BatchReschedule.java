package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 运输批次改期单（V10）。
 * 一次改期一张单，只追加历史，可追查：
 * - DISPATCHED（未装车）：申请即 AUTO 直接生效，重算预计到达；
 * - LOADING 及以后（装车/在途/异常挂起）：落 PENDING 必须审批，同意后才应用，驳回不变。
 * 承诺到达时间不落在本单、不改写——批次 committed_arrival_time 只在派车时写一次。
 */
@TableName("t_cc_batch_reschedule")
public class BatchReschedule extends BaseEntity {
    private String rescheduleNo;
    private Long batchId;
    private String receiverOrg;
    /** ETA 解析采用的规则版本（历史追查锚点） */
    private Long ruleId;
    private Integer ruleVersionNo;
    /** 参与解析的节假日/闭馆规则ID，逗号分隔 */
    private String holidayIdsSnap;
    private LocalDateTime oldPlanShipTime;
    private LocalDateTime oldEstimatedArrival;
    private LocalDateTime newPlanShipTime;
    private LocalDateTime newEstimatedArrival;
    private Integer transportMinutesSnap;
    private String reason;
    /** AUTO / PENDING / APPROVED / REJECTED */
    private String status;
    private Boolean requireApproval;
    private LocalDateTime appliedTime;
    private Long applyBy;
    private String applyName;
    private Long reviewBy;
    private String reviewName;
    private LocalDateTime reviewTime;
    private String reviewNote;

    public String getRescheduleNo() { return rescheduleNo; }
    public void setRescheduleNo(String rescheduleNo) { this.rescheduleNo = rescheduleNo; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getReceiverOrg() { return receiverOrg; }
    public void setReceiverOrg(String receiverOrg) { this.receiverOrg = receiverOrg; }
    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
    public Integer getRuleVersionNo() { return ruleVersionNo; }
    public void setRuleVersionNo(Integer ruleVersionNo) { this.ruleVersionNo = ruleVersionNo; }
    public String getHolidayIdsSnap() { return holidayIdsSnap; }
    public void setHolidayIdsSnap(String holidayIdsSnap) { this.holidayIdsSnap = holidayIdsSnap; }
    public LocalDateTime getOldPlanShipTime() { return oldPlanShipTime; }
    public void setOldPlanShipTime(LocalDateTime oldPlanShipTime) { this.oldPlanShipTime = oldPlanShipTime; }
    public LocalDateTime getOldEstimatedArrival() { return oldEstimatedArrival; }
    public void setOldEstimatedArrival(LocalDateTime oldEstimatedArrival) { this.oldEstimatedArrival = oldEstimatedArrival; }
    public LocalDateTime getNewPlanShipTime() { return newPlanShipTime; }
    public void setNewPlanShipTime(LocalDateTime newPlanShipTime) { this.newPlanShipTime = newPlanShipTime; }
    public LocalDateTime getNewEstimatedArrival() { return newEstimatedArrival; }
    public void setNewEstimatedArrival(LocalDateTime newEstimatedArrival) { this.newEstimatedArrival = newEstimatedArrival; }
    public Integer getTransportMinutesSnap() { return transportMinutesSnap; }
    public void setTransportMinutesSnap(Integer transportMinutesSnap) { this.transportMinutesSnap = transportMinutesSnap; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Boolean getRequireApproval() { return requireApproval; }
    public void setRequireApproval(Boolean requireApproval) { this.requireApproval = requireApproval; }
    public LocalDateTime getAppliedTime() { return appliedTime; }
    public void setAppliedTime(LocalDateTime appliedTime) { this.appliedTime = appliedTime; }
    public Long getApplyBy() { return applyBy; }
    public void setApplyBy(Long applyBy) { this.applyBy = applyBy; }
    public String getApplyName() { return applyName; }
    public void setApplyName(String applyName) { this.applyName = applyName; }
    public Long getReviewBy() { return reviewBy; }
    public void setReviewBy(Long reviewBy) { this.reviewBy = reviewBy; }
    public String getReviewName() { return reviewName; }
    public void setReviewName(String reviewName) { this.reviewName = reviewName; }
    public LocalDateTime getReviewTime() { return reviewTime; }
    public void setReviewTime(LocalDateTime reviewTime) { this.reviewTime = reviewTime; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
}
