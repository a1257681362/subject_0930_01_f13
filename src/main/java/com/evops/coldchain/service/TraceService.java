package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.TraceNodeCreateRequest;
import com.evops.coldchain.dto.TraceNodeReviseRequest;
import com.evops.coldchain.entity.TraceConcurrentLog;
import com.evops.coldchain.entity.TraceNode;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.TraceConcurrentLogMapper;
import com.evops.coldchain.mapper.TraceNodeMapper;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import com.evops.common.UserPrincipal;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 药品批次追溯图服务（V13）。
 *
 * 链路：采购 PURCHASE -> 仓储 WAREHOUSE -> 发运 DISPATCH -> 中转 TRANSIT（多次）
 * -> 签收 SIGN -> 退货 RETURN（可缺省/多次）。
 *
 * 快照规则：
 * - 节点先以 DRAFT 落库，可反复修订（revision 递增）；发布时把“当时状态”整体固化为
 *   snapshot_json + snapshot_hash（含发布当刻批次状态）。已发布快照只读——
 *   补录/修订请求一律拒绝（PUBLISHED_IMMUTABLE），新事实只能另追加节点。
 *
 * 并发规则：
 * - 同一操作（相同 opKey）并发发起时，t_cc_trace_node 唯一约束在数据库层仲裁，
 *   先提交者 WINNER 生效，落败者 REJECTED（CONCURRENT）；双方都写 t_cc_trace_concurrent_log。
 * - 同键同载荷是合法重放，幂等返回首个节点；同键不同载荷拒绝（DIFFERENT_PAYLOAD）。
 *
 * 授权规则：
 * - 链路/反查只返回当前账号授权机构范围内的节点，未授权机构不可见；
 *   联系方式按授权决定明文/脱敏（前3后2）。
 *
 * 导出：同口径查询 + CSV 头部标明数据截止时间、全部过滤条件与联系方式口径。
 */
@Service
public class TraceService {

    /** 链路固定节点次序 */
    private static final List<String> CHAIN_ORDER = Arrays.asList(
            CcConstants.NODE_PURCHASE, CcConstants.NODE_WAREHOUSE, CcConstants.NODE_DISPATCH,
            CcConstants.NODE_TRANSIT, CcConstants.NODE_SIGN, CcConstants.NODE_RETURN);

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Resource
    private TraceNodeMapper nodeMapper;
    @Resource
    private TraceConcurrentLogMapper concurrentLogMapper;
    @Resource
    private TransportBatchMapper batchMapper;
    @Resource
    private SequenceMapper sequenceMapper;
    @Resource
    private TraceOrgAuthService orgAuthService;
    @Resource
    private TraceConcurrentAuditService auditService;
    @Resource
    private OpLogService opLogService;

    // ---------------- 节点登记（含并发仲裁） ----------------

