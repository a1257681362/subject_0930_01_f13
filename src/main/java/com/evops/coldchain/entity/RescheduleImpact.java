package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.math.BigDecimal;

/**
 * 改期受影响明细（V10，只追加）。
 * impactType：
 * - TEMP_ZONE 温控区间：受改期影响的路线分段快照（或全程档案温区 segmentSeq=0），
 *   携带段序号、允许温区、责任人；
 * - CARRIER_NOTICE 承运通知：需要重新通知的承运对象（承运商/驾驶员、关联锁价单）；
 * - TODO_OWNER 待办责任人：需要跟进的责任岗位/具体责任人。
 */
@TableName("t_cc_reschedule_impact")
public class RescheduleImpact extends CreateOnlyEntity {
    private Long rescheduleId;
    private Long batchId;
    private String impactType;
    private Long refId;
    private Integer segmentSeq;
    private BigDecimal tempMin;
    private BigDecimal tempMax;
    private String ownerName;
    private String ownerPhone;
    private String detail;

    public Long getRescheduleId() { return rescheduleId; }
    public void setRescheduleId(Long rescheduleId) { this.rescheduleId = rescheduleId; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getImpactType() { return impactType; }
    public void setImpactType(String impactType) { this.impactType = impactType; }
    public Long getRefId() { return refId; }
    public void setRefId(Long refId) { this.refId = refId; }
    public Integer getSegmentSeq() { return segmentSeq; }
    public void setSegmentSeq(Integer segmentSeq) { this.segmentSeq = segmentSeq; }
    public BigDecimal getTempMin() { return tempMin; }
    public void setTempMin(BigDecimal tempMin) { this.tempMin = tempMin; }
    public BigDecimal getTempMax() { return tempMax; }
    public void setTempMax(BigDecimal tempMax) { this.tempMax = tempMax; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public String getOwnerPhone() { return ownerPhone; }
    public void setOwnerPhone(String ownerPhone) { this.ownerPhone = ownerPhone; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
}
