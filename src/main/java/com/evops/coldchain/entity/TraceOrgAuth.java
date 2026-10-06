package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

/**
 * 岗位追溯机构授权（V13）：账号 -> 可见机构（org_code，'*'=全域）+ 联系方式是否明文。
 * 追溯链路/反查只返回授权机构范围内的节点；contact_visible=FALSE 时联系方式脱敏展示。
 */
@TableName("t_cc_trace_org_auth")
public class TraceOrgAuth extends BaseEntity {
    private String username;
    /** 授权机构代码；'*' 全部机构 */
    private String orgCode;
    /** TRUE 联系方式明文 / FALSE 脱敏 */
    private Boolean contactVisible;
    private String grantReason;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getOrgCode() { return orgCode; }
    public void setOrgCode(String orgCode) { this.orgCode = orgCode; }
    public Boolean getContactVisible() { return contactVisible; }
    public void setContactVisible(Boolean contactVisible) { this.contactVisible = contactVisible; }
    public String getGrantReason() { return grantReason; }
    public void setGrantReason(String grantReason) { this.grantReason = grantReason; }
}
