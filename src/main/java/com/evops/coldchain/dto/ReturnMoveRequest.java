package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;

/**
 * 退货批次隔离区内库位移动请求（V11）。
 * 只允许在隔离区内移动（目标库位必须仍为隔离库位），不能借此出库；
 * 每次移动记录库位、人员和时间。
 */
public class ReturnMoveRequest {
    @NotBlank(message = "目标库位不能为空")
    private String toLocation;
    private String note;

    public String getToLocation() { return toLocation; }
    public void setToLocation(String toLocation) { this.toLocation = toLocation; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
