package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.time.LocalDateTime;

/**
 * 追溯节点封签锚点（V13）：节点发布当刻逐签固化封签号与当时核验状态/核验人/时间。
 * 只追加，不随封签事后状态变化改写；支撑“按封签反查批次链路”。
 */
@TableName("t_cc_trace_node_seal")
public class TraceNodeSeal extends CreateOnlyEntity {
    private Long nodeId;
    private Long batchId;
    private String sealNo;
    /** 发布当刻封签状态原文（PENDING/CONFIRMED） */
    private String sealStatus;
    private String verifiedBy;
    private LocalDateTime verifyTime;
    private String handoverNo;

    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getSealNo() { return sealNo; }
    public void setSealNo(String sealNo) { this.sealNo = sealNo; }
    public String getSealStatus() { return sealStatus; }
    public void setSealStatus(String sealStatus) { this.sealStatus = sealStatus; }
    public String getVerifiedBy() { return verifiedBy; }
    public void setVerifiedBy(String verifiedBy) { this.verifiedBy = verifiedBy; }
    public LocalDateTime getVerifyTime() { return verifyTime; }
    public void setVerifyTime(LocalDateTime verifyTime) { this.verifyTime = verifyTime; }
    public String getHandoverNo() { return handoverNo; }
    public void setHandoverNo(String handoverNo) { this.handoverNo = handoverNo; }
}
