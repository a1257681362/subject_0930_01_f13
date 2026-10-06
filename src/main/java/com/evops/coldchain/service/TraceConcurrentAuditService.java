package com.evops.coldchain.service;

import com.evops.coldchain.CcConstants;
import com.evops.coldchain.entity.TraceConcurrentLog;
import com.evops.coldchain.entity.TraceNode;
import com.evops.coldchain.mapper.TraceConcurrentLogMapper;
import com.evops.common.UserContext;
import com.evops.common.UserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

/**
 * 并发仲裁审计落库（V13）。
 *
 * 独立新事务（REQUIRES_NEW）：落败请求的业务事务随后回滚/抛错，
 * 但“谁被拒绝、为什么拒绝、谁生效”的审计（t_cc_trace_concurrent_log +
 * 对应操作流水）必须保留下来，不随被拒业务回滚。
 */
@Service
public class TraceConcurrentAuditService {

    @Resource
    private TraceConcurrentLogMapper concurrentLogMapper;
    @Resource
    private OpLogService opLogService;

    /**
     * 生效方审计：与节点登记同一事务提交（要么节点+生效审计都在，要么都回滚，不产生孤儿审计）。
     * 落败方之所以能撞到唯一约束，是因为胜出方事务（含本审计）已经提交。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public TraceConcurrentLog winner(TraceNode node, String payloadHash, String summary, UserPrincipal operator) {
        return insert(node, payloadHash, summary, operator,
                CcConstants.CONC_WINNER, null);
    }

    /** 被拒方审计：独立事务提交，不随被拒业务回滚；同时留一条拒绝操作流水 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TraceConcurrentLog loser(TraceNode winnerNode, String opKey, Long batchId, String batchNo,
                                   String nodeType, String payloadHash, String summary,
                                   UserPrincipal operator, String rejectReason) {
        TraceConcurrentLog log = new TraceConcurrentLog();
        log.setOpKey(opKey);
        log.setBatchId(winnerNode == null ? batchId : winnerNode.getBatchId());
        log.setBatchNo(winnerNode == null ? batchNo : winnerNode.getBatchNo());
        log.setNodeType(nodeType);
        log.setNodeId(winnerNode == null ? null : winnerNode.getId());
        log.setResult(CcConstants.CONC_REJECTED);
        log.setRejectReason(rejectReason);
        log.setPayloadHash(payloadHash);
        log.setRequestSummary(summary);
        log.setOperatorId(operator.getId());
        log.setOperatorName(operator.getUsername());
        concurrentLogMapper.insert(log);

        String winnerNo = winnerNode == null ? "" : winnerNode.getNodeNo();
        opLogService.log(CcConstants.ENT_TRACE_CONCURRENT,
                winnerNode == null ? null : winnerNode.getId(),
                winnerNode == null ? batchId : winnerNode.getBatchId(),
                CcConstants.ACT_TRACE_CONCURRENT_REJECT,
                String.format("同一操作 opKey=%s 仲裁：账号[%s]的请求被拒绝（%s），生效节点=%s；请求摘要=%s",
                        opKey, operator.getUsername(), TraceService.rejectReasonName(rejectReason),
                        winnerNo, summary));
        return log;
    }

    private TraceConcurrentLog insert(TraceNode node, String payloadHash, String summary,
                                      UserPrincipal operator, String result, String rejectReason) {
        TraceConcurrentLog log = new TraceConcurrentLog();
        log.setOpKey(node.getOpKey());
        log.setBatchId(node.getBatchId());
        log.setBatchNo(node.getBatchNo());
        log.setNodeType(node.getNodeType());
        log.setNodeId(node.getId());
        log.setResult(result);
        log.setRejectReason(rejectReason);
        log.setPayloadHash(payloadHash);
        log.setRequestSummary(summary);
        log.setOperatorId(operator.getId());
        log.setOperatorName(operator.getUsername());
        concurrentLogMapper.insert(log);
        return log;
    }

    /** 便利：当前操作者 */
    public static UserPrincipal currentOperator() {
        return UserContext.current();
    }
}
