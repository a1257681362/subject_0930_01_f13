package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;

/**
 * 退货批次入隔离库位请求（V11）。
 * 进入隔离库位后不能再次出库：唯一出库路径是双人复核通过的放行/销毁决定。
 */
public class ReturnIsolateRequest {
    @NotBlank(message = "隔离库位不能为空")
    private String isolationLocation;
    private String note;

    public String getIsolationLocation() { return isolationLocation; }
    public void setIsolationLocation(String isolationLocation) { this.isolationLocation = isolationLocation; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
