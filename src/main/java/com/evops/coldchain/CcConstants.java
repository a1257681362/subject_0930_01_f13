package com.evops.coldchain;

/** 冷链台账状态与动作常量 */
public final class CcConstants {

    private CcConstants() {
    }

    // 档案/车辆状态
    public static final String ACTIVE = "ACTIVE";
    public static final String REVOKED = "REVOKED";

    // 档案版本状态
    public static final String EFFECTIVE = "EFFECTIVE";
    public static final String SUPERSEDED = "SUPERSEDED";

    // 路线季节版本状态（DRAFT 尚未发布 / EFFECTIVE 生效中 / SUPERSEDED 被热替换 / REVOKED 已撤回）
    public static final String ROUTE_DRAFT = "DRAFT";
    // EFFECTIVE / SUPERSEDED / REVOKED 复用上方常量

    // 季节（同一路线可按季节各维护一套版本；GENERAL 为不区分季节的通用配置）
    public static final String SEASON_SPRING = "SPRING";
    public static final String SEASON_SUMMER = "SUMMER";
    public static final String SEASON_AUTUMN = "AUTUMN";
    public static final String SEASON_WINTER = "WINTER";
    public static final String SEASON_GENERAL = "GENERAL";

    // 发货单状态
    public static final String ORDER_CREATED = "CREATED";
    public static final String ORDER_DISPATCHED = "DISPATCHED";
    public static final String ORDER_SHIPPED = "SHIPPED";
    public static final String ORDER_SIGNED = "SIGNED";
    public static final String ORDER_REVOKED = "REVOKED";

    // 运输批次状态
    public static final String BATCH_DISPATCHED = "DISPATCHED";
    /** V10：已装车（派车之后、发运之前的节点；该状态起改期必须审批） */
    public static final String BATCH_LOADING = "LOADING";
    public static final String BATCH_SHIPPED = "SHIPPED";
    public static final String BATCH_EXCEPTION = "EXCEPTION";
    public static final String BATCH_SIGNED = "SIGNED";
    public static final String BATCH_REVOKED = "REVOKED";

    // 异常
    public static final String EXC_TEMP = "TEMP_EXCURSION";
    public static final String EXC_DAMAGE = "DAMAGE";
    /** V6：封签破损（封签核验确认破损时自动开单，旧 DAMAGE 仍可人工标记包装破损） */
    public static final String EXC_SEAL_BROKEN = "SEAL_BROKEN";
    public static final String EXC_DELAY = "DELAY";
    /** V6：收货拒签 */
    public static final String EXC_REJECTION = "REJECTION";
    public static final String EXC_OTHER = "OTHER";
    /** V6 异常状态：OPEN 待确认（人工补录未确认，时钟未启动）/ CONFIRMED 已确认时钟运行中
     / REVIEWING 待复核 / RESOLVED 已复核关闭 */
    public static final String EXC_OPEN = "OPEN";
    public static final String EXC_CONFIRMED = "CONFIRMED";
    public static final String EXC_REVIEWING = "REVIEWING";
    public static final String EXC_RESOLVED = "RESOLVED";

    // —— V6 异常分级 ——
    /** 重大 */
    public static final String GRADE_L1 = "L1";
    /** 较大 */
    public static final String GRADE_L2 = "L2";
    /** 一般 */
    public static final String GRADE_L3 = "L3";

    // 处置岗位（与 ColdChainRealm 内置角色同口径）
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_DISPATCHER = "DISPATCHER";
    public static final String ROLE_CARRIER = "CARRIER";
    public static final String ROLE_RECEIVER = "RECEIVER";
    /** 质量复核岗：异常关闭前必须由该角色复核 */
    public static final String ROLE_QA = "QA";

    // 通知阶段：RESPONSE 确认时首响通知 / ESCALATE 逾期升级 / RETRY 手动重试
    public static final String NOTIFY_RESPONSE = "RESPONSE";
    public static final String NOTIFY_ESCALATE = "ESCALATE";
    public static final String NOTIFY_RETRY = "RETRY";
    // 通知发送结果
    public static final String NOTIFY_SUCCESS = "SUCCESS";
    public static final String NOTIFY_FAILED = "FAILED";
    // 通知渠道
    public static final String NOTIFY_CHANNEL_SITE = "SITE";
    public static final String NOTIFY_CHANNEL_WEBHOOK = "WEBHOOK";

    // 复核结果
    public static final String REVIEW_PENDING = "PENDING";
    public static final String REVIEW_APPROVED = "APPROVED";
    public static final String REVIEW_REJECTED = "REJECTED";
    public static final String REVIEW_NA = "NA";

