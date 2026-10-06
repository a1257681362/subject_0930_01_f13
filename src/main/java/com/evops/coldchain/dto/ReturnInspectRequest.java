package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;

/**
 * 退货质量检验请求（V11，质量人员 QA）。
 * 完成检验才能选择放行、销毁或转待查；检验记录只追加。
 */
public class ReturnInspectRequest {
    /** PASS 合格 / FAIL 不合格 */
    @NotBlank(message = "检验结论不能为空")
    private String inspectResult;
    @NotBlank(message = "检验说明不能为空")
    private String inspectNote;

    public String getInspectResult() { return inspectResult; }
    public void setInspectResult(String inspectResult) { this.inspectResult = inspectResult; }
    public String getInspectNote() { return inspectNote; }
    public void setInspectNote(String inspectNote) { this.inspectNote = inspectNote; }
}
