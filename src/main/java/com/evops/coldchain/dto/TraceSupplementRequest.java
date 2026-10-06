package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 已发布追溯节点的补充请求（V13）。
 * 补录只能追加补充行（NOTE 备注 / FILE 附件 / CORRECTION_REF 更正依据），
 * 不改变节点已发布状态快照原值。
 */
public class TraceSupplementRequest {
    @NotBlank(message = "requestId 不能为空（补充幂等键）")
    private String requestId;
    @NotNull(message = "追溯节点ID不能为空")
    private Long nodeId;
    /** NOTE / FILE / CORRECTION_REF */
    @NotBlank(message = "补充类型不能为空（NOTE/FILE/CORRECTION_REF）")
    private String supplementType;
    @NotBlank(message = "补充内容不能为空")
    private String content;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Long getNodeId() { return nodeId; }
    public void setNodeId(Long nodeId) { this.nodeId = nodeId; }
    public String getSupplementType() { return supplementType; }
    public void setSupplementType(String supplementType) { this.supplementType = supplementType; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