    /**
     * 登记追溯节点。同一操作（相同 opKey）并发时由数据库唯一约束仲裁：
     * 先提交生效（WINNER），并发落败拒绝（REJECTED/CONCURRENT），双方保留审计；
     * 同键同载荷按幂等重放，同键不同载荷拒绝（DIFFERENT_PAYLOAD）。
     */
    @Transactional
    public TraceNode createNode(TraceNodeCreateRequest req) {
        String opKey = req.getOpKey().trim();
        String nodeType = normalizeNodeType(req.getNodeType());
        TransportBatch batch = requireBatch(req.getBatchId());

        // 岗位只能登记授权机构范围内的节点
        orgAuthService.requireWritable(req.getOrgCode().trim());

        boolean backfill = Boolean.TRUE.equals(req.getBackfill());
        if (backfill && (req.getBackfillReason() == null || req.getBackfillReason().trim().isEmpty())) {
            throw new BusinessException("补录节点必须填写补录原因（补录只追加新事实，不改变任何已发布快照）");
        }
        if (req.getOccurredTime() == null) {
            throw new BusinessException("业务发生时间不能为空（节点保存的是当时状态）");
        }

        String payloadHash = payloadHash(req, nodeType);
        String summary = summaryOf(req, nodeType);

        // 1) opKey 已存在（上一个同操作事务已提交）：
        //    默认视为“同一操作并发/重复发起”，拒绝并保留审计——只有调用方显式声明
        //    idempotentReplay=TRUE（网络重试）且载荷与首个请求一致时，才幂等回放首个结果。
        TraceNode existing = findByOpKey(opKey);
        if (existing != null) {
            boolean samePayload = payloadHash.equals(existing.getPayloadHash());
            if (samePayload && Boolean.TRUE.equals(req.getIdempotentReplay())) {
                return existing;
            }
            // 内容不同=同键不同载荷；内容相同但未声明重放=同一操作重复/并发发起
            rejectConflict(existing, opKey, payloadHash, summary,
                    samePayload ? CcConstants.REJECT_CONCURRENT : CcConstants.REJECT_DIFFERENT_PAYLOAD,
                    samePayload);
            return null; // 不可达
        }

        // 2) 同批次同类型序号（中转/退货多次时从 1 递增）
        Long count = nodeMapper.selectCount(new QueryWrapper<TraceNode>()
                .eq("batch_id", batch.getId()).eq("node_type", nodeType));
        int seq = (count == null ? 0 : count.intValue()) + 1;

        TraceNode node = new TraceNode();
        node.setNodeNo("TR" + sequenceMapper.nextTraceNodeNo());
        node.setBatchId(batch.getId());
        node.setBatchNo(batch.getBatchNo());
        node.setNodeType(nodeType);
        node.setNodeSeq(seq);
        node.setOpKey(opKey);
        node.setSealNo(trimToNull(req.getSealNo()));
        node.setOccurredTime(req.getOccurredTime());
        node.setOrgCode(req.getOrgCode().trim());
        node.setOrgName(req.getOrgName().trim());
        node.setContactName(trimToNull(req.getContactName()));
        node.setContactPhone(trimToNull(req.getContactPhone()));
        node.setStatusSnap(req.getStatusSnap().trim());
        node.setPayloadHash(payloadHash);
        node.setBackfill(backfill);
        node.setBackfillReason(backfill ? req.getBackfillReason().trim() : null);
        node.setRevision(1);
        node.setStatus(CcConstants.NODE_DRAFT);

        // 3) 唯一约束兜底并发：先插入者 WINNER，落败者 REJECTED 并保留审计。
        //    真正并发（双方都在途、在插入时相撞）一律判 CONCURRENT 拒绝——
        //    即使内容相同也是“同时发起的两次操作”而非完成后的重试，由唯一生效者代表该操作。
        try {
            nodeMapper.insert(node);
        } catch (DuplicateKeyException dup) {
            TraceNode winner = findByOpKey(opKey);
            if (winner == null) {
                throw new BusinessException("同一操作[" + opKey + "]并发冲突，请稍后按原 opKey 重试");
            }
            // 插入时相撞是真正并发：无论载荷是否相同都按 CONCURRENT 拒绝
            rejectConflict(winner, opKey, payloadHash, summaryOf(req, nodeType),
                    CcConstants.REJECT_CONCURRENT, payloadHash.equals(winner.getPayloadHash()));
            return null; // 不可达：rejectConflict 必抛异常
        }

        // 4) 生效方审计（独立事务保留）+ 操作流水
        UserPrincipal operator = UserContext.current();
        auditService.winner(node, payloadHash, summaryOf(req, nodeType), operator);
        String action = backfill ? CcConstants.ACT_TRACE_NODE_BACKFILL : CcConstants.ACT_TRACE_NODE_CREATE;
        opLogService.log(CcConstants.ENT_TRACE_NODE, node.getId(), batch.getId(), action,
                String.format("追溯节点登记生效：批次=%s 节点=%s(%s#%d) 机构=%s opKey=%s%s",
                        batch.getBatchNo(), node.getNodeNo(), nodeTypeName(nodeType), seq,
                        node.getOrgCode(), opKey,
                        backfill ? " 补录原因=" + node.getBackfillReason() : ""));

        // 5) 创建即发布（补录事实可直接落为已发布快照）
        if (Boolean.TRUE.equals(req.getPublish())) {
            freezeSnapshot(node, batch);
            nodeMapper.updateById(node);
            opLogService.log(CcConstants.ENT_TRACE_NODE, node.getId(), batch.getId(),
                    CcConstants.ACT_TRACE_NODE_PUBLISH,
                    String.format("追溯节点 %s 登记即发布，快照已固化 hash=%s（此后补录/修订不可改变）",
                            node.getNodeNo(), node.getSnapshotHash()));
        }
        return node;
    }

