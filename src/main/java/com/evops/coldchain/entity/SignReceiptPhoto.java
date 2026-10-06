package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.time.LocalDateTime;

/**
 * 回执照片（只追加）：恢复网络后逐张补传，同回执内 photo_url 唯一保证重传幂等。
 * 照片行一旦落库不提供更新/删除路径。
 */
@TableName("t_cc_sign_receipt_photo")
public class SignReceiptPhoto extends CreateOnlyEntity {
    private Long receiptId;
    private Long batchId;
    private Integer photoSeq;
    private String photoName;
    private String photoUrl;
    private String sha256;
    /** 拍摄设备时间（可空） */
    private LocalDateTime deviceTime;

    public Long getReceiptId() { return receiptId; }
    public void setReceiptId(Long receiptId) { this.receiptId = receiptId; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public Integer getPhotoSeq() { return photoSeq; }
    public void setPhotoSeq(Integer photoSeq) { this.photoSeq = photoSeq; }
    public String getPhotoName() { return photoName; }
    public void setPhotoName(String photoName) { this.photoName = photoName; }
    public String getPhotoUrl() { return photoUrl; }
    public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public LocalDateTime getDeviceTime() { return deviceTime; }
    public void setDeviceTime(LocalDateTime deviceTime) { this.deviceTime = deviceTime; }
}
