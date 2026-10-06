package com.evops.coldchain.dto;

import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 电子签收上传请求（单回执）。
 *
 * 司机离线时先在端上保存签收草稿并生成回执编号；恢复网络后签名、照片、定位
 * 可分多次随本请求上传，服务端按回执编号（receiptNo）合并进同一条回执：
 * - receiptNo 为空（在线直连）时由服务端发号；
 * - 同一回执编号重复上传只补齐缺失要素，核心内容不一致直接拒绝；
 * - requestId 为幂等键：同键同载荷重放返回首次结果，同键不同载荷拒绝。
 */
public class EsignUploadRequest {
    /** 幂等键（必填，建议 UUID；批量同步时由同步请求统一携带，单项忽略） */
    private String requestId;
    @NotNull(message = "运输批次ID不能为空")
    private Long batchId;
    /** 回执编号：离线端生成；在线签收可空（服务端发号） */
    private String receiptNo;
    /** ONLINE 在线签收 / OFFLINE 离线回执补传（默认 ONLINE） */
    private String source;
    private String signBy;
    private String signRemark;
    // 实物核对字段（沿用签收闭环口径，与批次快照核对）
    private String receivedApprovalNo;
    private String receivedPackageSpec;
    private Integer confirmQty;
    /** 设备时间（离线签收当刻，端上报；与服务器时间并列展示） */
    private LocalDateTime deviceTime;
    private String signatureName;
    private String signatureUrl;
    private BigDecimal locationLng;
    private BigDecimal locationLat;
    private String locationAddress;
    private List<EsignPhotoItem> photos;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getReceiptNo() { return receiptNo; }
    public void setReceiptNo(String receiptNo) { this.receiptNo = receiptNo; }
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
    public List<EsignPhotoItem> getPhotos() { return photos; }
    public void setPhotos(List<EsignPhotoItem> photos) { this.photos = photos; }
}