    /**
     * 同一操作冲突落败处理：写独立事务被拒审计（生效者/被拒者/原因），再抛出业务异常，
     * 当前事务回滚、不产生第二个节点。
     *
     * @param rejectReason CONCURRENT 同一操作并发或重复发起 / DIFFERENT_PAYLOAD 同键不同载荷
     */
    private void rejectConflict(TraceNode winner, String opKey, String payloadHash,
                                String summary, String rejectReason, boolean samePayload) {
        TraceConcurrentLog winnerLog = concurrentLogMapper.selectOne(new QueryWrapper<TraceConcurrentLog>()
                .eq("op_key", opKey).eq("result", CcConstants.CONC_WINNER)
                .orderByDesc("id").last("LIMIT 1"));
        String winnerName = winnerLog == null ? "未知账号" : winnerLog.getOperatorName();
        UserPrincipal me = UserContext.current();
        String fullSummary = summary + "（与生效请求载荷" + (samePayload ? "相同" : "不同") + "）";
        auditService.loser(winner, opKey, null, null, winner.getNodeType(),
                payloadHash, fullSummary, me, rejectReason);
        throw new BusinessException(String.format(
                "冷链药品运输同一操作冲突：opKey=%s 已由账号[%s]先生效（节点 %s），当前账号[%s]的请求被拒绝"
                        + "（%s %s%s）；谁生效、谁被拒绝已写入并发审计。"
                        + "如确为网络重试，请以 idempotentReplay=true 且完全相同的载荷重发",
                opKey, winnerName, winner.getNodeNo(), me.getUsername(),
                rejectReason, rejectReasonName(rejectReason),
                samePayload ? "，双方内容相同" : "，双方内容不同"));
    }

    // ---------------- 草稿修订 / 发布（快照冻结） ----------------

    /** 修订草稿节点：仅 DRAFT 可改，revision+1；已发布快照拒绝一切改写 */
    @Transactional
    public TraceNode revise(Long nodeId, TraceNodeReviseRequest req) {
        TraceNode node = requireNode(nodeId);
        if (CcConstants.NODE_PUBLISHED.equals(node.getStatus())) {
            rejectPublishedImmutable(node, "修订草稿内容");
        }
        orgAuthService.requireWritable(node.getOrgCode());

        node.setSealNo(trimToNull(req.getSealNo()));
        if (req.getOccurredTime() != null) {
            node.setOccurredTime(req.getOccurredTime());
        }
        if (req.getOrgCode() != null && !req.getOrgCode().trim().isEmpty()) {
            String newOrg = req.getOrgCode().trim();
            orgAuthService.requireWritable(newOrg);
            node.setOrgCode(newOrg);
        }
        if (req.getOrgName() != null && !req.getOrgName().trim().isEmpty()) {
            node.setOrgName(req.getOrgName().trim());
        }
        node.setContactName(trimToNull(req.getContactName()));
        node.setContactPhone(trimToNull(req.getContactPhone()));
        node.setStatusSnap(req.getStatusSnap().trim());
        node.setRevision(node.getRevision() + 1);
        nodeMapper.updateById(node);

        opLogService.log(CcConstants.ENT_TRACE_NODE, node.getId(), node.getBatchId(),
                CcConstants.ACT_TRACE_NODE_REVISE,
                String.format("追溯节点 %s 草稿修订至 V%d：%s",
                        node.getNodeNo(), node.getRevision(),
                        req.getReviseReason() == null ? "" : req.getReviseReason()));
        return node;
    }

