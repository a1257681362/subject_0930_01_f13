package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

/**
 * 上传/同步幂等登记：同一 requestId 只做一次业务，
 * 同键同载荷重放返回首次结果（resultJson 原样回放），同键不同载荷拒绝。
 */
@TableName("t_cc_esign_request")
public class EsignRequest extends CreateOnlyEntity {
    private String requestId;
    /** UPLOAD 单回执上传 / SYNC 批量同步 */
    private String requestType;
    private String payloadHash;
    private String resultJson;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getRequestType() { return requestType; }
    public void setRequestType(String requestType) { this.requestType = requestType; }
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }
    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }
}
