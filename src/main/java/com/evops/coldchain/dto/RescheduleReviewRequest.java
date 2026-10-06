package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;

/** 装车后改期审批意见（同意/驳回均必填，参照运输审批口径） */
public class RescheduleReviewRequest {
    @NotBlank(message = "审批意见不能为空")
    private String reviewNote;

    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
}