    /** 发布节点：把当时状态（含发布当刻批次状态）整体固化为快照；已发布不可重复发布/改写 */
    @Transactional
    public TraceNode publish(Long nodeId) {
        TraceNode node = requireNode(nodeId);
        if (CcConstants.NODE_PUBLISHED.equals(node.getStatus())) {
            rejectPublishedImmutable(node, "重复发布");
        }
        orgAuthService.requireWritable(node.getOrgCode());
        TransportBatch batch = requireBatch(node.getBatchId());
        freezeSnapshot(node, batch);
        nodeMapper.updateById(node);

        opLogService.log(CcConstants.ENT_TRACE_NODE, node.getId(), node.getBatchId(),
                CcConstants.ACT_TRACE_NODE_PUBLISH,
                String.format("追溯节点 %s 发布：当时状态快照已固化 hash=%s（发布后补录不能改变本快照）",
                        node.getNodeNo(), node.getSnapshotHash()));
        return node;
    }

    /** 固化发布快照：完整 JSON + SHA-256 + 发布当刻批次状态（此后只读） */
    private void freezeSnapshot(TraceNode node, TransportBatch batch) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("batchNo", node.getBatchNo());
        snap.put("nodeType", node.getNodeType());
        snap.put("nodeSeq", node.getNodeSeq());
        snap.put("sealNo", node.getSealNo());
        snap.put("occurredTime", node.getOccurredTime());
        snap.put("orgCode", node.getOrgCode());
        snap.put("orgName", node.getOrgName());
        snap.put("contactName", node.getContactName());
        snap.put("contactPhone", node.getContactPhone());
        snap.put("statusSnap", node.getStatusSnap());
        snap.put("backfill", node.getBackfill());
        snap.put("backfillReason", node.getBackfillReason());
        snap.put("revision", node.getRevision());
        // 发布当刻批次状态一并固化：节点保存“当时”的完整状态
        snap.put("batchStatusAtPublish", batch.getStatus());
        String json;
        try {
            json = JSON.writeValueAsString(snap);
        } catch (Exception e) {
            throw new BusinessException("发布快照序列化失败: " + e.getMessage());
        }
        node.setSnapshotJson(json);
        node.setSnapshotHash(sha256(json));
        node.setBatchStatusSnap(batch.getStatus());
        node.setStatus(CcConstants.NODE_PUBLISHED);
        UserPrincipal me = UserContext.current();
        node.setPublishedBy(me.getId());
        node.setPublishedName(me.getUsername());
        node.setPublishedTime(LocalDateTime.now());
    }

    /** 已发布快照不可变卡口：写被拒审计（独立事务保留）后拒绝 */
    private void rejectPublishedImmutable(TraceNode node, String attempted) {
        UserPrincipal me = UserContext.current();
        // 拒绝审计在独立事务落库（含操作流水），不受随后业务事务回滚影响
        auditService.loser(node, node.getOpKey(), node.getBatchId(), node.getBatchNo(), node.getNodeType(),
                node.getPayloadHash(), "尝试对已发布节点执行[" + attempted + "]", me,
                CcConstants.REJECT_PUBLISHED_IMMUTABLE);
        throw new BusinessException(String.format(
                "追溯节点 %s 已发布，当时状态快照已冻结（hash=%s），补录/修订不能改变已发布快照；"
                        + "新事实请另追加节点。被拒操作已写审计",
                node.getNodeNo(), node.getSnapshotHash()));
    }

    // ---------------- 查询：按批次链路 / 按封签反查（授权过滤 + 联系方式脱敏） ----------------

    /** 按批次ID取追溯图（只含当前账号授权机构节点） */
    public Map<String, Object> chainByBatchId(Long batchId) {
        TransportBatch batch = requireBatch(batchId);
        return assembleChain(batch, nodeMapper.selectList(new QueryWrapper<TraceNode>()
                .eq("batch_id", batch.getId())));
    }

    /** 按批次号取追溯图 */
    public Map<String, Object> chainByBatchNo(String batchNo) {
        String no = required(batchNo, "批次号");
        TransportBatch batch = batchMapper.selectOne(new QueryWrapper<TransportBatch>()
                .eq("batch_no", no).last("LIMIT 1"));
        if (batch == null) {
            throw new BusinessException("运输批次不存在: " + no);
        }
        return chainByBatchId(batch.getId());
    }

    /** 按封签号反查：跨批次命中所有登记该封签的节点及各自链路（授权过滤） */
    public Map<String, Object> reverseBySeal(String sealNo) {
        String seal = required(sealNo, "封签号");
        List<TraceNode> hits = nodeMapper.selectList(new QueryWrapper<TraceNode>()
                .eq("seal_no", seal).orderByAsc("id"));

        LocalDateTime cutoff = LocalDateTime.now();
        Set<String> myOrgs = orgAuthService.myOrgCodes();
        List<Map<String, Object>> matchList = new ArrayList<>();
        for (TraceNode hit : hits) {
            if (!visible(hit, myOrgs)) {
                continue;
            }
            TransportBatch batch = batchMapper.selectById(hit.getBatchId());
            Map<String, Object> match = new LinkedHashMap<>();
            match.put("matchedNode", toView(hit));
            match.put("chain", assembleChain(batch, nodeMapper.selectList(new QueryWrapper<TraceNode>()
                    .eq("batch_id", hit.getBatchId())), cutoff, myOrgs));
            matchList.add(match);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lookupType", "SEAL");
        result.put("sealNo", seal);
        result.put("dataCutoffTime", cutoff);
        result.put("authorizedOrgCodes", myOrgs);
        result.put("matchCount", matchList.size());
        result.put("matches", matchList);
        return result;
    }

    /** 组装单批次追溯图：授权机构过滤 + 固定链路次序 + 联系方式明文/脱敏 */
    private Map<String, Object> assembleChain(TransportBatch batch, List<TraceNode> nodes) {
        return assembleChain(batch, nodes, LocalDateTime.now(), orgAuthService.myOrgCodes());
    }

    private Map<String, Object> assembleChain(TransportBatch batch, List<TraceNode> nodes,
                                              LocalDateTime cutoff, Set<String> myOrgs) {
        List<TraceNode> visible = new ArrayList<>();
        for (TraceNode n : nodes) {
            if (visible(n, myOrgs)) {
                visible.add(n);
            }
        }
        visible.sort((a, b) -> {
            int byType = chainIndex(a.getNodeType()) - chainIndex(b.getNodeType());
            if (byType != 0) {
                return byType;
            }
            int bySeq = a.getNodeSeq().compareTo(b.getNodeSeq());
            return bySeq != 0 ? bySeq : Long.compare(a.getId(), b.getId());
        });

        List<Map<String, Object>> nodeViews = new ArrayList<>();
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (String t : CHAIN_ORDER) {
            groups.put(t, new ArrayList<>());
        }
        for (TraceNode n : visible) {
            Map<String, Object> v = toView(n, myOrgs);
            nodeViews.add(v);
            groups.get(n.getNodeType()).add(v);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lookupType", "BATCH");
        result.put("batchId", batch.getId());
        result.put("batchNo", batch.getBatchNo());
        result.put("batchStatus", batch.getStatus());
        // 数据截止时间：本次查询组装时刻；之后发布/补录的数据不在本次结果内
        result.put("dataCutoffTime", cutoff);
        result.put("authorizedOrgCodes", myOrgs);
        result.put("nodeCount", nodeViews.size());
        result.put("nodes", nodeViews);
        result.put("groups", groups);
        // 被授权范围过滤掉的节点数（说明存在但本岗位不可见）
        result.put("filteredOutByOrgAuth", nodes.size() - visible.size());
        return result;
    }

    public TraceNode requireNode(Long nodeId) {
        TraceNode node = nodeMapper.selectById(nodeId);
        if (node == null) {
            throw new BusinessException("追溯节点不存在: " + nodeId);
        }
        return node;
    }

    /** 单节点视图（未授权机构不可见） */
    public Map<String, Object> nodeView(Long nodeId) {
        TraceNode node = requireNode(nodeId);
        Set<String> myOrgs = orgAuthService.myOrgCodes();
        if (!visible(node, myOrgs)) {
            throw new BusinessException(String.format(
                    "岗位机构授权隔离：当前账号[%s]无权查看机构[%s]的追溯节点",
                    UserContext.currentName(), node.getOrgCode()));
        }
        return toView(node, myOrgs);
    }

    // ---------------- 导出（同口径，标注数据截止时间与过滤条件） ----------------

    /**
     * 按过滤条件查可导出节点（授权 + 过滤条件同口径），并固定数据截止时间。
     */
    public Map<String, Object> exportData(String batchNo, String sealNo, String nodeType, String orgCode) {
        Set<String> myOrgs = orgAuthService.myOrgCodes();
        LocalDateTime cutoff = LocalDateTime.now();

        QueryWrapper<TraceNode> qw = new QueryWrapper<>();
        if (batchNo != null && !batchNo.trim().isEmpty()) {
            qw.eq("batch_no", batchNo.trim());
        }
        if (sealNo != null && !sealNo.trim().isEmpty()) {
            qw.eq("seal_no", sealNo.trim());
        }
        if (nodeType != null && !nodeType.trim().isEmpty()) {
            qw.eq("node_type", normalizeNodeType(nodeType));
        }
        if (orgCode != null && !orgCode.trim().isEmpty()) {
            qw.eq("org_code", orgCode.trim());
        }
        List<TraceNode> rows = nodeMapper.selectList(qw.orderByAsc("batch_id", "id"));

        List<Map<String, Object>> views = new ArrayList<>();
        int filtered = 0;
        for (TraceNode n : rows) {
            if (!visible(n, myOrgs)) {
                filtered++;
                continue;
            }
            views.add(toView(n, myOrgs));
        }

        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("batchNo", emptyAsAll(batchNo));
        filter.put("sealNo", emptyAsAll(sealNo));
        filter.put("nodeType", nodeType == null || nodeType.trim().isEmpty() ? "全部" : nodeType.trim());
        filter.put("orgCode", emptyAsAll(orgCode));
        filter.put("authorizedOrgCodes", myOrgs);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dataCutoffTime", cutoff);
        data.put("filter", filter);
        data.put("username", UserContext.currentName());
        data.put("rows", views);
        data.put("filteredOutByOrgAuth", filtered);
        return data;
    }

    // ---------------- 并发审计查询 ----------------

    public List<TraceConcurrentLog> listConcurrentLogs(String opKey) {
        QueryWrapper<TraceConcurrentLog> qw = new QueryWrapper<>();
        if (opKey != null && !opKey.trim().isEmpty()) {
            qw.eq("op_key", opKey.trim());
        }
        return concurrentLogMapper.selectList(qw.orderByDesc("id"));
    }

    // ---------------- 视图/工具 ----------------

    /** 节点视图（按当前账号授权决定联系方式明文/脱敏） */
    public Map<String, Object> toView(TraceNode n) {
        return toView(n, orgAuthService.myOrgCodes());
    }

    private Map<String, Object> toView(TraceNode n, Set<String> myOrgs) {
        boolean plaintext = contactPlaintext(n.getOrgCode(), myOrgs);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", n.getId());
        v.put("nodeNo", n.getNodeNo());
        v.put("batchId", n.getBatchId());
        v.put("batchNo", n.getBatchNo());
        v.put("nodeType", n.getNodeType());
        v.put("nodeTypeName", nodeTypeName(n.getNodeType()));
        v.put("nodeSeq", n.getNodeSeq());
        v.put("opKey", n.getOpKey());
        v.put("sealNo", n.getSealNo());
        v.put("occurredTime", n.getOccurredTime());
        v.put("orgCode", n.getOrgCode());
        v.put("orgName", n.getOrgName());
        v.put("contactName", n.getContactName());
        // 联系方式：授权明文则原值，否则脱敏（前3后2）；展示口径字段随视图返回
        v.put("contactPhone", plaintext ? n.getContactPhone() : maskPhone(n.getContactPhone()));
        v.put("contactPhoneMasked", !plaintext);
        v.put("statusSnap", n.getStatusSnap());
        v.put("backfill", Boolean.TRUE.equals(n.getBackfill()));
        v.put("backfillReason", n.getBackfillReason());
        v.put("revision", n.getRevision());
        v.put("status", n.getStatus());
        v.put("published", CcConstants.NODE_PUBLISHED.equals(n.getStatus()));
        v.put("snapshotHash", n.getSnapshotHash());
        v.put("snapshotJson", n.getSnapshotJson());
        v.put("batchStatusSnap", n.getBatchStatusSnap());
        v.put("publishedName", n.getPublishedName());
        v.put("publishedTime", n.getPublishedTime());
        v.put("createTime", n.getCreateTime());
        return v;
    }

    private boolean visible(TraceNode n, Set<String> myOrgs) {
        return orgAuthService.isGlobal(myOrgs) || myOrgs.contains(n.getOrgCode());
    }

    private boolean contactPlaintext(String orgCode, Set<String> myOrgs) {
        if (orgAuthService.isGlobal(myOrgs)) {
            // 全域账号仍按其全域授权行的 contact_visible 决定（admin=明文，qa=脱敏）
            return orgAuthService.contactPlaintext(orgCode);
        }
        return orgAuthService.contactPlaintext(orgCode);
    }

    private TraceNode findByOpKey(String opKey) {
        return nodeMapper.selectOne(new QueryWrapper<TraceNode>()
                .eq("op_key", opKey).last("LIMIT 1"));
    }

    private TransportBatch requireBatch(Long batchId) {
        TransportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BusinessException("运输批次不存在: " + batchId);
        }
        return batch;
    }

    private String normalizeNodeType(String type) {
        String t = required(type, "节点类型");
        if (!CHAIN_ORDER.contains(t)) {
            throw new BusinessException("非法追溯节点类型: " + t + "，合法值=" + CHAIN_ORDER);
        }
        return t;
    }

    private int chainIndex(String type) {
        int i = CHAIN_ORDER.indexOf(type);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    /** 操作载荷规范化摘要：同一操作并发的各方内容一致则摘要一致（幂等回放依据） */
    private String payloadHash(TraceNodeCreateRequest req, String nodeType) {
        String canonical = String.join("|",
                nz(req.getBatchId()),
                nodeType,
                nz(trimToNull(req.getSealNo())),
                req.getOccurredTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                req.getOrgCode().trim(),
                req.getOrgName().trim(),
                nz(trimToNull(req.getContactName())),
                nz(trimToNull(req.getContactPhone())),
                req.getStatusSnap().trim(),
                String.valueOf(Boolean.TRUE.equals(req.getBackfill())));
        return sha256(canonical);
    }

    private String summaryOf(TraceNodeCreateRequest req, String nodeType) {
        return String.format("批次=%s 节点=%s 机构=%s 封签=%s 发生时间=%s 状态=%s",
                req.getBatchId(), nodeType, req.getOrgCode(), nz(req.getSealNo()),
                req.getOccurredTime(), req.getStatusSnap());
    }

    public static String nodeTypeName(String type) {
        if (type == null) {
            return "";
        }
        switch (type) {
            case CcConstants.NODE_PURCHASE: return "采购";
            case CcConstants.NODE_WAREHOUSE: return "仓储";
            case CcConstants.NODE_DISPATCH: return "发运";
            case CcConstants.NODE_TRANSIT: return "中转";
            case CcConstants.NODE_SIGN: return "签收";
            case CcConstants.NODE_RETURN: return "退货";
            default: return type;
        }
    }

    public static String rejectReasonName(String reason) {
        if (reason == null) {
            return "";
        }
        switch (reason) {
            case CcConstants.REJECT_CONCURRENT: return "并发落败（同一操作已被他方先生效）";
            case CcConstants.REJECT_DIFFERENT_PAYLOAD: return "同键不同载荷（同一 opKey 内容不一致）";
            case CcConstants.REJECT_PUBLISHED_IMMUTABLE: return "已发布快照不可改变";
            default: return reason;
        }
    }

    /** 电话脱敏：保留前3位与后2位，中间星号（与抽检导出同口径） */
    public static String maskPhone(String phone) {
        if (phone == null) {
            return "";
        }
        String p = phone.trim();
        if (p.isEmpty()) {
            return "";
        }
        if (p.length() < 7) {
            return repeat('*', p.length());
        }
        return p.substring(0, 3) + repeat('*', p.length() - 5) + p.substring(p.length() - 2);
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String required(String s, String field) {
        if (s == null || s.trim().isEmpty()) {
            throw new BusinessException(field + "不能为空");
        }
        return s.trim();
    }

    private static String nz(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String emptyAsAll(String s) {
        return s == null || s.trim().isEmpty() ? "全部" : s.trim();
    }
}
