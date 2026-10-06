package com.evops.coldchain.dto;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.util.List;

/**
 * 送达时窗规则草稿请求（V10）。
 * 规则调整只能生成新版本：保存草稿只覆盖该机构唯一 DRAFT，发布后版本与区间行永不修改。
 * 区间时间用 "HH:mm" 表达（如 "09:00"~"11:30"）；同一工作日区间不得重叠。
 */
public class WindowRuleDraftRequest {
    private String changeReason;
    @NotNull(message = "工作日可接收区间不能为空")
    private List<SlotDraft> slots;

    public String getChangeReason() { return changeReason; }
    public void setChangeReason(String changeReason) { this.changeReason = changeReason; }
    public List<SlotDraft> getSlots() { return slots; }
    public void setSlots(List<SlotDraft> slots) { this.slots = slots; }

    public static class SlotDraft {
        /** 星期几：1=周一 ... 7=周日 */
        @NotNull(message = "星期几不能为空")
        @Min(value = 1, message = "星期几取值 1(周一)~7(周日)")
        @Max(value = 7, message = "星期几取值 1(周一)~7(周日)")
        private Integer weekday;
        @NotBlank(message = "区间起点不能为空")
        private String startTime;
        @NotBlank(message = "区间终点不能为空")
        private String endTime;

        public Integer getWeekday() { return weekday; }
        public void setWeekday(Integer weekday) { this.weekday = weekday; }
        public String getStartTime() { return startTime; }
        public void setStartTime(String startTime) { this.startTime = startTime; }
        public String getEndTime() { return endTime; }
        public void setEndTime(String endTime) { this.endTime = endTime; }
    }
}