    // 操作日志实体类型
    public static final String ENT_DRUG = "DRUG";
    public static final String ENT_VEHICLE = "VEHICLE";
    public static final String ENT_ORDER = "ORDER";
    public static final String ENT_BATCH = "BATCH";
    public static final String ENT_TEMPERATURE = "TEMPERATURE";
    public static final String ENT_EXCEPTION = "EXCEPTION";
    public static final String ENT_ROUTE = "ROUTE";
    // 车载温度探头 / 校准凭证
    public static final String ENT_PROBE = "PROBE";
    public static final String ENT_CERT = "CERT";
    // 分仓交接单 / 封签 / 权限开关
    public static final String ENT_HANDOVER = "HANDOVER";
    public static final String ENT_SEAL = "SEAL";
    public static final String ENT_PERMISSION = "PERMISSION";
    // 药品类别 / 温控预警模板 / 温控预警（V5）
    public static final String ENT_CATEGORY = "CATEGORY";
    public static final String ENT_ALERT_TEMPLATE = "ALERT_TEMPLATE";
    public static final String ENT_TEMP_ALERT = "TEMP_ALERT";

    // 校准凭证状态（VALID 有效 / VOID 作废；只追加 void* 字段，不删除原行）
    public static final String CERT_VALID = "VALID";
    public static final String CERT_VOID = "VOID";

    // 分仓交接单状态（DRAFT 待逐封签核验 / SUBMITTED 已提交责任转移 / VOIDED 已作废留痕）
    public static final String HANDOVER_DRAFT = "DRAFT";
    public static final String HANDOVER_SUBMITTED = "SUBMITTED";
    public static final String HANDOVER_VOIDED = "VOIDED";

    // 封签核验状态（PENDING 待核验 / CONFIRMED 已核验；确认事实只进不退）
    public static final String SEAL_PENDING = "PENDING";
    public static final String SEAL_CONFIRMED = "CONFIRMED";

    // 冷链药品运输（分仓交接）权限键：FALSE=权限收紧，交接停止推进，已确认封签事实保留
    public static final String PERM_COLD_CHAIN_HANDOVER = "COLD_CHAIN_HANDOVER";

    // —— V5 温控预警模板 / 预警 Shiro 权限点 ——
    /** 查看（列表/详情/台账）温控预警 */
    public static final String PERM_ALERT_VIEW = "coldchain:alert:view";
    /** 导出温控预警 */
    public static final String PERM_ALERT_EXPORT = "coldchain:alert:export";
    /** 预警处理闭环 */
    public static final String PERM_ALERT_RESOLVE = "coldchain:alert:resolve";
    /** 药品类别与温控预警模板维护 */
    public static final String PERM_TEMPLATE_MANAGE = "coldchain:template:manage";
    /** 手动触发温控预警扫描（定时任务运维入口，仅管理员） */
    public static final String PERM_ALERT_SCAN = "coldchain:alert:scan";

    // 默认温区代码（未显式指定温区时按药品档案储存温区映射的默认温区）
    public static final String ZONE_REFRIGERATE = "REFRIGERATE";
    public static final String ZONE_REFRIGERATE_NAME = "冷藏温区";

    // 连续超限计时状态（ACTIVE 连续超限中含抖动合并 / BROKEN 被超窗正常段断开）
    public static final String STREAK_ACTIVE = "ACTIVE";
    public static final String STREAK_BROKEN = "BROKEN";

    // 温控预警状态（OPEN 待处理 / RESOLVED 已处理；只追加 resolve*，不随模板变更消失）
    public static final String ALERT_OPEN = "OPEN";
    public static final String ALERT_RESOLVED = "RESOLVED";

    // 操作动作
    public static final String ACT_CREATE = "CREATE";
    public static final String ACT_CHANGE = "CHANGE";
    public static final String ACT_DISPATCH = "DISPATCH";
    public static final String ACT_SHIP = "SHIP";
    public static final String ACT_TEMP_REPORT = "TEMP_REPORT";
    public static final String ACT_EXCEPTION_MARK = "EXCEPTION_MARK";
    public static final String ACT_EVIDENCE_APPEND = "EVIDENCE_APPEND";
    public static final String ACT_EXCEPTION_RESOLVE = "EXCEPTION_RESOLVE";
    public static final String ACT_SIGN = "SIGN";
    public static final String ACT_REVOKE = "REVOKE";
    // 路线温区分段：草稿编排 / 发布（热替换）/ 撤回未生效版本 / 发布前差异核对
    public static final String ACT_ROUTE_DRAFT = "ROUTE_DRAFT";
    public static final String ACT_ROUTE_PUBLISH = "ROUTE_PUBLISH";
    public static final String ACT_ROUTE_WITHDRAW = "ROUTE_WITHDRAW";
    public static final String ACT_ROUTE_DIFF = "ROUTE_DIFF";
    // 车载探头：建档 / 证书上传 / 证书下载 / 证书作废 / 凭证关联（绑定/替换）
    public static final String ACT_PROBE_CREATE = "PROBE_CREATE";
    public static final String ACT_CERT_UPLOAD = "CERT_UPLOAD";
    public static final String ACT_CERT_DOWNLOAD = "CERT_DOWNLOAD";
    public static final String ACT_CERT_VOID = "CERT_VOID";
    public static final String ACT_PROBE_BIND = "PROBE_BIND";
    public static final String ACT_PROBE_REPLACE = "PROBE_REPLACE";
    // 分仓交接：拆单建单 / 封签核验 / 提交责任转移 / 作废 / 权限收紧放行切换
    public static final String ACT_HANDOVER_CREATE = "HANDOVER_CREATE";
    public static final String ACT_SEAL_VERIFY = "SEAL_VERIFY";
    public static final String ACT_HANDOVER_SUBMIT = "HANDOVER_SUBMIT";
    public static final String ACT_HANDOVER_VOID = "HANDOVER_VOID";
    public static final String ACT_PERMISSION_TIGHTEN = "PERMISSION_TIGHTEN";
    public static final String ACT_PERMISSION_RESTORE = "PERMISSION_RESTORE";
    // 一车货到达中转仓（交接前一个运输节点，登记到仓总箱数作为分箱平衡基准）
    public static final String ACT_ARRIVE = "ARRIVE";
    // V5：类别建档/变更、预警模板维护、预警产生（系统扫描）、预警闭环
    public static final String ACT_CATEGORY_CREATE = "CATEGORY_CREATE";
    public static final String ACT_CATEGORY_CHANGE = "CATEGORY_CHANGE";
    public static final String ACT_CATEGORY_REVOKE = "CATEGORY_REVOKE";
    public static final String ACT_DRUG_ASSIGN_CATEGORY = "DRUG_ASSIGN_CATEGORY";
    public static final String ACT_TEMPLATE_SAVE = "ALERT_TEMPLATE_SAVE";
    public static final String ACT_ALERT_RAISE = "TEMP_ALERT_RAISE";
    public static final String ACT_ALERT_SCAN = "TEMP_ALERT_SCAN";
    public static final String ACT_ALERT_RESOLVE = "TEMP_ALERT_RESOLVE";
    public static final String ACT_ALERT_EXPORT = "TEMP_ALERT_EXPORT";

