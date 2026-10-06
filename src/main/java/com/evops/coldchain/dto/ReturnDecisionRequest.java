package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;

/**
 * 退货处理决定提出请求（V11）。
 * 质量检验完成后才能提出：RELEASE 放行 / DESTROY 销毁 / PENDING_REVIEW 转待查；
 * 决定须双人复核（提出人与复核人必须是不同登录账号）才生效。
 */
public class ReturnDecisionRequest {
    /** RELEASE 放行 / DESTROY 销毁 / PENDING_REVIEW 转待查 */
    @NotBlank(message = "处理决定类型不能为空")
    private String decisionType;
    @NotBlank(message = "决定理由不能为空")
    private String reason;
    /** 放行->合格品库位 / 销毁->销毁区（转待查留空，批次留在隔离区） */
    private String targetLocation;

    public String getDecisionType() { return decisionType; }
    public void setDecisionType(String decisionType) { this.decisionType = decisionType; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getTargetLocation() { return targetLocation; }
    public void setTargetLocation(String targetLocation) { this.targetLocation = targetLocation; }
}
