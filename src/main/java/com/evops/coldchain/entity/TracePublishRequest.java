package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

/**
 * 追溯节点发布/补录的 requestId 幂等登记（V13）。
 * 同 requestId 同载荷重放直接返回首次结果（replay=true），不新增节点/补充/审计；
 * 同 requestId 不同载荷拒绝。
 */
@TableName("t_cc_trace_publish_request")
public class TracePublishRequest extends CreateOnlyEntity {
    private String requestId;
    /** PUBLISH / SUPPLEMENT */
    private String requestKind;
    private String payloadHash;
    /** 首次处理结果 JSON（重放原样返回） */
    private String resultJson;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getRequestKind() { return requestKind; }
    public void setRequestKind(String requestKind) { this.requestKind = requestKind; }
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
}