    // —— V6 运输异常分级 —— Shiro 权限点 ——
    /** 异常分级规则配置 */
    public static final String PERM_EXCEPTION_RULE_MANAGE = "coldchain:exception:rule:manage";
    /** 异常确认（启动处置时钟） */
    public static final String PERM_EXCEPTION_CONFIRM = "coldchain:exception:confirm";
    /** 提交处置结论（必须引用证据） */
    public static final String PERM_EXCEPTION_CONCLUDE = "coldchain:exception:conclude";
    /** 关闭前复核（质量复核岗） */
    public static final String PERM_EXCEPTION_REVIEW = "coldchain:exception:review";
    /** 升级通知手动重试 */
    public static final String PERM_EXCEPTION_NOTIFY_RETRY = "coldchain:exception:notify:retry";
    /** 批次结算 */
    public static final String PERM_BATCH_SETTLE = "coldchain:batch:settle";

    // V6 操作动作
    public static final String ACT_EXC_RULE_CHANGE = "EXC_GRADE_RULE_CHANGE";
    public static final String ACT_EXC_CONFIRM = "EXCEPTION_CONFIRM";
    public static final String ACT_EXC_CONCLUDE = "EXCEPTION_CONCLUDE";
    public static final String ACT_EXC_REVIEW = "EXCEPTION_REVIEW";
    public static final String ACT_EXC_NOTIFY = "EXCEPTION_NOTIFY";
    public static final String ACT_EXC_NOTIFY_RETRY = "EXCEPTION_NOTIFY_RETRY";
    public static final String ACT_EXC_ESCALATE = "EXCEPTION_ESCALATE";
    public static final String ACT_BATCH_REJECT = "BATCH_REJECT";
    public static final String ACT_BATCH_SETTLE = "BATCH_SETTLE";

    // —— V7 药品运输抽检与运输审批 ——
    /** 监管岗（发起抽检任务/样本封存/转移/结论） */
    public static final String ROLE_INSPECTOR = "INSPECTOR";

    // 抽检任务状态（PLANNED 立项封存中 / CONCLUDED 已出结论；不提供删除）
    public static final String TASK_PLANNED = "PLANNED";
    public static final String TASK_CONCLUDED = "CONCLUDED";
    // 抽检结论（回写批次独立列，不覆盖原始运输记录）
    public static final String INSPECT_PASS = "PASS";
    public static final String INSPECT_FAIL = "FAIL";
    // 样本状态（SEALED 已封存 / TRANSFERRED 已完成双人转移；只进不退、不可删除）
    public static final String SAMPLE_SEALED = "SEALED";
    public static final String SAMPLE_TRANSFERRED = "TRANSFERRED";
    // 样本转移单状态（PENDING 待第二人确认 / CONFIRMED 双人确认完成）
    public static final String TRANSFER_PENDING = "PENDING";
    public static final String TRANSFER_CONFIRMED = "CONFIRMED";
    // 冷链运输审批状态（PENDING 只完成一半停留待处理 / APPROVED 同意 / REJECTED 驳回）
    public static final String APPROVAL_PENDING = "PENDING";
    public static final String APPROVAL_APPROVED = "APPROVED";
    public static final String APPROVAL_REJECTED = "REJECTED";

