package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 补偿记录（部分成功时成对开立）。
 *
 * 冷链药品运输同步出现部分成功（部分回执失败/部分照片缺失）时，
 * 同时为电子签收侧（ESIGN）与离线回执侧（OFFLINE）各开一条补偿记录，
 * 两行 pairId 互指、彼此对应；该回执后续完整上传成功后，两侧补偿一并闭环。
 */
@TableName("t_cc_sign_compensation")
public class SignCompensation extends BaseEntity {
    private Long receiptId;
    private String receiptNo;
    private Long batchId;
    /** 触发补偿的同步/上传请求ID */
    private String syncRequestId;
    /** ESIGN 电子签收侧 / OFFLINE 离线回执侧 */
    private String side;
    /** 对方补偿记录ID（彼此对应） */
    private Long pairId;
    private String reason;
    /** OPEN 待补偿 / RESOLVED 已补偿闭环 */
    private String status;
    private String resolveNote;
    private LocalDateTime resolveTime;

    public Long getReceiptId() { return receiptId; }
    public void setReceiptId(Long receiptId) { this.receiptId = receiptId; }
    public String getReceiptNo() { return receiptNo; }
    public void setReceiptNo(String receiptNo) { this.receiptNo = receiptNo; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getSyncRequestId() { return syncRequestId; }
    public void setSyncRequestId(String syncRequestId) { this.syncRequestId = syncRequestId; }
    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }
    public Long getPairId() { return pairId; }
    public void setPairId(Long pairId) { this.pairId = pairId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getResolveNote() { return resolveNote; }
    public void setResolveNote(String resolveNote) { this.resolveNote = resolveNote; }
    public LocalDateTime getResolveTime() { return resolveTime; }
    public void setResolveTime(LocalDateTime resolveTime) { this.resolveTime = resolveTime; }
}
