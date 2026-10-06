package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 节假日 / 临时闭馆规则维护请求（V10，与时窗规则版本独立保存）。
 * - HOLIDAY：holidayYear + holidayDate 必填，整日不可接收；
 * - CLOSED：holidayDate（单日闭馆）或 closedFrom+closedTo（一段时间闭馆）二选一。
 */
public class HolidayRuleRequest {
    /** HOLIDAY / CLOSED */
    @NotBlank(message = "规则类别不能为空")
    private String ruleKind;
    private Integer holidayYear;
    private LocalDate holidayDate;
    private LocalDateTime closedFrom;
    private LocalDateTime closedTo;
    @NotBlank(message = "节假日名称/闭馆事由不能为空")
    private String holidayName;

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
}
