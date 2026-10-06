package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 送达时窗规则版本（V10）。
 * 调整只能生成新版本：DRAFT 草稿（可编排工作日区间）-> EFFECTIVE 发布生效
 * -> SUPERSEDED 被新版本热替换。版本行与区间行发布后永不修改。
 */
@TableName("t_cc_delivery_window_rule")
public class DeliveryWindowRule extends BaseEntity {
    private Long orgId;
    private Integer versionNo;
    private String changeReason;
    /** DRAFT / EFFECTIVE / SUPERSEDED */
    private String status;
    private LocalDateTime publishTime;
    private Long publishBy;
    private String publishName;

    public Long getOrgId() { return orgId; }
    public void setOrgId(Long orgId) { this.orgId = orgId; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public String getChangeReason() { return changeReason; }
    public void setChangeReason(String changeReason) { this.changeReason = changeReason; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getPublishTime() { return publishTime; }
    public void setPublishTime(LocalDateTime publishTime) { this.publishTime = publishTime; }
    public Long getPublishBy() { return publishBy; }
    public void setPublishBy(Long publishBy) { this.publishBy = publishBy; }
    public String getPublishName() { return publishName; }
    public void setPublishName(String publishName) { this.publishName = publishName; }
}
