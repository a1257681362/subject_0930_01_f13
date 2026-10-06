package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.PositiveOrZero;

/** 收货机构建档请求（V10） */
public class ReceiveOrgCreateRequest {
    @NotBlank(message = "收货机构名称不能为空")
    private String orgName;
    private String contactName;
    private String contactPhone;
    /** 默认运输时长（分钟）：派车时 计划发运+时长 起算承诺到达 */
    @NotNull(message = "默认运输时长不能为空")
    @PositiveOrZero(message = "默认运输时长不能为负")
    private Integer transportMinutes;
    private String remark;

    public String getOrgName() { return orgName; }
    public void setOrgName(String orgName) { this.orgName = orgName; }
    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
    public Integer getTransportMinutes() { return transportMinutes; }
    public void setTransportMinutes(Integer transportMinutes) { this.transportMinutes = transportMinutes; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