    // V7 操作日志实体类型
    public static final String ENT_INSPECTION = "INSPECTION";
    public static final String ENT_SAMPLE = "SAMPLE";
    public static final String ENT_SAMPLE_TRANSFER = "SAMPLE_TRANSFER";
    public static final String ENT_APPROVAL = "APPROVAL";

    // V7 操作动作
    public static final String ACT_INSPECTION_CREATE = "INSPECTION_CREATE";
    public static final String ACT_SAMPLE_SEAL = "SAMPLE_SEAL";
    public static final String ACT_SAMPLE_TRANSFER_REQUEST = "SAMPLE_TRANSFER_REQUEST";
    public static final String ACT_SAMPLE_TRANSFER_CONFIRM = "SAMPLE_TRANSFER_CONFIRM";
    public static final String ACT_INSPECTION_CONCLUDE = "INSPECTION_CONCLUDE";
    public static final String ACT_INSPECTION_EXPORT = "INSPECTION_EXPORT";
    public static final String ACT_APPROVAL_APPLY = "APPROVAL_APPLY";
    public static final String ACT_APPROVAL_APPROVE = "APPROVAL_APPROVE";
    public static final String ACT_APPROVAL_REJECT = "APPROVAL_REJECT";

    // —— V7 Shiro 权限点 ——
    /** 抽检查看（列表/详情，另按监管区域隔离） */
    public static final String PERM_INSPECTION_VIEW = "coldchain:inspection:view";
    /** 抽检办理（建任务/样本封存/转移/出结论） */
    public static final String PERM_INSPECTION_MANAGE = "coldchain:inspection:manage";
    /** 抽检台账导出（导出文件承运人联系方式脱敏） */
    public static final String PERM_INSPECTION_EXPORT = "coldchain:inspection:export";
    /** 冷链运输审批申请 */
    public static final String PERM_APPROVAL_APPLY = "coldchain:approval:apply";
    /** 冷链运输审批（同意/驳回） */
    public static final String PERM_APPROVAL_REVIEW = "coldchain:approval:review";

    // —— V8 冷链承运商报价比选与锁价 ——
    // 报价批次状态：OPEN 报价中（并行收报价）/ CLOSED 已截止（已锁价或人工截止）/ EXPIRED 已过期
    public static final String QB_OPEN = "OPEN";
    public static final String QB_CLOSED = "CLOSED";
    public static final String QB_EXPIRED = "EXPIRED";
    // 报价单状态：SUBMITTED 当前有效 / SUPERSEDED 被本承运商修订版替换 /
    // LOCKED 中选锁价 / REJECTED 落选 / EXPIRED 随批次过期失效
    public static final String QUOTE_SUBMITTED = "SUBMITTED";
    public static final String QUOTE_SUPERSEDED = "SUPERSEDED";
    public static final String QUOTE_LOCKED = "LOCKED";
    public static final String QUOTE_REJECTED = "REJECTED";
    public static final String QUOTE_EXPIRED = "EXPIRED";
    // 锁价单状态：LOCKED 锁价有效 / ADJUSTING 改价审批中 / VOIDED 已作废
    public static final String LP_LOCKED = "LOCKED";
    public static final String LP_ADJUSTING = "ADJUSTING";
    public static final String LP_VOIDED = "VOIDED";
    // 改价审批状态复用 APPROVAL_PENDING / APPROVAL_APPROVED / APPROVAL_REJECTED

    // V8 温区代码（报价维度；REFRIGERATE 已在 V5 定义）
    public static final String ZONE_FROZEN = "FROZEN";
    public static final String ZONE_DEEP_FROZEN = "DEEP_FROZEN";
    public static final String ZONE_NORMAL = "NORMAL";

    // V8 操作日志实体类型
    public static final String ENT_CARRIER = "CARRIER";
    public static final String ENT_QUOTE_BATCH = "QUOTE_BATCH";
    public static final String ENT_QUOTE = "QUOTE";
    public static final String ENT_LOCKED_PRICE = "LOCKED_PRICE";
    public static final String ENT_QUOTE_ADJUST = "QUOTE_ADJUST";

    // V8 操作动作
    public static final String ACT_CARRIER_CREATE = "CARRIER_CREATE";
    public static final String ACT_QUOTE_BATCH_OPEN = "QUOTE_BATCH_OPEN";
    public static final String ACT_QUOTE_BATCH_CLOSE = "QUOTE_BATCH_CLOSE";
    public static final String ACT_QUOTE_BATCH_EXPIRE = "QUOTE_BATCH_EXPIRE";
    public static final String ACT_QUOTE_SUBMIT = "QUOTE_SUBMIT";
    public static final String ACT_QUOTE_LOCK = "QUOTE_LOCK";
    public static final String ACT_LOCKED_PRICE_VOID = "LOCKED_PRICE_VOID";
    public static final String ACT_PRICE_ADJUST_APPLY = "PRICE_ADJUST_APPLY";
    public static final String ACT_PRICE_ADJUST_APPROVE = "PRICE_ADJUST_APPROVE";
    public static final String ACT_PRICE_ADJUST_REJECT = "PRICE_ADJUST_REJECT";

