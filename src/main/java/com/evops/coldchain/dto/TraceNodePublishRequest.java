package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 追溯节点发布/漏记补建请求（V13）。
 * requestId 幂等：同键同载荷重放返回首次结果，同键不同载荷拒绝。
 * 已发布节点不能靠再次发布改变；对已发布节点的补充走 TraceSupplementRequest。
 */
public class TraceNodePublishRequest {
    @NotBlank(message = "requestId 不能为空（发布/补录幂等键）")
    private String requestId;
    @NotNull(message = "运输批次ID不能为空")
    private Long batchId;
    /** PURCHASE/WAREHOUSE/SHIP/TRANSIT/SIGN/RETURN */
    @NotBlank(message = "节点类型不能为空（PURCHASE/WAREHOUSE/SHIP/TRANSIT/SIGN/RETURN）")
    private String nodeType;
    /** 节点业务键：默认 PRIMARY；中转=交接单号、退货=退货单号 */
    private String bizKey;
    /** 业务发生时间；漏记补建可填历史时间，为空取当前时间 */
    private LocalDateTime occurredTime;
    private String orgName;
    private String contactName;
    private String contactPhone;
    private String remark;
    /** 中转节点补建时逐签固化的封签号 */
    private List<String> sealNos;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getNodeType() { return nodeType; }
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }
    public String getBizKey() { return bizKey; }
    public void setBizKey(String bizKey) { this.bizKey = bizKey; }
    public LocalDateTime getOccurredTime() { return occurredTime; }
    public void setOccurredTime(LocalDateTime occurredTime) { this.occurredTime = occurredTime; }
    public String getOrgName() { return orgName; }
    public void setOrgName(String orgName) { this.orgName = orgName; }
    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
    public String getRemark() { return remark; }
    public void setRemark(String string) { this.remark = string; }
    public List<String> getSealNos() { return sealNos; }
    public void setSealNos(List<String> sealNos) { this.sealNos = sealNos; }
}
