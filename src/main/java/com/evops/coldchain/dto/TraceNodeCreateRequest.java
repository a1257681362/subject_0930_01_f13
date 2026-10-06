package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 追溯节点创建请求（V13）。
 *
 * opKey 是“同一操作”的归并键：同一操作并发发起时各方使用相同 opKey，
 * 由数据库唯一约束仲裁唯一生效者；opKey 缺失直接拒绝（无法做并发归并）。
 * 补录（backfill=TRUE）必须填写 backfillReason；补录只追加新节点事实，
 * 不提供、也不允许改写任何已发布节点快照。
 */
public class TraceNodeCreateRequest {
    /** 同一操作归并键（同一操作并发的各方必须一致） */
    @NotBlank(message = "操作归并键 opKey 不能为空（同一操作并发据此仲裁唯一生效）")
    private String opKey;
    @NotNull(message = "运输批次ID不能为空")
    private Long batchId;
    /** PURCHASE / WAREHOUSE / DISPATCH / TRANSIT / SIGN / RETURN */
    @NotBlank(message = "节点类型不能为空")
    private String nodeType;
    /** 封签号（可空；中转核验等节点登记后支持按封签反查） */
    private String sealNo;
    /** 业务发生时间（补录可为过去时间；节点保存“当时”状态） */
    @NotNull(message = "业务发生时间不能为空")
    private LocalDateTime occurredTime;
    @NotBlank(message = "节点责任机构代码不能为空")
    private String orgCode;
    @NotBlank(message = "节点责任机构名称不能为空")
    private String orgName;
    private String contactName;
    private String contactPhone;
    /** 当时状态快照（文字摘要，必填；发布时整体固化） */
    @NotBlank(message = "节点状态快照不能为空")
    private String statusSnap;
    /** 是否补录（业务发生后补登） */
    private Boolean backfill;
    /** 补录原因（backfill=TRUE 时必填） */
    private String backfillReason;
    /** 是否创建即发布（补录事实可直接发布；默认 FALSE 先落草稿） */
    private Boolean publish;
    /**
     * 幂等重放声明：默认 FALSE——对已存在 opKey 的重复请求一律视为“同一操作并发”而拒绝；
     * 仅当调用方明确这是网络重试（TRUE）且载荷与首个请求完全一致时，回放首个结果。
     */
    private Boolean idempotentReplay;

    public String getOpKey() { return opKey; }
    public void setOpKey(String opKey) { this.opKey = opKey; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getNodeType() { return nodeType; }
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }
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
    public Boolean getBackfill() { return backfill; }
    public void setBackfill(Boolean backfill) { this.backfill = backfill; }
    public String getBackfillReason() { return backfillReason; }
    public void setBackfillReason(String backfillReason) { this.backfillReason = backfillReason; }
    public Boolean getPublish() { return publish; }
    public void setPublish(Boolean publish) { this.publish = publish; }
    public Boolean getIdempotentReplay() { return idempotentReplay; }
    public void setIdempotentReplay(Boolean idempotentReplay) { this.idempotentReplay = idempotentReplay; }
}