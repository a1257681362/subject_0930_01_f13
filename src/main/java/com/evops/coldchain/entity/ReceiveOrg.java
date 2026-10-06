package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

/**
 * 收货机构主档（V10 送达时窗）。
 * 一个机构一行，currentRuleId 指向当前生效的时窗规则版本；
 * 与运输批次 receiver_org 自由文本按机构名对应。
 */
@TableName("t_cc_receive_org")
public class ReceiveOrg extends BaseEntity {
    private String orgNo;
    private String orgName;
    private String contactName;
    private String contactPhone;
    /** 默认运输时长（分钟）：派车时 计划发运 + 该时长 起算承诺到达 */
    private Integer transportMinutes;
    /** 当前生效时窗规则版本ID */
    private Long currentRuleId;
    /** ACTIVE / REVOKED */
    private String status;
    private String remark;

    public String getOrgNo() { return orgNo; }
    public void setOrgNo(String orgNo) { this.orgNo = orgNo; }
    public String getOrgName() { return orgName; }
    public void setOrgName(String orgName) { this.orgName = orgName; }
    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
    public Integer getTransportMinutes() { return transportMinutes; }
    public void setTransportMinutes(Integer transportMinutes) { this.transportMinutes = transportMinutes; }
    public Long getCurrentRuleId() { return currentRuleId; }
    public void setCurrentRuleId(Long currentRuleId) { this.currentRuleId = currentRuleId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
