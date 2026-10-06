package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

/**
 * 同一操作并发仲裁审计（V13，只追加）。
 * 冷链药品运输同一操作并发发起时：先提交者 WINNER 生效，落败者 REJECTED，
 * 双方（生效与拒绝）都写一行，说明谁生效、谁被拒绝以及拒绝原因。
 */
@TableName("t_cc_trace_concurrent_log")
public class TraceConcurrentLog extends CreateOnlyEntity {
    private String opKey;
    private Long batchId;
    private String batchNo;
    private String nodeType;
    /** 生效节点ID（被拒记录为空） */
    private Long nodeId;
    /** WINNER / REJECTED */
    private String result;
    /** CONCURRENT / DIFFERENT_PAYLOAD / PUBLISHED_IMMUTABLE */
    private String rejectReason;
    private String payloadHash;
    private String requestSummary;
    private Long operatorId;
    private String operatorName;

    public String getOpKey() { return opKey; }
    public void setOpKey(String opKey) { this.opKey = opKey; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public String getNodeType() { return nodeType; }
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }
    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }
    public String getRequestSummary() { return requestSummary; }
    public void setRequestSummary(String requestSummary) { this.requestSummary = requestSummary; }
    public Long getOperatorId() { return operatorId; }
    public void setOperatorId(Long operatorId) { this.operatorId = operatorId; }
    public String getOperatorName() { return operatorName; }
    public void setOperatorName(String operatorName) { this.operatorName = operatorName; }
}
