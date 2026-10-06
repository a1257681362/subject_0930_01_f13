package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.time.LocalDateTime;

/**
 * 药品批次追溯链路节点（V13）。
 *
 * 六类节点：PURCHASE 采购 / WAREHOUSE 仓储 / SHIP 发运 / TRANSIT 中转 / SIGN 签收 / RETURN 退货。
 * 发布当刻由系统采集业务表当时状态写入 {@link #statusSnapshot}（JSON），此后只追加、
 * 永不修改、不删除；补录只能漏记补建（source=BACKFILL）或对已发布节点追加补充行，
 * 已发布快照原值不改变。
 *
 * opKey = 批次|节点类型|业务键，数据库唯一约束兜底并发：冷链同一操作并发发起时
 * 恰好一个请求生效，其余因唯一约束冲突被拒绝（审计见表 t_cc_trace_op_audit）。
 */
@TableName("t_cc_trace_node")
public class TraceNode extends CreateOnlyEntity {
    private String nodeNo;
    /** 链路锚：发货单ID（采购节点发布时批次尚未生成，仅凭 order_id 串联全链路） */
    private Long orderId;
    private Long batchId;
    private String batchNo;
    /** PURCHASE/WAREHOUSE/SHIP/TRANSIT/SIGN/RETURN */
    private String nodeType;
    private String bizKey;
    /** 仲裁键 = 批次|节点类型|业务键 */
    private String opKey;

    private String orgName;
    private String contactName;
    private String contactPhone;
    /** 发布当刻业务状态原文 */
    private String bizStatus;
    /** AUTO 业务流程自动 / MANUAL 手工发布 / BACKFILL 漏记补建 */
    private String source;
    private LocalDateTime occurredTime;
    private String remark;
    /** 发布当刻完整状态快照（JSON，永不修改） */
    private String statusSnapshot;

    public String getNodeNo() { return nodeNo; }
    public void setNodeNo(String nodeNo) { this.nodeNo = nodeNo; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public String getNodeType() { return nodeType; }
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }
    public String getBizKey() { return bizKey; }
    public void setBizKey(String bizKey) { this.bizKey = bizKey; }
    public String getOpKey() { return opKey; }
    public void setOpKey(String opKey) { this.opKey = opKey; }
    public String getOrgName() { return orgName; }
    public void setOrgName(String orgName) { this.orgName = orgName; }
    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }
    public String getBizStatus() { return bizStatus; }
    public void setBizStatus(String bizStatus) { this.bizStatus = bizStatus; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public LocalDateTime getOccurredTime() { return occurredTime; }
    public void setOccurredTime(LocalDateTime occurredTime) { this.occurredTime = occurredTime; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getStatusSnapshot() { return statusSnapshot; }
    public void setStatusSnapshot(String statusSnapshot) { this.statusSnapshot = statusSnapshot; }
}