    // —— V8 Shiro 权限点 ——
    /** 承运商主档维护 */
    public static final String PERM_CARRIER_MANAGE = "coldchain:carrier:manage";
    /** 发起报价批次（询价/截止/过期处理） */
    public static final String PERM_QUOTE_BATCH_MANAGE = "coldchain:quote:batch:manage";
    /** 承运商提交报价 */
    public static final String PERM_QUOTE_SUBMIT = "coldchain:quote:submit";
    /** 比选锁价（锁价单只能选一个有效方案） */
    public static final String PERM_PRICE_LOCK = "coldchain:price:lock";
    /** 人工改价申请（必须经审批，原始报价保留） */
    public static final String PERM_PRICE_ADJUST_APPLY = "coldchain:price:adjust:apply";
    /** 人工改价审批 */
    public static final String PERM_PRICE_ADJUST_REVIEW = "coldchain:price:adjust:review";
    /** 报价/锁价台账查看（含反查） */
    public static final String PERM_QUOTE_VIEW = "coldchain:quote:view";

    // —— V9 车载设备温度数据接入 ——
    /** 设备角色（车载设备网关账号） */
    public static final String ROLE_DEVICE = "DEVICE";

    // 设备证书状态
    public static final String CERT_ACTIVE = "ACTIVE";
    // CERT_REVOKED 复用 REVOKED

    // 数据批次状态
    public static final String INGEST_POSTED = "POSTED";
    public static final String INGEST_QUARANTINED = "QUARANTINED";

    // 隔离队列状态
    public static final String QUARANTINE_PENDING = "PENDING";
    public static final String QUARANTINE_REPLAYED = "REPLAYED";
    public static final String QUARANTINE_DISCARDED = "DISCARDED";

    // 隔离原因代码
    public static final String REASON_DEVICE_UNKNOWN = "DEVICE_UNKNOWN";
    public static final String REASON_CERT_EXPIRED = "CERT_EXPIRED";
    public static final String REASON_CERT_REVOKED = "CERT_REVOKED";
    public static final String REASON_CERT_MISMATCH = "CERT_MISMATCH";
    public static final String REASON_SIGN_MISMATCH = "SIGN_MISMATCH";
    public static final String REASON_PAYLOAD_MALFORMED = "PAYLOAD_MALFORMED";
    public static final String REASON_BATCH_INVALID = "BATCH_INVALID";

    // 温度数据来源
    public static final String TEMP_SOURCE_REALTIME = "REALTIME";
    public static final String TEMP_SOURCE_BACKFILL = "BACKFILL";

    // 重放结果
    public static final String REPLAY_SUCCESS = "SUCCESS";
    public static final String REPLAY_FAILED = "FAILED";

    // V9 操作日志实体类型
    public static final String ENT_DEVICE_CERT = "DEVICE_CERT";
    public static final String ENT_DEVICE_INGEST = "DEVICE_INGEST";
    public static final String ENT_DEVICE_QUARANTINE = "DEVICE_QUARANTINE";
    public static final String ENT_DEVICE_REPLAY = "DEVICE_REPLAY";

    // V9 操作动作
    public static final String ACT_DEVICE_CERT_REGISTER = "DEVICE_CERT_REGISTER";
    public static final String ACT_DEVICE_CERT_REVOKE = "DEVICE_CERT_REVOKE";
    public static final String ACT_DEVICE_INGEST = "DEVICE_INGEST";
    public static final String ACT_DEVICE_QUARANTINE = "DEVICE_QUARANTINE";
    public static final String ACT_DEVICE_REPLAY = "DEVICE_REPLAY";

    // —— V9 Shiro 权限点 ——
    /** 设备证书登记/管理 */
    public static final String PERM_DEVICE_CERT_MANAGE = "coldchain:device:cert:manage";
    /** 设备温度数据上报 */
    public static final String PERM_DEVICE_INGEST = "coldchain:device:ingest";
    /** 隔离队列查看 */
    public static final String PERM_DEVICE_QUARANTINE_VIEW = "coldchain:device:quarantine:view";
    /** 重放隔离数据 */
    public static final String PERM_DEVICE_REPLAY = "coldchain:device:replay";

    // —— V10 送达时窗管理 ——
    /** 时窗规则草稿/发布状态复用 DRAFT / EFFECTIVE / SUPERSEDED */
    public static final String WINDOW_DRAFT = "DRAFT";

    /** 节假日规则类别：HOLIDAY 节假日（整日闭馆）/ CLOSED 临时闭馆 */
    public static final String HOLIDAY_HOLIDAY = "HOLIDAY";
    public static final String HOLIDAY_CLOSED = "CLOSED";
    /** 节假日/临时闭馆规则状态：ACTIVE 生效中 / VOID 已作废（冻结行不能作废） */
    public static final String HOLIDAY_ACTIVE = "ACTIVE";
    public static final String HOLIDAY_VOID = "VOID";

