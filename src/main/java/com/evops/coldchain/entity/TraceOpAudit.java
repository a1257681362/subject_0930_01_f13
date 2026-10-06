package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 追溯操作与并发仲裁审计（V13，只追加）。
 *
 * 冷链药品运输的同一操作并发发起时：唯一约束仲裁出一个 WIN（生效），其余 LOST（被拒绝），
 * 双方操作者、时间、opKey 与拒绝原因（被谁、哪条已发布节点）逐行保留；
 * 导出动作记录全部过滤条件（filter_json）。审计由独立事务写入，业务主事务回滚不影响审计保留。
 */
@TableName("t_cc_trace_op_audit")
public class TraceOpAudit {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String requestId;
    /** PUBLISH/SUPPLEMENT/EXPORT/GRANT/REVOKE_GRANT/REPLAY */
    private String action;
    /** WIN 生效 / LOST 被拒绝 / INFO 一般留痕 / REPLAY 重放 */
    private String result;
    private Long batchId;
    private String batchNo;
    private String nodeType;
    private String opKey;
    /** WIN 指向新生效节点；LOST 指向对方已生效节点 */
    private Long nodeId;
    private Long operatorId;
    private String operatorName;
    private String rejectReason;
    private String filterJson;
    private LocalDateTime createTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public String getNodeType() { return nodeType; }
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }
    public String getOpKey() { return opKey; }
    public void setOpKey(String opKey) { this.opKey = opKey; }
    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public Long getOperatorId() { return operatorId; }
    public void setOperatorId(Long operatorId) { this.operatorId = operatorId; }
    public String getOperatorName() { return operatorName; }
    public void setOperatorName(String operatorName) { this.operatorName = operatorName; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }
    public String getFilterJson() { return filterJson; }
    public void setFilterJson(String filterJson) { this.filterJson = filterJson; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
