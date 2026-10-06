package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 追溯岗位-机构授权（V13）。
 * 一行表示“某账号可看某机构”；orgName='*' 为全域授权。
 * contactVisible=TRUE 时该机构联系方式可见明文，否则联系方式只展示脱敏值。
 * 授权撤销只置 REVOKED 留痕，不删除授权行；授权收紧只影响查询/导出口径，
 * 已发布追溯节点快照原样保留。
 */
@TableName("t_cc_trace_org_grant")
public class TraceOrgGrant extends BaseEntity {
    private String username;
    private String orgName;
    private Boolean contactVisible;
    /** ACTIVE / REVOKED */
    private String status;
    private String revokeReason;
    private LocalDateTime revokeTime;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getOrgName() { return orgName; }
    public void setOrgName(String orgName) { this.orgName = orgName; }
    public Boolean getContactVisible() { return contactVisible; }
    public void setContactVisible(Boolean contactVisible) { this.contactVisible = contactVisible; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRevokeReason() { return revokeReason; }
    public void setRevokeReason(String revokeReason) { this.revokeReason = revokeReason; }
    public LocalDateTime getRevokeTime() { return revokeTime; }
    public void setRevokeTime(LocalDateTime revokeTime) { this.revokeTime = revokeTime; }
}
