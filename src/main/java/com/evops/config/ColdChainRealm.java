package com.evops.config;

import com.evops.common.UserPrincipal;
import com.evops.coldchain.CcConstants;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authc.credential.SimpleCredentialsMatcher;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.subject.PrincipalCollection;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 冷链台账内置演示账号：登录主体为 UserPrincipal（携带操作者 ID）。
 * V5 起同时提供授权信息（Shiro 权限点）：温控预警的查看/导出/闭环、类别模板维护按权限点校验。
 * V1 为本地静态账号，后续版本可对接统一身份。
 */
public class ColdChainRealm extends AuthorizingRealm {

    /** username -> (id, password, role) */
    private static final Map<String, long[]> IDS = new LinkedHashMap<>();
    private static final Map<String, String> PASSWORDS = new LinkedHashMap<>();
    private static final Map<String, String> ROLES = new LinkedHashMap<>();
    /** username -> Shiro 权限点集合 */
    private static final Map<String, Set<String>> PERMISSIONS = new LinkedHashMap<>();

    static {
        put("bootstrap", 1L, "bootstrap", "BOOTSTRAP", Collections.emptySet());
        put("dispatcher", 2L, "dispatcher123", "DISPATCHER", new LinkedHashSet<>(Arrays.asList(
                CcConstants.PERM_TEMPLATE_MANAGE,
                CcConstants.PERM_ALERT_VIEW,
                CcConstants.PERM_ALERT_RESOLVE,
                // V6：调度岗确认异常/提交处置结论/重试通知/批次结算（延误、收货拒签的处置岗位）
                CcConstants.PERM_EXCEPTION_CONFIRM,
                CcConstants.PERM_EXCEPTION_CONCLUDE,
                CcConstants.PERM_EXCEPTION_NOTIFY_RETRY,
                CcConstants.PERM_BATCH_SETTLE,
                // V7：调度发起冷链药品运输审批申请（监管岗审批同意后方可发运）
                CcConstants.PERM_APPROVAL_APPLY,
                // V8：调度发起报价批次、比选锁价、发起人工改价申请（改价由 QA 审批，职责分离）
                CcConstants.PERM_QUOTE_BATCH_MANAGE,
                CcConstants.PERM_PRICE_LOCK,
                CcConstants.PERM_PRICE_ADJUST_APPLY,
                CcConstants.PERM_QUOTE_VIEW,
                // V9：调度可查看隔离队列（运维监控）
                CcConstants.PERM_DEVICE_QUARANTINE_VIEW,
                // V10：调度维护送达时窗台账查看、装车登记、改期申请、待办确认、承运通知重试
                CcConstants.PERM_WINDOW_VIEW,
                CcConstants.PERM_BATCH_LOAD,
                CcConstants.PERM_RESCHEDULE_APPLY,
                CcConstants.PERM_RESCHEDULE_TODO,
                CcConstants.PERM_CARRIER_NOTIFY_RETRY,
                // V11：调度办理退货登记/入隔离/隔离区内移动，可查看退货台账
                CcConstants.PERM_RETURN_HANDLE,
                CcConstants.PERM_RETURN_VIEW,
                // V12：调度可上传电子签收回执、查看台账、发起人工修正（只追加不覆盖）
                CcConstants.PERM_ESIGN_UPLOAD,
                CcConstants.PERM_ESIGN_VIEW,
                CcConstants.PERM_ESIGN_CORRECT,
                // V13：调度可查看/导出追溯图，手工发布/补录节点（机构范围由 t_cc_trace_org_grant 授权）
                CcConstants.PERM_TRACE_VIEW,
                CcConstants.PERM_TRACE_EXPORT,
                CcConstants.PERM_TRACE_PUBLISH)));
        // 承运岗：温度超限/延误的处置岗位，可确认异常与提交处置结论；
        // V8：承运商并行提交报价
        put("carrier", 3L, "carrier123", "CARRIER", new LinkedHashSet<>(Arrays.asList(
                CcConstants.PERM_EXCEPTION_CONFIRM,
                CcConstants.PERM_EXCEPTION_CONCLUDE,
                CcConstants.PERM_QUOTE_SUBMIT,
                CcConstants.PERM_QUOTE_VIEW,
                // V10：承运装车登记、改期申请、本岗待办确认、承运通知重试
                CcConstants.PERM_WINDOW_VIEW,
                CcConstants.PERM_BATCH_LOAD,
                CcConstants.PERM_RESCHEDULE_APPLY,
                CcConstants.PERM_RESCHEDULE_TODO,
                CcConstants.PERM_CARRIER_NOTIFY_RETRY,
                // V12：司机离线保存签收草稿、恢复网络后上传签名/照片/定位
                CcConstants.PERM_ESIGN_UPLOAD,
                CcConstants.PERM_ESIGN_VIEW,
                // V13：承运岗可查看授权范围内追溯图
                CcConstants.PERM_TRACE_VIEW)));
        put("receiver", 4L, "receiver123", "RECEIVER",
                new LinkedHashSet<>(Arrays.asList(
                        CcConstants.PERM_ALERT_VIEW,
                        // V6：收货岗确认封签破损/拒签登记并提交处置结论
                        CcConstants.PERM_EXCEPTION_CONFIRM,
                        CcConstants.PERM_EXCEPTION_CONCLUDE,
                        // V8：可查看报价/锁价台账（无报价/锁价操作权）
                        CcConstants.PERM_QUOTE_VIEW,
                        // V10：收货岗查看时窗、确认本岗收货待办
                        CcConstants.PERM_WINDOW_VIEW,
                        CcConstants.PERM_RESCHEDULE_TODO,
                        // V11：收货岗登记退货（收货拒签）/办理入隔离，可查看退货台账
                        CcConstants.PERM_RETURN_HANDLE,
                        CcConstants.PERM_RETURN_VIEW,
                        // V12：收货方电子签收（在线签收/离线回执上传）
                        CcConstants.PERM_ESIGN_UPLOAD,
                        CcConstants.PERM_ESIGN_VIEW,
                        // V13：收货岗可查看授权范围内追溯图
                        CcConstants.PERM_TRACE_VIEW)));
        // 管理员：冷链温控预警查看/导出/闭环/扫描 + 类别模板维护 + V6 异常分级全套（不含 QA 复核角色）
        // V9：设备证书管理 + 隔离队列查看 + 重放
        put("admin", 5L, "admin123", "ADMIN", new LinkedHashSet<>(Arrays.asList(
                CcConstants.PERM_ALERT_VIEW,
                CcConstants.PERM_ALERT_EXPORT,
                CcConstants.PERM_ALERT_RESOLVE,
                CcConstants.PERM_ALERT_SCAN,
                CcConstants.PERM_TEMPLATE_MANAGE,
                CcConstants.PERM_EXCEPTION_RULE_MANAGE,
                CcConstants.PERM_EXCEPTION_CONFIRM,
                CcConstants.PERM_EXCEPTION_CONCLUDE,
                CcConstants.PERM_EXCEPTION_NOTIFY_RETRY,
                CcConstants.PERM_BATCH_SETTLE,
                CcConstants.PERM_DEVICE_CERT_MANAGE,
                CcConstants.PERM_DEVICE_QUARANTINE_VIEW,
                CcConstants.PERM_DEVICE_REPLAY,
                // V11：管理员可复核退货处理决定（双人复核第二人）、查看/导出退货台账
                CcConstants.PERM_RETURN_VIEW,
                CcConstants.PERM_RETURN_REVIEW,
                CcConstants.PERM_RETURN_EXPORT,
                // V12：主管裁决签收冲突（冻结批次选定生效回执）、人工修正、台账查看
                CcConstants.PERM_ESIGN_VIEW,
                CcConstants.PERM_ESIGN_CORRECT,
                CcConstants.PERM_ESIGN_ADJUDICATE,
                "*")));
        // V6 质量复核岗：唯一可按分级规则复核关闭异常的角色（可查看预警与异常详情）；
        // V8：人工改价必须经独立审批，由 QA 岗审批同意后才生效（申请在调度，职责分离）
        put("qa", 6L, "qa123", "QA", new LinkedHashSet<>(Arrays.asList(
                CcConstants.PERM_ALERT_VIEW,
                CcConstants.PERM_EXCEPTION_REVIEW,
                CcConstants.PERM_PRICE_ADJUST_REVIEW,
                CcConstants.PERM_QUOTE_VIEW,
                // V10：装车后改期由质量/审批岗复核；可查看时窗台账、确认本岗待办
                CcConstants.PERM_WINDOW_VIEW,
                CcConstants.PERM_RESCHEDULE_REVIEW,
                CcConstants.PERM_RESCHEDULE_TODO,
                // V11：质量人员完成退货检验、提出处理决定（放行/销毁/转待查）、
                // 双人复核（须与提出人不同账号）、查看与导出证据链
                CcConstants.PERM_RETURN_VIEW,
                CcConstants.PERM_RETURN_INSPECT,
                CcConstants.PERM_RETURN_DECIDE,
                CcConstants.PERM_RETURN_REVIEW,
                CcConstants.PERM_RETURN_EXPORT,
                // V13：质量岗查看/导出追溯图（质量追溯与证据核对）
                CcConstants.PERM_TRACE_VIEW,
                CcConstants.PERM_TRACE_EXPORT)));
        // V7 监管岗：按批次抽检（立项/封存/双人转移/结论）+ 抽检台账导出 + 冷链运输审批；
        // 具体能访问哪些监管区域由 t_cc_inspector_region 授权（EAST/NORTH），查询再按区域隔离；
        // V8：可查看承运商报价/锁价台账（无业务操作权）
        put("inspector", 7L, "inspector123", "INSPECTOR", new LinkedHashSet<>(Arrays.asList(
                CcConstants.PERM_INSPECTION_VIEW,
                CcConstants.PERM_INSPECTION_MANAGE,
                CcConstants.PERM_INSPECTION_EXPORT,
                CcConstants.PERM_APPROVAL_REVIEW,
                CcConstants.PERM_QUOTE_VIEW,
                // V10：监管可查看送达时窗/改期台账（历史版本追查）
                CcConstants.PERM_WINDOW_VIEW,
                // V13：监管查看/导出追溯图（可见机构仍由机构授权表收窄）
                CcConstants.PERM_TRACE_VIEW,
                CcConstants.PERM_TRACE_EXPORT)));
        // V7 第二个监管账号：仅授权 SOUTH 区域（用于跨区域隔离验收），无运输审批权
        put("inspector2", 8L, "inspector123", "INSPECTOR", new LinkedHashSet<>(Arrays.asList(
                CcConstants.PERM_INSPECTION_VIEW,
                CcConstants.PERM_INSPECTION_MANAGE,
                CcConstants.PERM_QUOTE_VIEW,
                // V10：监管可查看送达时窗/改期台账
                CcConstants.PERM_WINDOW_VIEW)));
        // V9 车载设备网关账号：仅设备温度数据上报权限
        put("device", 9L, "device123", "DEVICE", new LinkedHashSet<>(Arrays.asList(
                CcConstants.PERM_DEVICE_INGEST)));
    }