    /** 改期单状态：AUTO 未装车直接生效 / PENDING 装车后待审批 / APPROVED 同意已生效 / REJECTED 驳回未生效 */
    public static final String RESCHEDULE_AUTO = "AUTO";
    public static final String RESCHEDULE_PENDING = "PENDING";
    public static final String RESCHEDULE_APPROVED = "APPROVED";
    public static final String RESCHEDULE_REJECTED = "REJECTED";

    /** 受影响明细类型：温控区间 / 承运通知对象 / 待办责任人 */
    public static final String IMPACT_TEMP_ZONE = "TEMP_ZONE";
    public static final String IMPACT_CARRIER_NOTICE = "CARRIER_NOTICE";
    public static final String IMPACT_TODO_OWNER = "TODO_OWNER";

    /** 承运通知渠道 / 结果（复用 V6 NOTIFY_CHANNEL_SITE/WEBHOOK 与 NOTIFY_SUCCESS/FAILED） */
    /** 待办状态：PENDING / DONE 已确认 */
    public static final String TODO_PENDING = "PENDING";
    public static final String TODO_DONE = "DONE";

    // V10 操作日志实体类型
    public static final String ENT_RECEIVE_ORG = "RECEIVE_ORG";
    public static final String ENT_WINDOW_RULE = "DELIVERY_WINDOW_RULE";
    public static final String ENT_HOLIDAY = "HOLIDAY_RULE";
    public static final String ENT_RESCHEDULE = "BATCH_RESCHEDULE";

    // V10 操作动作
    public static final String ACT_RECEIVE_ORG_CREATE = "RECEIVE_ORG_CREATE";
    public static final String ACT_RECEIVE_ORG_CHANGE = "RECEIVE_ORG_CHANGE";
    public static final String ACT_WINDOW_DRAFT = "WINDOW_RULE_DRAFT";
    public static final String ACT_WINDOW_PUBLISH = "WINDOW_RULE_PUBLISH";
    public static final String ACT_HOLIDAY_ADD = "HOLIDAY_RULE_ADD";
    public static final String ACT_HOLIDAY_VOID = "HOLIDAY_RULE_VOID";
    public static final String ACT_BATCH_LOAD = "BATCH_LOAD";
    public static final String ACT_BATCH_RESCHEDULE = "BATCH_RESCHEDULE";
    public static final String ACT_RESCHEDULE_APPROVE = "RESCHEDULE_APPROVE";
    public static final String ACT_RESCHEDULE_REJECT = "RESCHEDULE_REJECT";
    public static final String ACT_CARRIER_NOTIFY = "CARRIER_NOTIFY";
    public static final String ACT_CARRIER_NOTIFY_RETRY = "CARRIER_NOTIFY_RETRY";
    public static final String ACT_TODO_CONFIRM = "RESCHEDULE_TODO_CONFIRM";

    // —— V10 Shiro 权限点 ——
    /** 收货机构与时窗规则（工作日区间/版本发布）维护 */
    public static final String PERM_WINDOW_MANAGE = "coldchain:window:manage";
    /** 节假日与临时闭馆规则独立维护 */
    public static final String PERM_HOLIDAY_MANAGE = "coldchain:holiday:manage";
    /** 送达时窗/改期台账只读查看 */
    public static final String PERM_WINDOW_VIEW = "coldchain:window:view";
    /** 运输批次改期申请（未装车直接生效，装车后提交审批） */
    public static final String PERM_RESCHEDULE_APPLY = "coldchain:reschedule:apply";
    /** 装车后改期审批 */
    public static final String PERM_RESCHEDULE_REVIEW = "coldchain:reschedule:review";
    /** 批次装车登记 */
    public static final String PERM_BATCH_LOAD = "coldchain:batch:load";
    /** 承运通知重试 */
    public static final String PERM_CARRIER_NOTIFY_RETRY = "coldchain:carrier:notify:retry";
    /** 改期待办确认 */
    public static final String PERM_RESCHEDULE_TODO = "coldchain:reschedule:todo";

    // —— V11 冷链药品退货 ——
    /** 退货原因：收货拒签（对应运输异常 REJECTION） */
    public static final String RETURN_REASON_REJECTION = "REJECTION";
    /** 退货原因：温度超限（对应运输异常 TEMP_EXCURSION） */
    public static final String RETURN_REASON_TEMP = "TEMP_EXCURSION";
    /** 退货原因：包装破损（对应运输异常 DAMAGE） */
    public static final String RETURN_REASON_DAMAGE = "PACKAGE_DAMAGE";

    /** 退货批次状态：REGISTERED 已登记（未入隔离） */
    public static final String RETURN_REGISTERED = "REGISTERED";
    /** 已入隔离库位（不能再次出库，待质量检验） */
    public static final String RETURN_ISOLATED = "ISOLATED";
    /** 质量检验完成（可提出处理决定） */
    public static final String RETURN_INSPECTED = "INSPECTED";
    /** 处理决定待复核（双人复核中，库位冻结） */
    public static final String RETURN_DECISION_PENDING = "DECISION_PENDING";
    /** 已放行（终态，双人复核通过后出隔离区） */
    public static final String RETURN_RELEASED = "RELEASED";
    /** 已销毁（终态） */
    public static final String RETURN_DESTROYED = "DESTROYED";
    /** 转待查（留在隔离区，可重新检验后再决定） */
    public static final String RETURN_PENDING_REVIEW = "PENDING_REVIEW";

