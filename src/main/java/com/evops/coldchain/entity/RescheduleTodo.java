package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 改期待办（V10）。
 * 改期生效后按受影响责任人生成（调度/承运/收货/质量复核）；
 * PENDING -> DONE 只进不退，确认只写确认字段，不删除待办事实。
 */
@TableName("t_cc_reschedule_todo")
public class RescheduleTodo extends BaseEntity {
    private Long rescheduleId;
    private Long batchId;
    /** DISPATCHER / CARRIER / RECEIVER / QA */
    private String ownerRole;
    private String ownerName;
    private String content;
    /** PENDING / DONE */
    private String status;
    private String confirmNote;
    private Long confirmBy;
    private String confirmName;
    private LocalDateTime confirmTime;

    public Long getRescheduleId() { return rescheduleId; }
    public void setRescheduleId(Long rescheduleId) { this.rescheduleId = rescheduleId; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getOwnerRole() { return ownerRole; }
    public void setOwnerRole(String ownerRole) { this.ownerRole = ownerRole; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getConfirmNote() { return confirmNote; }
    public void setConfirmNote(String confirmNote) { this.confirmNote = confirmNote; }
    public Long getConfirmBy() { return confirmBy; }
    public void setConfirmBy(Long confirmBy) { this.confirmBy = confirmBy; }
    public String getConfirmName() { return confirmName; }
    public void setConfirmName(String confirmName) { this.confirmName = confirmName; }
    public LocalDateTime getConfirmTime() { return confirmTime; }
    public void setConfirmTime(LocalDateTime confirmTime) { this.confirmTime = confirmTime; }
}
