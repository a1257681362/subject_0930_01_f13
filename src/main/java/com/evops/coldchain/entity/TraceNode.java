package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 药品批次追溯图节点（V13）。
 *
 * 固定六节点链路：采购 PURCHASE -> 仓储 WAREHOUSE -> 发运 DISPATCH ->
 * 中转 TRANSIT（可多次）-> 签收 SIGN -> 退货 RETURN（可缺省/多次）。
 *
 * 快照语义：
 * - DRAFT 草稿可反复修订（revision 递增留痕）；发布后置 PUBLISHED 并固化
 *   snapshot_json/snapshot_hash，发布后任何更新/补录请求都不能改变已发布快照。
 * - op_key 是“同一操作”的归并键，数据库唯一约束仲裁并发：先提交者生效，
 *   落败者拒绝并写 t_cc_trace_concurrent_log。
 */
@TableName("t_cc_trace_node")
public class TraceNode extends BaseEntity {
    private String nodeNo;
    private Long batchId;
    private String batchNo;
    /** PURCHASE / WAREHOUSE / DISPATCH / TRANSIT / SIGN / RETURN */
    private String nodeType;
    /** 同批次同类型序号（中转/退货可多次） */
    private Integer nodeSeq;
    /** 同一操作归并键（并发仲裁锚点） */
    private String opKey;
    /** 封签号（可空；按封签反查锚点） */
    private String sealNo;
    private LocalDateTime occurredTime;
    /** 节点责任机构与联系方式 */
    private String orgCode;
    private String orgName;
    private String contactName;
    private String contactPhone;
    /** 当时状态快照（文字摘要） */
    private String statusSnap;
    /** 发布时固化的完整状态快照 JSON */
    private String snapshotJson;
    private String snapshotHash;
    /** 建节点请求内容摘要（同键重放/并发判定用） */
    private String payloadHash;
    /** 是否补录 */
    private Boolean backfill;
    private String backfillReason;
    private Integer revision;
    /** DRAFT / PUBLISHED */
    private String status;
    /** 发布当刻批次状态 */
    private String batchStatusSnap;
    private Long publishedBy;
    private String publishedName;
    private LocalDateTime publishedTime;

    public String getNodeNo() { return nodeNo; }
    public void setNodeNo(String nodeNo) { this.nodeNo = nodeNo; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public String getNodeType() { return nodeType; }
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }
    public Integer getNodeSeq() { return nodeSeq; }
    public void setNodeSeq(Integer nodeSeq) { this.nodeSeq = nodeSeq; }
    public String getOpKey() { return opKey; }
    public void setOpKey(String opKey) { this.opKey = opKey; }
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
    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String snapshotJson) { this.snapshotJson = snapshotJson; }
    public String getSnapshotHash() { return snapshotHash; }
    public void setSnapshotHash(String snapshotHash) { this.snapshotHash = snapshotHash; }
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }
    public Boolean getBackfill() { return backfill; }
    public void setBackfill(Boolean backfill) { this.backfill = backfill; }
    public String getBackfillReason() { return backfillReason; }
    public void setBackfillReason(String backfillReason) { this.backfillReason = backfillReason; }
    public Integer getRevision() { return revision; }
    public void setRevision(Integer revision) { this.revision = revision; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getBatchStatusSnap() { return batchStatusSnap; }
    public void setBatchStatusSnap(String batchStatusSnap) { this.batchStatusSnap = batchStatusSnap; }
    public Long getPublishedBy() { return publishedBy; }
    public void setPublishedBy(Long publishedBy) { this.publishedBy = publishedBy; }
    public String getPublishedName() { return publishedName; }
    public void setPublishedName(String publishedName) { this.publishedName = publishedName; }
    public LocalDateTime getPublishedTime() { return publishedTime; }
    public void setPublishedTime(LocalDateTime publishedTime) { this.publishedTime = publishedTime; }
}
