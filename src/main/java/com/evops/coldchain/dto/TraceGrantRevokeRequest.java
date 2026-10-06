package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/** 撤销岗位-机构追溯授权（只置 REVOKED 留痕，不删除授权行） */
public class TraceGrantRevokeRequest {
    @NotNull(message = "授权记录ID不能为空")
    private Long id;
    @NotBlank(message = "撤销原因不能为空")
    private String reason;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
