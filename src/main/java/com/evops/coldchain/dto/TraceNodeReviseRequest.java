package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import java.time.LocalDateTime;

/**
 * 追溯草稿节点修订请求（V13）。
 * 仅 DRAFT 节点可修订（revision 每次 +1）；PUBLISHED 节点快照已冻结，
 * 任何修订一律拒绝（PUBLISHED_IMMUTABLE），补录只能另追加新节点。
 */
public class TraceNodeReviseRequest {
    private String sealNo;
    private LocalDateTime occurredTime;
    private String orgCode;
    private String orgName;
    private String contactName;
    private String contactPhone;
    @NotBlank(message = "节点状态快照不能为空")
    private String statusSnap;
    /** 修订原因（留痕） */
    private String reviseReason;

    public String getSealNo() { return sealNo; }
    public void setSealNo(String sealNo) { this.sealNo = sealNo; }
    public LocalDateTime getOccurredTime() { return occurredTime; }
    public void setOccurredTime(LocalDateTime occurredTime) { this.occurredTime = occurredTime; }
    public String getOrgCode() { return orgCode; }
    public void setOrgCode(String orgCode) { this.orgCode = orgCode; }
    public String getOrgName() { return orgName; }
    public void setOrgName(String orgName) { this.orgName = orgName; }
    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
    public String getStatusSnap() { return statusSnap; }
    public void setStatusSnap(String statusSnap) { this.statusSnap = statusSnap; }
    public String getReviseReason() { return reviseReason; }
    public void setReviseReason(String reviseReason) { this.reviseReason = reviseReason; }
}
