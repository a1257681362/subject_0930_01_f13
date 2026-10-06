package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.time.LocalDateTime;

/**
 * 改期承运通知记录（V10，只追加）。
 * 改期生效（申请即生效或审批同意）后向承运方投递；每次发送/重试独立一行，
 * 失败必须记录失败原因；attemptNo 同次改期内递增。
 */
@TableName("t_cc_carrier_notify_log")
public class CarrierNotifyLog extends CreateOnlyEntity {
    private Long rescheduleId;
    private Long batchId;
    private Integer attemptNo;
    /** SITE / WEBHOOK */
    private String channel;
    private String targetName;
    private String targetPhone;
    private String endpoint;
    /** SUCCESS / FAILED */
    private String sendStatus;
    private String failReason;
    private String retryReason;
    private LocalDateTime sendTime;

    public Long getRescheduleId() { return rescheduleId; }
    public void setRescheduleId(Long rescheduleId) { this.rescheduleId = rescheduleId; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public Integer getAttemptNo() { return attemptNo; }
    public void setAttemptNo(Integer attemptNo) { this.attemptNo = attemptNo; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getTargetName() { return targetName; }
    public void setTargetName(String targetName) { this.targetName = targetName; }
    public String getTargetPhone() { return targetPhone; }
    public void setTargetPhone(String targetPhone) { this.targetPhone = targetPhone; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getSendStatus() { return sendStatus; }
    public void setSendStatus(String sendStatus) { this.sendStatus = sendStatus; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
    public String getRetryReason() { return retryReason; }
    public void setRetryReason(String retryReason) { this.retryReason = retryReason; }
    public LocalDateTime getSendTime() { return sendTime; }
    public void setSendTime(LocalDateTime sendTime) { this.sendTime = sendTime; }
}
