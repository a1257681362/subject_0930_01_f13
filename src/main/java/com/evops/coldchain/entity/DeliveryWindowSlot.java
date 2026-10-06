package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

/**
 * 工作日可接收区间（属于某个时窗规则版本，只追加）。
 * weekday 1=周一 ... 7=周日；startMinute/endMinute 为当日分钟数（0~1440）。
 * 同一工作日内区间不得重叠；版本发布后区间行永不修改。
 */
@TableName("t_cc_delivery_window_slot")
public class DeliveryWindowSlot extends CreateOnlyEntity {
    private Long ruleId;
    private Integer weekday;
    private Integer startMinute;
    private Integer endMinute;

    public Long getRuleId() { return ruleId; }
    public void setRuleId(Long ruleId) { this.ruleId = ruleId; }
    public Integer getWeekday() { return weekday; }
    public void setWeekday(Integer weekday) { this.weekday = weekday; }
    public Integer getStartMinute() { return startMinute; }
    public void setStartMinute(Integer startMinute) { this.startMinute = startMinute; }
    public Integer getEndMinute() { return endMinute; }
    public void setEndMinute(Integer endMinute) { this.endMinute = endMinute; }
}
