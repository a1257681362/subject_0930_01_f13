package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 退货处理决定复核请求（V11，双人复核第二人）。
 * 复核人必须是不同于提出人的登录账号；通过才生效，驳回退回已检验状态。
 */
public class ReturnReviewRequest {
    @NotNull(message = "复核结论不能为空")
    private Boolean approved;
    @NotBlank(message = "复核意见不能为空")
    private String reviewNote;

    public Boolean getApproved() { return approved; }
    public void setApproved(Boolean approved) { this.approved = approved; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
}