    /** 移动类型：ISOLATE_IN 入隔离 / MOVE 隔离区内移动 / RELEASE_OUT 放行出库 / DESTROY_OUT 销毁出库 */
    public static final String MOVE_ISOLATE_IN = "ISOLATE_IN";
    public static final String MOVE_MOVE = "MOVE";
    public static final String MOVE_RELEASE_OUT = "RELEASE_OUT";
    public static final String MOVE_DESTROY_OUT = "DESTROY_OUT";

    /** 检验结论：PASS 合格 / FAIL 不合格 */
    public static final String INSPECT_RESULT_PASS = "PASS";
    public static final String INSPECT_RESULT_FAIL = "FAIL";

    /** 处理决定类型：RELEASE 放行 / DESTROY 销毁 / PENDING_REVIEW 转待查 */
    public static final String DECISION_RELEASE = "RELEASE";
    public static final String DECISION_DESTROY = "DESTROY";
    public static final String DECISION_PENDING_REVIEW = "PENDING_REVIEW";
    /** 决定复核状态：PENDING 待复核 / CONFIRMED 双人复核通过 / REJECTED 复核驳回 */
    public static final String DECISION_ST_PENDING = "PENDING";
    public static final String DECISION_ST_CONFIRMED = "CONFIRMED";
    public static final String DECISION_ST_REJECTED = "REJECTED";

    // V11 操作日志实体类型
    public static final String ENT_RETURN = "RETURN";
    public static final String ENT_RETURN_MOVEMENT = "RETURN_MOVEMENT";
    public static final String ENT_RETURN_INSPECTION = "RETURN_INSPECTION";
    public static final String ENT_RETURN_DECISION = "RETURN_DECISION";

    // V11 操作动作
    public static final String ACT_RETURN_REGISTER = "RETURN_REGISTER";
    public static final String ACT_RETURN_ISOLATE = "RETURN_ISOLATE";
    public static final String ACT_RETURN_MOVE = "RETURN_MOVE";
    public static final String ACT_RETURN_INSPECT = "RETURN_INSPECT";
    public static final String ACT_RETURN_DECIDE = "RETURN_DECIDE";
    public static final String ACT_RETURN_REVIEW = "RETURN_REVIEW";
    public static final String ACT_RETURN_EXPORT = "RETURN_EXPORT";

    // —— V11 Shiro 权限点 ——
    /** 退货办理：登记/入隔离/隔离区内库位移动 */
    public static final String PERM_RETURN_HANDLE = "coldchain:return:handle";
    /** 退货质量检验（质量人员） */
    public static final String PERM_RETURN_INSPECT = "coldchain:return:inspect";
    /** 提出退货处理决定（放行/销毁/转待查） */
    public static final String PERM_RETURN_DECIDE = "coldchain:return:decide";
    /** 退货处理决定复核（第二人，必须与提出人不同账号） */
    public static final String PERM_RETURN_REVIEW = "coldchain:return:review";
    /** 退货台账查看（页面/列表/详情） */
    public static final String PERM_RETURN_VIEW = "coldchain:return:view";
    /** 退货台账与证据链导出 */
    public static final String PERM_RETURN_EXPORT = "coldchain:return:export";

    // —— V12 电子签收与离线回执 ——
    /** 批次状态：签收冲突冻结（同一批次出现两份不同签收，待主管裁决；签收/结算/撤回停止） */
    public static final String BATCH_FROZEN = "FROZEN";

    // 回执来源：ONLINE 在线签收 / OFFLINE 离线回执补传
    public static final String ESIGN_ONLINE = "ONLINE";
    public static final String ESIGN_OFFLINE = "OFFLINE";

    // 回执状态：DRAFT 草稿（要素未齐）/ COMPLETE 要素齐全 / EFFECTIVE 生效 /
    // REJECTED 主管裁决作废（事实保留）/ DUPLICATE 内容一致的重复件
    public static final String RECEIPT_DRAFT = "DRAFT";
    public static final String RECEIPT_COMPLETE = "COMPLETE";
    public static final String RECEIPT_EFFECTIVE = "EFFECTIVE";
    public static final String RECEIPT_REJECTED = "REJECTED";
    public static final String RECEIPT_DUPLICATE = "DUPLICATE";

    // 补偿记录侧别：ESIGN 电子签收侧 / OFFLINE 离线回执侧（成对开立，pair_id 互指）
    public static final String COMP_ESIGN = "ESIGN";
    public static final String COMP_OFFLINE = "OFFLINE";
    // 补偿状态：OPEN 待补偿 / RESOLVED 已补偿闭环
    public static final String COMP_OPEN = "OPEN";
    public static final String COMP_RESOLVED = "RESOLVED";

    // 同步/上传结果：SUCCESS 全部成功 / PARTIAL 部分成功 / FAILED 全部失败
    public static final String SYNC_SUCCESS = "SUCCESS";
    public static final String SYNC_PARTIAL = "PARTIAL";
    public static final String SYNC_FAILED = "FAILED";

