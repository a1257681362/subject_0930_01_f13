package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.time.LocalDateTime;

/**
 * 退货质量检验记录（V11，只追加）。
 * 质量人员（QA）完成检验后才能提出处理决定；转待查后须重新检验。
 * 每次检验独立一行，历史结论不覆盖。
 */
@TableName("t_cc_return_inspection")
public class ReturnInspection extends CreateOnlyEntity {
    private Long returnId;
    /** PASS 合格 / FAIL 不合格 */
    private String inspectResult;
    /** 检验说明（必填） */
    private String inspectNote;
    /** 检验人（质量人员） */
    private Long inspectorId;
    private String inspectorName;
    private LocalDateTime inspectTime;

    public Long getReturnId() { return returnId; }
    public void setReturnId(Long returnId) { this.returnId = returnId; }
    public String getInspectResult() { return inspectResult; }
    public void setInspectResult(String inspectResult) { this.inspectResult = inspectResult; }
    public String getInspectNote() { return inspectNote; }
    public void setInspectNote(String inspectNote) { this.inspectNote = inspectNote; }
    public Long getInspectorId() { return inspectorId; }
    public void setInspectorId(Long inspectorId) { this.inspectorId = inspectorId; }
    public String getInspectorName() { return inspectorName; }
    public void setInspectorName(String inspectorName) { this.inspectorName = inspectorName; }
    public LocalDateTime getInspectTime() { return inspectTime; }
    public void setInspectTime(LocalDateTime inspectTime) { this.inspectTime = inspectTime; }
}
