package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 电子签收回执（离线草稿与在线签收的统一载体）。
 *
 * 回执编号（receiptNo）是合并锚点：司机离线保存草稿后，恢复网络时签名、照片、定位
 * 可分多次上传，服务端按回执编号合并进同一行。签收时间并列保留 deviceTime（设备时间）
 * 与 serverTime（服务器接收时间），不做互相覆盖。人工修正不修改本行——
 * 修正记录只追加到 t_cc_sign_correction。
 */
@TableName("t_cc_sign_receipt")
public class SignReceipt extends BaseEntity {
    private String receiptNo;
    private Long batchId;
    /** ONLINE 在线签收 / OFFLINE 离线回执补传 */
    private String source;
    private String signBy;
    private String signRemark;
    // 实物核对字段（与批次快照核对）
    private String receivedApprovalNo;
    private String receivedPackageSpec;
    private Integer confirmQty;
    // 签收时间并列：设备时间 + 服务器时间
    private LocalDateTime deviceTime;
    private LocalDateTime serverTime;
    // 签名与定位（恢复网络后上传，先到先写、缺失后补）
    private String signatureName;
    private String signatureUrl;
    private BigDecimal locationLng;
    private BigDecimal locationLat;
    private String locationAddress;
    /** 核心内容摘要：判定“两份不同签收”的依据 */
    private String contentHash;
    /** DRAFT / COMPLETE / EFFECTIVE / REJECTED / DUPLICATE */
    private String status;
    /** 主管裁决意见（裁决时追加，不改原始要素） */
    private String adjudicateNote;

    public String getReceiptNo() { return receiptNo; }
    public void setReceiptNo(String receiptNo) { this.receiptNo = receiptNo; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getSignBy() { return signBy; }
    public void setSignBy(String signBy) { this.signBy = signBy; }
    public String getSignRemark() { return signRemark; }
    public void setSignRemark(String signRemark) { this.signRemark = signRemark; }
    public String getReceivedApprovalNo() { return receivedApprovalNo; }
    public void setReceivedApprovalNo(String receivedApprovalNo) { this.receivedApprovalNo = receivedApprovalNo; }
    public String getReceivedPackageSpec() { return receivedPackageSpec; }
    public void setReceivedPackageSpec(String receivedPackageSpec) { this.receivedPackageSpec = receivedPackageSpec; }
    public Integer getConfirmQty() { return confirmQty; }
    public void setConfirmQty(Integer confirmQty) { this.confirmQty = confirmQty; }
    public LocalDateTime getDeviceTime() { return deviceTime; }
    public void setDeviceTime(LocalDateTime deviceTime) { this.deviceTime = deviceTime; }
    public LocalDateTime getServerTime() { return serverTime; }
    public void setServerTime(LocalDateTime serverTime) { this.serverTime = serverTime; }
    public String getSignatureName() { return signatureName; }
    public void setSignatureName(String signatureName) { this.signatureName = signatureName; }
    public String getSignatureUrl() { return signatureUrl; }
    public void setSignatureUrl(String signatureUrl) { this.signatureUrl = signatureUrl; }
    public BigDecimal getLocationLng() { return locationLng; }
    public void setLocationLng(BigDecimal locationLng) { this.locationLng = locationLng; }
    public BigDecimal getLocationLat() { return locationLat; }
    public void setLocationLat(BigDecimal locationLat) { this.locationLat = locationLat; }
    public String getLocationAddress() { return locationAddress; }
    public void setLocationAddress(String locationAddress) { this.locationAddress = locationAddress; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getAdjudicateNote() { return adjudicateNote; }
    public void setAdjudicateNote(String adjudicateNote) { this.adjudicateNote = adjudicateNote; }
}
