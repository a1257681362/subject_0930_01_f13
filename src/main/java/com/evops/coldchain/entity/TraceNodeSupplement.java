package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

/**
 * 已发布追溯节点的补充记录（V13，只追加）。
 * 节点补录不能改变已发布快照：对已发布节点的任何补充只能新增本行，
 * status_snapshot 原值永不修改。
 */
@TableName("t_cc_trace_node_supplement")
public class TraceNodeSupplement extends CreateOnlyEntity {
    private Long nodeId;
    private Long batchId;
    /** NOTE 备注 / FILE 附件 / CORRECTION_REF 更正依据 */
    private String supplementType;
    private String content;

    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getSupplementType() { return supplementType; }
    public void setSupplementType(String supplementType) { this.supplementType = supplementType; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