    // V12 操作日志实体类型
    public static final String ENT_ESIGN_RECEIPT = "ESIGN_RECEIPT";
    public static final String ENT_ESIGN_COMPENSATION = "ESIGN_COMPENSATION";
    public static final String ENT_ESIGN_CORRECTION = "ESIGN_CORRECTION";

    // V12 操作动作
    public static final String ACT_ESIGN_UPLOAD = "ESIGN_UPLOAD";
    public static final String ACT_ESIGN_SYNC = "ESIGN_SYNC";
    public static final String ACT_BATCH_FREEZE = "BATCH_FREEZE";
    public static final String ACT_ESIGN_ADJUDICATE = "ESIGN_ADJUDICATE";
    public static final String ACT_ESIGN_CORRECT = "ESIGN_CORRECT";
    public static final String ACT_COMP_CREATE = "COMPENSATION_CREATE";
    public static final String ACT_COMP_RESOLVE = "COMPENSATION_RESOLVE";

    // —— V12 Shiro 权限点 ——
    /** 电子签收上传/离线回执同步（司机/收货方） */
    public static final String PERM_ESIGN_UPLOAD = "coldchain:esign:upload";
    /** 电子签收回执/补偿台账查看 */
    public static final String PERM_ESIGN_VIEW = "coldchain:esign:view";
    /** 人工修正（只追加修正记录，不覆盖原始回执） */
    public static final String PERM_ESIGN_CORRECT = "coldchain:esign:correct";
    /** 签收冲突主管裁决 */
    public static final String PERM_ESIGN_ADJUDICATE = "coldchain:esign:adjudicate";

    // —— V13 药品批次追溯图 ——
    /** 追溯节点类型：采购（发货单创建锁定档案版本） */
    public static final String TRACE_PURCHASE = "PURCHASE";
    /** 仓储（派车备货/待发运） */
    public static final String TRACE_WAREHOUSE = "WAREHOUSE";
    /** 发运（在途） */
    public static final String TRACE_SHIP = "SHIP";
    /** 中转（分仓交接提交，责任主体随单转移） */
    public static final String TRACE_TRANSIT = "TRANSIT";
    /** 签收（按快照核对闭环） */
    public static final String TRACE_SIGN = "SIGN";
    /** 退货（退货登记即固化快照） */
    public static final String TRACE_RETURN = "RETURN";

    /** 节点来源：业务流程自动发布 */
    public static final String TRACE_SRC_AUTO = "AUTO";
    /** 手工发布 */
    public static final String TRACE_SRC_MANUAL = "MANUAL";
    /** 漏记节点补建（仍是不可变快照，只是业务发生在过去） */
    public static final String TRACE_SRC_BACKFILL = "BACKFILL";

    /** 并发仲裁/审计结果：WIN 生效 / LOST 被拒绝 / INFO 一般留痕 / REPLAY 幂等重放 */
    public static final String TRACE_WIN = "WIN";
    public static final String TRACE_LOST = "LOST";
    public static final String TRACE_INFO = "INFO";
    public static final String TRACE_REPLAY = "REPLAY";

    /** 节点补充类型 */
    public static final String SUPP_NOTE = "NOTE";
    public static final String SUPP_FILE = "FILE";
    public static final String SUPP_CORRECTION_REF = "CORRECTION_REF";

    /** 授权状态 */
    public static final String GRANT_ACTIVE = "ACTIVE";
    public static final String GRANT_REVOKED = "REVOKED";
    /** 全域机构授权通配 */
    public static final String TRACE_ORG_ALL = "*";

    // V13 操作日志实体类型
    public static final String ENT_TRACE_NODE = "TRACE_NODE";
    public static final String ENT_TRACE_GRANT = "TRACE_GRANT";

    // V13 操作动作
    public static final String ACT_TRACE_PUBLISH = "TRACE_PUBLISH";
    public static final String ACT_TRACE_SUPPLEMENT = "TRACE_SUPPLEMENT";
    public static final String ACT_TRACE_EXPORT = "TRACE_EXPORT";
    public static final String ACT_TRACE_GRANT = "TRACE_GRANT";
    public static final String ACT_TRACE_GRANT_REVOKE = "TRACE_GRANT_REVOKE";

    // —— V13 Shiro 权限点 ——
    /** 追溯图查看（节点/链路/反查；可见机构与联系方式再按 t_cc_trace_org_grant 收窄） */
    public static final String PERM_TRACE_VIEW = "coldchain:trace:view";
    /** 追溯节点手工发布/漏记补建/节点补充 */
    public static final String PERM_TRACE_PUBLISH = "coldchain:trace:publish";
    /** 追溯结果导出（CSV 头标明数据截止时间与过滤条件，逐次留痕） */
    public static final String PERM_TRACE_EXPORT = "coldchain:trace:export";
    /** 岗位机构授权维护（决定各岗位可见机构范围与联系方式明文） */
    public static final String PERM_TRACE_GRANT = "coldchain:trace:grant";
}
