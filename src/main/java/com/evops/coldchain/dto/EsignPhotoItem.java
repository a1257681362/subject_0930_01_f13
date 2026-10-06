package com.evops.coldchain.dto;

import java.time.LocalDateTime;

/**
 * 回执照片要素（恢复网络后逐张补传）。
 * 单张照片缺名称/地址时不阻断整单：按“部分成功”跳过并开成对补偿记录。
 */
public class EsignPhotoItem {
    private String photoName;
    private String photoUrl;
    private String sha256;
    /** 拍摄设备时间（可空） */
    private LocalDateTime deviceTime;

    public String getPhotoName() { return photoName; }
    public void setPhotoName(String photoName) { this.photoName = photoName; }
    public String getPhotoUrl() { return photoUrl; }
    public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public LocalDateTime getDeviceTime() { return deviceTime; }
    public void setDeviceTime(LocalDateTime deviceTime) { this.deviceTime = deviceTime; }
}
