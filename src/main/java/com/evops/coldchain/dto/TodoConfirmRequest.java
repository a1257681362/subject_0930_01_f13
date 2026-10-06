package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;

/** 改期待办确认请求（PENDING -> DONE，确认事实只进不退） */
public class TodoConfirmRequest {
    @NotBlank(message = "确认说明不能为空")
    private String confirmNote;

    public String getConfirmNote() { return confirmNote; }
    public void setConfirmNote(String confirmNote) { this.confirmNote = confirmNote; }
}
