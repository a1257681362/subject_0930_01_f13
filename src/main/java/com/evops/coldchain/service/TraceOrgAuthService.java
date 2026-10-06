package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.entity.TraceOrgAuth;
import com.evops.coldchain.mapper.TraceOrgAuthMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 岗位追溯授权（V13）：账号能看到哪些机构、联系方式明文还是脱敏。
 *
 * - 授权行 t_cc_trace_org_auth：username + orgCode（'*'=全域）+ contactVisible。
 * - 链路查询/封签反查只返回授权机构节点；无任何授权的账号查不到任何节点。
 * - 联系方式明文：存在覆盖该机构且 contactVisible=TRUE 的授权行才明文，否则脱敏。
 * - 节点维护（建节点）同样要求机构在授权可写范围内，未授权机构禁止登记。
 */
@Service
public class TraceOrgAuthService {

    /** 全域通配 */
    public static final String ORG_ALL = "*";

    @Resource
    private TraceOrgAuthMapper authMapper;

    /** 指定账号的全部授权行 */
    public List<TraceOrgAuth> listByUsername(String username) {
        return authMapper.selectList(new QueryWrapper<TraceOrgAuth>()
                .eq("username", username).orderByAsc("org_code"));
    }

    /** 当前账号的全部授权行 */
    public List<TraceOrgAuth> myAuths() {
        return listByUsername(UserContext.currentName());
    }

    /** 当前账号授权机构代码集合（含 '*' 即全域） */
    public Set<String> myOrgCodes() {
        Set<String> codes = new LinkedHashSet<>();
        for (TraceOrgAuth a : myAuths()) {
            codes.add(a.getOrgCode());
        }
        return codes;
    }

    public boolean isGlobal(Set<String> orgCodes) {
        return orgCodes.contains(ORG_ALL);
    }

    /** 节点机构是否在当前账号可见范围 */
    public boolean canSee(String orgCode) {
        Set<String> mine = myOrgCodes();
        return isGlobal(mine) || orgCode != null && mine.contains(orgCode);
    }

    /** 节点机构是否在当前账号可登记范围（与可见范围同口径） */
    public boolean canWrite(String orgCode) {
        return canSee(orgCode);
    }

    /** 机构写入口：未授权直接拒绝（不同岗位只能登记授权范围内机构的节点） */
    public void requireWritable(String orgCode) {
        if (!canWrite(orgCode)) {
            throw new BusinessException(String.format(
                    "岗位机构授权隔离：当前账号[%s]未被授权机构[%s]，不能登记该机构的追溯节点"
                            + "（请由管理员在追溯授权中授予）",
                    UserContext.currentName(), orgCode));
        }
    }

    /** 该机构联系方式对当前账号是否明文（全域/本机构任一授权行允许明文即可） */
    public boolean contactPlaintext(String orgCode) {
        for (TraceOrgAuth a : myAuths()) {
            if (Boolean.TRUE.equals(a.getContactVisible())
                    && (ORG_ALL.equals(a.getOrgCode()) || a.getOrgCode().equals(orgCode))) {
                return true;
            }
        }
        return false;
    }

    /** 授权：同账号同机构唯一（存在则更新明文口径与原因），逐次留痕由调用方完成 */
    @Transactional
    public TraceOrgAuth grant(String username, String orgCode, boolean contactVisible, String reason) {
        TraceOrgAuth auth = authMapper.selectOne(new QueryWrapper<TraceOrgAuth>()
                .eq("username", username).eq("org_code", orgCode).last("LIMIT 1"));
        boolean created = auth == null;
        if (created) {
            auth = new TraceOrgAuth();
            auth.setUsername(username);
            auth.setOrgCode(orgCode);
        }
        auth.setContactVisible(contactVisible);
        auth.setGrantReason(reason);
        if (created) {
            authMapper.insert(auth);
        } else {
            authMapper.updateById(auth);
        }
        return auth;
    }

    /** 收回授权（删除授权行；不影响任何已发布追溯节点） */
    @Transactional
    public TraceOrgAuth revoke(String username, String orgCode) {
        TraceOrgAuth auth = authMapper.selectOne(new QueryWrapper<TraceOrgAuth>()
                .eq("username", username).eq("org_code", orgCode).last("LIMIT 1"));
        if (auth == null) {
            throw new BusinessException(String.format(
                    "授权不存在：账号[%s] 机构[%s]", username, orgCode));
        }
        authMapper.deleteById(auth.getId());
        return auth;
    }
}
