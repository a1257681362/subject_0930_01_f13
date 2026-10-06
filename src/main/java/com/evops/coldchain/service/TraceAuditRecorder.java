package com.evops.coldchain.service;

import com.evops.coldchain.entity.TraceOpAudit;
import com.evops.coldchain.mapper.TraceOpAuditMapper;
import com.evops.common.UserContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * 追溯并发仲裁审计记录器（V13）。
 *
 * LOST（被拒绝）/REPLAY（幂等重放）审计必须在独立事务（REQUIRES_NEW）中落库：
 * 被拒绝请求的主业务事务会回滚/抛出，但“谁生效、谁被拒绝、为何拒绝”的审计必须保留，
 * 不随主事务回滚消失。
 */
@Component
public class TraceAuditRecorder {

    @Resource
    private TraceOpAuditMapper auditMapper;

    /** 独立事务写审计：主业务事务回滚不影响本行保留（LOST 被拒绝/REPLAY 重放走这里） */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDetached(String requestId, String action, String result,
                               Long batchId, String batchNo, String nodeType, String opKey,
                               Long nodeId, String rejectReason, String filterJson) {
        insert(requestId, action, result, batchId, batchNo, nodeType, opKey, nodeId, rejectReason, filterJson);
    }

    /**
     * 随主事务写审计（WIN 生效/INFO 一般留痕/EXPORT/GRANT）：
     * 生效事实与审计同生共死，主事务回滚则不留下指向不存在节点的悬空审计。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void recordJoined(String requestId, String action, String result,
                             Long batchId, String batchNo, String nodeType, String opKey,
                             Long nodeId, String rejectReason, String filterJson) {
        insert(requestId, action, result, batchId, batchNo, nodeType, opKey, nodeId, rejectReason, filterJson);
    }

    private void insert(String requestId, String action, String result,
                        Long batchId, String batchNo, String nodeType, String opKey,
                        Long nodeId, String rejectReason, String filterJson) {
        TraceOpAudit audit = new TraceOpAudit();
        audit.setRequestId(requestId);
        audit.setAction(action);
        audit.setResult(result);
        audit.setBatchId(batchId);
        audit.setBatchNo(batchNo);
        audit.setNodeType(nodeType);
        audit.setOpKey(opKey);
        audit.setNodeId(nodeId);
        audit.setOperatorId(UserContext.currentId());
        audit.setOperatorName(UserContext.currentName());
        audit.setRejectReason(rejectReason);
        audit.setFilterJson(filterJson);
        audit.setCreateTime(LocalDateTime.now());
        auditMapper.insert(audit);
    }
}
