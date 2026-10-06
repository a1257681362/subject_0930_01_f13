package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;

/**
 * 追溯岗位-机构授权维护请求（V13）。
 * 授权决定该岗位可查看哪些机构；contactVisible=TRUE 时联系方式可见明文，否则脱敏。
 * orgName='*' 为全域授权。
 */
public class TraceGrantRequest {
    @NotBlank(message = "被授权账号不能为空")
    private String username;
    @NotBlank(message = "授权机构名不能为空（'*' 表示全域）")
    private String orgName;
    private Boolean contactVisible = Boolean.FALSE;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getOrgName() { return orgName; }
    public void setOrgName(String orgName) { this.orgName = orgName; }
    public Boolean getContactVisible() { return contactVisible; }
    public void setContactVisible(Boolean contactVisible) { this.contactVisible = contactVisible; }
}
