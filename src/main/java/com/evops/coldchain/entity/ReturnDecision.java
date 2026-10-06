package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 退货处理决定（V11，只追加；双人复核）。
 * 提出人登记 放行/销毁/转待查 决定（PENDING），必须由不同于提出人的
 * 第二个登录账号复核确认才生效（CONFIRMED）；驳回（REJECTED）退回已检验状态。
 * 每次决定独立成行，历史决定（含被驳回）原样保留。
 */
@TableName("t_cc_return_decision")
public class ReturnDecision extends BaseEntity {
    private Long returnId;
    /** RELEASE 放行 / DESTROY 销毁 / PENDING_REVIEW 转待查 */
    private String decisionType;
    /** 决定理由（必填） */
    private String reason;
    /** 放行->合格品库位 / 销毁->销毁区（转待查为空，留在隔离区） */
    private String targetLocation;
    /** PENDING 待复核 / CONFIRMED 双人复核通过已生效 / REJECTED 复核驳回 */
    private String status;
    private Long proposeBy;
    private String proposeName;
    private LocalDateTime proposeTime;
    /** 复核人（必须与提出人不同账号） */
    private Long reviewBy;
    private String reviewName;
    private LocalDateTime reviewTime;
    private String reviewNote;

    public Long getReturnId() { return returnId; }
    public void setReturnId(Long returnId) { this.returnId = returnId; }
    public String getDecisionType() { return decisionType; }
    public void setDecisionType(String decisionType) { this.decisionType = decisionType; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getTargetLocation() { return targetLocation; }
    public void setTargetLocation(String targetLocation) { this.targetLocation = targetLocation; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getProposeBy() { return proposeBy; }
    public void setProposeBy(Long proposeBy) { this.proposeBy = proposeBy; }
    public String getProposeName() { return proposeName; }
    public void setProposeName(String proposeName) { this.proposeName = proposeName; }
    public LocalDateTime getProposeTime() { return proposeTime; }
    public void setProposeTime(LocalDateTime proposeTime) { this.proposeTime = proposeTime; }
    public Long getReviewBy() { return reviewBy; }
    public void setReviewBy(Long reviewBy) { this.reviewBy = reviewBy; }
    public String getReviewName() { return reviewName; }
    public void setReviewName(String reviewName) { this.reviewName = reviewName; }
    public LocalDateTime getReviewTime() { return reviewTime; }
    public void setReviewTime(LocalDateTime reviewTime) { this.reviewTime = reviewTime; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
}
