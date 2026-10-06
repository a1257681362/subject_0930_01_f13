package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;

/**
 * 岗位追溯机构授权请求（V13）：授予账号可见机构范围与联系方式明文/脱敏口径。
 * orgCode='*' 表示全域（仅管理员/质量岗等全域岗位）。
 */
public class TraceAuthGrantRequest {
    @NotBlank(message = "被授权账号不能为空")
    private String username;
    @NotBlank(message = "授权机构代码不能为空（* 表示全域）")
    private String orgCode;
    /** TRUE 联系方式明文可见 / FALSE 脱敏展示，默认 FALSE */
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
