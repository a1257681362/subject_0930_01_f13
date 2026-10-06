package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.time.LocalDateTime;

/**
 * 退货批次移动留痕（V11，只追加）。
 * 每次移动记录源库位、目标库位、操作人（ID+账号）与移动时间；
 * 入隔离 / 隔离区内移动 / 放行出库 / 销毁出库逐行留痕，不提供修改与删除。
 */
@TableName("t_cc_return_movement")
public class ReturnMovement extends CreateOnlyEntity {
    private Long returnId;
    /** ISOLATE_IN 入隔离 / MOVE 隔离区内移动 / RELEASE_OUT 放行出库 / DESTROY_OUT 销毁出库 */
    private String moveType;
    private String fromLocation;
    private String toLocation;
    /** 移动操作人（留痕） */
    private Long operatorId;
    private String operatorName;
    /** 移动时间 */
    private LocalDateTime moveTime;
    private String note;

    public Long getReturnId() { return returnId; }
    public void setReturnId(Long returnId) { this.returnId = returnId; }
    public String getMoveType() { return moveType; }
    public void setMoveType(String moveType) { this.moveType = moveType; }
    public String getFromLocation() { return fromLocation; }
    public void setFromLocation(String fromLocation) { this.fromLocation = fromLocation; }
    public String getToLocation() { return toLocation; }
    public void setToLocation(String toLocation) { this.toLocation = toLocation; }
    public Long getOperatorId() { return operatorId; }
    public void setOperatorId(Long operatorId) { this.operatorId = operatorId; }
    public String getOperatorName() { return operatorName; }
    public void setOperatorName(String operatorName) { this.operatorName = operatorName; }
    public LocalDateTime getMoveTime() { return moveTime; }
    public void setMoveTime(LocalDateTime moveTime) { this.moveTime = moveTime; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
