package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;

/** 承运通知重试请求：必须填写重试原因（参照 V6 升级通知重试口径） */
public class CarrierNotifyRetryRequest {
    @NotBlank(message = "重试原因不能为空")
    private String retryReason;

    public String getRetryReason() { return retryReason; }
    public void setRetryReason(String retryReason) { this.retryReason = retryReason; }
}
