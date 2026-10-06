package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 节假日 / 临时闭馆规则（V10，与时窗规则版本独立保存）。
 * - HOLIDAY：法定/机构节假日，holidayYear + holidayDate 指定整日不可接收；
 * - CLOSED：临时闭馆，可单日（holidayDate）或一段时间（closedFrom~closedTo）。
 * 一旦参与过已生效时窗结论（派车承诺或生效改期 ETA），frozen=TRUE 永久冻结，
 * 不能修改、不能作废/删除——旧结论按当时的节假日集得出，历史版本可追查。
 */
@TableName("t_cc_holiday_rule")
public class HolidayRule extends BaseEntity {
    private Long orgId;
    /** HOLIDAY / CLOSED */
    private String ruleKind;
    private Integer holidayYear;
    private LocalDate holidayDate;
    private LocalDateTime closedFrom;
    private LocalDateTime closedTo;
    private String holidayName;
    /** ACTIVE / VOID */
    private String status;
    private Boolean frozen;
    private LocalDateTime frozenTime;
    private Long frozenBy;
    private Long frozenBatchId;
    private String voidReason;
    private LocalDateTime voidTime;

    public Long getOrgId() { return orgId; }
    public void setOrgId(Long orgId) { this.orgId = orgId; }
    public String getRuleKind() { return ruleKind; }
    public void setRuleKind(String ruleKind) { this.ruleKind = ruleKind; }
    public Integer getHolidayYear() { return holidayYear; }
    public void setHolidayYear(Integer holidayYear) { this.holidayYear = holidayYear; }
    public LocalDate getHolidayDate() { return holidayDate; }
    public void setHolidayDate(LocalDate holidayDate) { this.holidayDate = holidayDate; }
    public LocalDateTime getClosedFrom() { return closedFrom; }
    public void setClosedFrom(LocalDateTime closedFrom) { this.closedFrom = closedFrom; }
    public LocalDateTime getClosedTo() { return closedTo; }
    public void setClosedTo(LocalDateTime closedTo) { this.closedTo = closedTo; }
    public String getHolidayName() { return holidayName; }
    public void setHolidayName(String holidayName) { this.holidayName = holidayName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Boolean getFrozen() { return frozen; }
    public void setFrozen(Boolean frozen) { this.frozen = frozen; }
    public LocalDateTime getFrozenTime() { return frozenTime; }
    public void setFrozenTime(LocalDateTime frozenTime) { this.frozenTime = frozenTime; }
    public Long getFrozenBy() { return frozenBy; }
    public void setFrozenBy(Long frozenBy) { this.frozenBy = frozenBy; }
    public Long getFrozenBatchId() { return frozenBatchId; }
    public void setFrozenBatchId(Long frozenBatchId) { this.frozenBatchId = frozenBatchId; }
    public String getVoidReason() { return voidReason; }
    public void setVoidReason(String voidReason) { this.voidReason = voidReason; }
    public LocalDateTime getVoidTime() { return voidTime; }
    public void setVoidTime(LocalDateTime voidTime) { this.voidTime = voidTime; }
}