    private static void put(String username, long id, String password, String role, Set<String> perms) {
        IDS.put(username, new long[]{id});
        PASSWORDS.put(username, password);
        ROLES.put(username, role);
        PERMISSIONS.put(username, perms);
    }

    public ColdChainRealm() {
        // SimpleCredentialsMatcher 会把 HTTP Basic 提交的 char[] 密码转成字符串后比对
        setCredentialsMatcher(new SimpleCredentialsMatcher());
    }

    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) throws AuthenticationException {
        String username = token.getPrincipal() == null ? null : String.valueOf(token.getPrincipal());
        if (username == null || !PASSWORDS.containsKey(username)) {
            return null;
        }
        UserPrincipal principal = new UserPrincipal(IDS.get(username)[0], username);
        // 凭证由 SimpleCredentialsMatcher 与 token 中的 char[] 密码比对
        return new SimpleAuthenticationInfo(principal, PASSWORDS.get(username), getName());
    }

    @Override
    protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
        Object primary = principals.getPrimaryPrincipal();
        SimpleAuthorizationInfo info = new SimpleAuthorizationInfo();
        if (primary instanceof UserPrincipal) {
            String username = ((UserPrincipal) primary).getUsername();
            String role = ROLES.get(username);
            if (role != null) {
                info.addRole(role);
            }
            Set<String> perms = PERMISSIONS.get(username);
            if (perms != null) {
                info.addStringPermissions(perms);
            }
        }
        return info;
    }
}
