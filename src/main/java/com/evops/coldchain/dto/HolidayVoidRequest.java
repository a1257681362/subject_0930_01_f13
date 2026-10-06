package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;

/** 节假日/临时闭馆规则作废请求：冻结（已参与旧结论）的规则禁止作废 */
public class HolidayVoidRequest {
    @NotBlank(message = "作废原因不能为空")
    private String voidReason;

    public String getVoidReason() { return voidReason; }
    public void setVoidReason(String voidReason) { this.voidReason = voidReason; }
}
