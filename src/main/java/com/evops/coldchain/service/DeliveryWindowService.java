package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.HolidayRuleRequest;
import com.evops.coldchain.dto.ReceiveOrgCreateRequest;
import com.evops.coldchain.dto.WindowRuleDraftRequest;
import com.evops.coldchain.entity.DeliveryWindowRule;
import com.evops.coldchain.entity.DeliveryWindowSlot;
import com.evops.coldchain.entity.HolidayRule;
import com.evops.coldchain.entity.ReceiveOrg;
import com.evops.coldchain.mapper.DeliveryWindowRuleMapper;
import com.evops.coldchain.mapper.DeliveryWindowSlotMapper;
import com.evops.coldchain.mapper.HolidayRuleMapper;
import com.evops.coldchain.mapper.ReceiveOrgMapper;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 送达时窗服务（V10）。
 *
 * 规则口径：
 * - 收货机构按工作日（1=周一~7=周日）维护可接收区间；区间挂在"规则版本"下，
 *   调整只能生成新版本（唯一草稿 -> 发布热替换），版本行与区间行发布后永不修改。
 * - 节假日（HOLIDAY，整日闭馆）与临时闭馆（CLOSED，单日/一段时间）独立保存，
 *   不随规则版本走；一旦参与过已生效时窗结论（派车承诺或已生效改期 ETA），
 *   规则 frozen=TRUE，永久不可修改/作废——新的调整只能新增节假日/闭馆行。
 * - ETA 解析：计划发运 + 机构运输时长得到物理到达时刻，再按"工作日区间 + 节假日/闭馆"
 *   把它推到下一个可接收时刻；被跳过的节假日/闭馆行即"参与旧结论"的行，调用方负责冻结。
 */
@Service
public class DeliveryWindowService {

    /** ETA 向后推算的保护上限：配置异常（整年无区间）时避免死循环 */
    private static final int RESOLVE_MAX_DAYS = 366;
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    @Resource
    private ReceiveOrgMapper orgMapper;
    @Resource
    private DeliveryWindowRuleMapper ruleMapper;
    @Resource
    private DeliveryWindowSlotMapper slotMapper;
    @Resource
    private HolidayRuleMapper holidayMapper;
    @Resource
    private SequenceMapper sequenceMapper;
    @Resource
    private OpLogService opLogService;

    // ---------------- 收货机构 ----------------

    @Transactional
    public ReceiveOrg createOrg(ReceiveOrgCreateRequest req) {
        String name = req.getOrgName().trim();
        if (findByName(name) != null) {
            throw new BusinessException("收货机构已存在: " + name);
        }
        ReceiveOrg org = new ReceiveOrg();
        org.setOrgNo("RO" + sequenceMapper.nextReceiveOrgNo());
        org.setOrgName(name);
        org.setContactName(trimToNull(req.getContactName()));
        org.setContactPhone(trimToNull(req.getContactPhone()));
        org.setTransportMinutes(req.getTransportMinutes());
        org.setStatus(CcConstants.ACTIVE);
        org.setRemark(trimToNull(req.getRemark()));
        orgMapper.insert(org);

        opLogService.log(CcConstants.ENT_RECEIVE_ORG, org.getId(), null,
                CcConstants.ACT_RECEIVE_ORG_CREATE,
                String.format("收货机构建档：%s（%s），默认运输时长=%d分钟，联系人=%s",
                        org.getOrgName(), org.getOrgNo(), org.getTransportMinutes(),
                        org.getContactName() == null ? "" : org.getContactName()));
        return org;
    }

    public ReceiveOrg requireOrg(Long orgId) {
        ReceiveOrg org = orgMapper.selectById(orgId);
        if (org == null) {
            throw new BusinessException("收货机构不存在: " + orgId);
        }
        return org;
    }

    public ReceiveOrg requireActiveOrg(Long orgId) {
        ReceiveOrg org = requireOrg(orgId);
        if (CcConstants.REVOKED.equals(org.getStatus())) {
            throw new BusinessException("收货机构已停用: " + org.getOrgNo());
        }
        return org;
    }

    /** 按机构名找主档（与批次 receiver_org 对应）；未建档返回 null（不影响既有批次流程） */
    public ReceiveOrg findByName(String orgName) {
        if (orgName == null || orgName.trim().isEmpty()) {
            return null;
        }
        return orgMapper.selectOne(new QueryWrapper<ReceiveOrg>()
                .eq("org_name", orgName.trim()).last("LIMIT 1"));
    }

    public List<ReceiveOrg> listOrgs() {
        return orgMapper.selectList(new QueryWrapper<ReceiveOrg>().orderByDesc("id"));
    }

    // ---------------- 时窗规则版本（工作日区间） ----------------

    /**
     * 保存草稿（同一机构至多一个 DRAFT，重复保存覆盖草稿区间）。
     * 草稿从未生效、无任何批次引用，允许反复编排；一旦发布只能再出新版本。
     */
    @Transactional
    public DeliveryWindowRule saveDraft(Long orgId, WindowRuleDraftRequest req) {
        ReceiveOrg org = requireActiveOrg(orgId);
        List<WindowRuleDraftRequest.SlotDraft> slots = req.getSlots();
        if (slots == null || slots.isEmpty()) {
            throw new BusinessException("至少配置一个工作日可接收区间");
        }
        validateSlots(slots);

        DeliveryRuleDraft draft = findDraft(orgId);
        boolean created = draft == null;
        DeliveryWindowRule rule = draft == null ? new DeliveryWindowRule() : draft.rule;
        if (created) {
            rule.setOrgId(orgId);
            rule.setVersionNo(nextVersionNo(orgId));
            rule.setStatus(CcConstants.WINDOW_DRAFT);
        }
        rule.setChangeReason(trimToNull(req.getChangeReason()));
        if (created) {
            ruleMapper.insert(rule);
        } else {
            ruleMapper.updateById(rule);
            // 草稿区间可重排：清掉旧行（草稿从未生效，无历史结论引用）
            slotMapper.delete(new QueryWrapper<DeliveryWindowSlot>().eq("rule_id", rule.getId()));
            // 兼容历史 H2：物理删除草稿行后序列不回收，不影响版本只追加口径
        }

        int inserted = 0;
        for (WindowRuleDraftRequest.SlotDraft s : slots) {
            int[] hm = parseHourMinute(s);
            DeliveryWindowSlot slot = new DeliveryWindowSlot();
            slot.setRuleId(rule.getId());
            slot.setWeekday(s.getWeekday());
            slot.setStartMinute(hm[0]);
            slot.setEndMinute(hm[1]);
            slotMapper.insert(slot);
            inserted++;
        }

        opLogService.log(CcConstants.ENT_WINDOW_RULE, rule.getId(), null,
                CcConstants.ACT_WINDOW_DRAFT,
                String.format("收货机构 %s 送达时窗 V%d 草稿编排：%d 个工作日可接收区间（草稿可重排，发布后只出新版本），原因：%s",
                        org.getOrgName(), rule.getVersionNo(), inserted,
                        rule.getChangeReason() == null ? "" : rule.getChangeReason()));
        return rule;
    }

    /**
     * 发布草稿：热替换——机构当前生效版本置 SUPERSEDED，草稿转 EFFECTIVE 并更新机构当前版本指针。
     * 发布只影响此后解析（新派车的承诺与改期重算的预计）；已固化承诺的批次继续引用原版本。
     */
    @Transactional
    public DeliveryWindowRule publish(Long orgId) {
        ReceiveOrg org = requireActiveOrg(orgId);
        DeliveryRuleDraft draft = findDraft(orgId);
        if (draft == null) {
            throw new BusinessException("机构 " + org.getOrgName() + " 当前没有待发布的时窗草稿");
        }
        DeliveryWindowRule rule = draft.rule;
        List<DeliveryWindowSlot> slots = listSlots(rule.getId());
        if (slots.isEmpty()) {
            throw new BusinessException("草稿没有任何工作日区间，不能发布");
        }

        DeliveryWindowRule current = findEffectiveRule(orgId);
        if (current != null) {
            current.setStatus(CcConstants.SUPERSEDED);
            ruleMapper.updateById(current);
        }
        rule.setStatus(CcConstants.EFFECTIVE);
        rule.setPublishTime(LocalDateTime.now());
        rule.setPublishBy(UserContext.currentId());
        rule.setPublishName(UserContext.currentName());
        ruleMapper.updateById(rule);

        org.setCurrentRuleId(rule.getId());
        orgMapper.updateById(org);

        opLogService.log(CcConstants.ENT_WINDOW_RULE, rule.getId(), null,
                CcConstants.ACT_WINDOW_PUBLISH,
                String.format("收货机构 %s 送达时窗 V%d 发布生效（热替换 V%s）：工作日可接收区间 %d 段；"
                                + "已固化承诺时间的批次仍按原版本，节假日/临时闭馆规则独立保存不受影响。原因：%s",
                        org.getOrgName(), rule.getVersionNo(),
                        current == null ? "无（首次发布）" : String.valueOf(current.getVersionNo()),
                        slots.size(), rule.getChangeReason() == null ? "" : rule.getChangeReason()));
        return rule;
    }

    public DeliveryWindowRule requireRule(Long ruleId) {
        DeliveryWindowRule rule = ruleMapper.selectById(ruleId);
        if (rule == null) {
            throw new BusinessException("送达时窗规则版本不存在: " + ruleId);
        }
        return rule;
    }

    public List<DeliveryWindowRule> listRules(Long orgId) {
        return ruleMapper.selectList(new QueryWrapper<DeliveryWindowRule>()
                .eq("org_id", orgId).orderByDesc("version_no"));
    }

    public List<DeliveryWindowSlot> listSlots(Long ruleId) {
        return slotMapper.selectList(new QueryWrapper<DeliveryWindowSlot>()
                .eq("rule_id", ruleId)
                .orderByAsc("weekday").orderByAsc("start_minute"));
    }

    /** 当前生效规则版本；无则 null */
    public DeliveryWindowRule findEffectiveRule(Long orgId) {
        return ruleMapper.selectOne(new QueryWrapper<DeliveryWindowRule>()
                .eq("org_id", orgId)
                .eq("status", CcConstants.EFFECTIVE)
                .orderByDesc("version_no").last("LIMIT 1"));
    }

    // ---------------- 节假日 / 临时闭馆（独立保存） ----------------

    @Transactional
    public HolidayRule addHoliday(Long orgId, HolidayRuleRequest req) {
        ReceiveOrg org = requireActiveOrg(orgId);
        String kind = normalizeKind(req.getRuleKind());
        HolidayRule rule = new HolidayRule();
        rule.setOrgId(orgId);
        rule.setRuleKind(kind);
        rule.setHolidayName(req.getHolidayName().trim());
        rule.setStatus(CcConstants.HOLIDAY_ACTIVE);
        rule.setFrozen(false);

        if (CcConstants.HOLIDAY_HOLIDAY.equals(kind)) {
            if (req.getHolidayDate() == null) {
                throw new BusinessException("节假日必须指定日期 holidayDate");
            }
            LocalDate date = req.getHolidayDate();
            rule.setHolidayDate(date);
            rule.setHolidayYear(req.getHolidayYear() == null ? date.getYear() : req.getHolidayYear());
            if (!rule.getHolidayYear().equals(date.getYear())) {
                throw new BusinessException("节假日年份必须与日期所属年份一致");
            }
            Long dup = holidayMapper.selectCount(new QueryWrapper<HolidayRule>()
                    .eq("org_id", orgId)
                    .eq("rule_kind", CcConstants.HOLIDAY_HOLIDAY)
                    .eq("holiday_date", date)
                    .eq("status", CcConstants.HOLIDAY_ACTIVE));
            if (dup != null && dup > 0) {
                throw new BusinessException(String.format("机构 %s 在 %s 已有生效节假日规则，不能重复登记",
                        org.getOrgName(), date));
            }
        } else {
            if (req.getHolidayDate() != null) {
                rule.setHolidayDate(req.getHolidayDate());
            } else if (req.getClosedFrom() != null && req.getClosedTo() != null) {
                if (!req.getClosedTo().isAfter(req.getClosedFrom())) {
                    throw new BusinessException("临时闭馆终点必须晚于起点");
                }
                rule.setClosedFrom(req.getClosedFrom());
                rule.setClosedTo(req.getClosedTo());
            } else {
                throw new BusinessException("临时闭馆必须指定单日 holidayDate 或闭馆区间 closedFrom/closedTo");
            }
        }
        holidayMapper.insert(rule);

        opLogService.log(CcConstants.ENT_HOLIDAY, rule.getId(), null, CcConstants.ACT_HOLIDAY_ADD,
                String.format("收货机构 %s 新增%s：%s（%s）；与时窗规则版本独立保存，"
                                + "参与已生效时窗结论后即冻结，不可改动",
                        org.getOrgName(),
                        CcConstants.HOLIDAY_HOLIDAY.equals(kind) ? "节假日（整日不可接收）" : "临时闭馆",
                        rule.getHolidayName(), describeHoliday(rule)));
        return rule;
    }

    /** 作废节假日/临时闭馆：仅 ACTIVE 且从未参与旧结论（frozen=FALSE）允许；作废只置状态不删行 */
    @Transactional
    public HolidayRule voidHoliday(Long holidayId, String reason) {
        HolidayRule rule = holidayMapper.selectById(holidayId);
        if (rule == null) {
            throw new BusinessException("节假日/临时闭馆规则不存在: " + holidayId);
        }
        if (Boolean.TRUE.equals(rule.getFrozen())) {
            throw new BusinessException(String.format(
                    "%s 已参与批次（ID=%d）已生效的送达时窗结论并冻结，不能改动/作废；请新增规则进行调整",
                    rule.getHolidayName(), rule.getFrozenBatchId()));
        }
        if (CcConstants.HOLIDAY_VOID.equals(rule.getStatus())) {
            throw new BusinessException("规则已作废，不能重复作废");
        }
        rule.setStatus(CcConstants.HOLIDAY_VOID);
        rule.setVoidReason(reason.trim());
        rule.setVoidTime(LocalDateTime.now());
        holidayMapper.updateById(rule);

        ReceiveOrg org = orgMapper.selectById(rule.getOrgId());
        opLogService.log(CcConstants.ENT_HOLIDAY, rule.getId(), null, CcConstants.ACT_HOLIDAY_VOID,
                String.format("收货机构 %s 作废%s：%s（%s，从未参与已生效时窗结论）。原因：%s",
                        org == null ? rule.getOrgId() : org.getOrgName(),
                        CcConstants.HOLIDAY_HOLIDAY.equals(rule.getRuleKind()) ? "节假日" : "临时闭馆",
                        rule.getHolidayName(), describeHoliday(rule), reason));
        return rule;
    }

    public HolidayRule requireHoliday(Long holidayId) {
        HolidayRule rule = holidayMapper.selectById(holidayId);
        if (rule == null) {
            throw new BusinessException("节假日/临时闭馆规则不存在: " + holidayId);
        }
        return rule;
    }

    public List<HolidayRule> listHolidays(Long orgId, boolean activeOnly) {
        QueryWrapper<HolidayRule> qw = new QueryWrapper<HolidayRule>()
                .eq("org_id", orgId).orderByDesc("id");
        if (activeOnly) {
            qw.eq("status", CcConstants.HOLIDAY_ACTIVE);
        }
        return holidayMapper.selectList(qw);
    }

    /**
     * 冻结参与已生效时窗结论的节假日/闭馆行（派车固化承诺、改期生效时调用）。
     * 已冻结行重复冻结保持原值（首个引用批次作为追溯锚点不覆盖）。
     */
    @Transactional
    public void freezeHolidays(List<Long> holidayIds, Long batchId) {
        if (holidayIds == null || holidayIds.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (Long id : holidayIds) {
            HolidayRule rule = holidayMapper.selectById(id);
            if (rule == null || Boolean.TRUE.equals(rule.getFrozen())) {
                continue;
            }
            rule.setFrozen(true);
            rule.setFrozenTime(now);
            rule.setFrozenBy(UserContext.currentId());
            rule.setFrozenBatchId(batchId);
            holidayMapper.updateById(rule);
        }
    }

    // ---------------- ETA 解析 ----------------

    /** 时窗解析结果 */
    public static class WindowResolution {
        private final LocalDateTime eta;
        private final Long ruleId;
        private final Integer ruleVersionNo;
        /** 解析中实际阻断（跳过）的节假日/闭馆规则ID——即参与本结论的行 */
        private final List<Long> appliedHolidayIds;

        public WindowResolution(LocalDateTime eta, Long ruleId, Integer ruleVersionNo,
                                List<Long> appliedHolidayIds) {
            this.eta = eta;
            this.ruleId = ruleId;
            this.ruleVersionNo = ruleVersionNo;
            this.appliedHolidayIds = appliedHolidayIds;
        }

        public LocalDateTime getEta() { return eta; }
        public Long getRuleId() { return ruleId; }
        public Integer getRuleVersionNo() { return ruleVersionNo; }
        public List<Long> getAppliedHolidayIds() { return appliedHolidayIds; }
    }

    /**
     * 按机构当前生效规则与节假日集，把计划发运时间解析为预计可接收（送达）时刻。
     * 物理到达 = planShipTime + transportMinutes；落到闭馆日/区间或区间空档则向后推到下一个可接收时刻。
     *
     * @param planShipTime 计划发运时间（空=按当前时刻起算）
     */
    public WindowResolution resolveEta(ReceiveOrg org, LocalDateTime planShipTime) {
        DeliveryWindowRule rule = findEffectiveRule(org.getId());
        if (rule == null) {
            throw new BusinessException(String.format(
                    "收货机构 %s 尚未发布送达时窗规则，无法解析可接收时间，请先维护工作日区间并发布",
                    org.getOrgName()));
        }
        List<DeliveryWindowSlot> slots = listSlots(rule.getId());
        if (slots.isEmpty()) {
            throw new BusinessException("机构 " + org.getOrgName() + " 当前生效时窗 V"
                    + rule.getVersionNo() + " 没有任何工作日区间");
        }
        List<HolidayRule> holidays = listHolidays(org.getId(), true);

        LocalDateTime base = (planShipTime == null ? LocalDateTime.now() : planShipTime)
                .plusMinutes(org.getTransportMinutes());
        LocalDateTime candidate = base;
        Set<Long> applied = new LinkedHashSet<>();

        for (int day = 0; day <= RESOLVE_MAX_DAYS; day++) {
            // 1) 整日节假日
            HolidayRule wholeDay = wholeDayHoliday(holidays, candidate.toLocalDate());
            if (wholeDay != null) {
                applied.add(wholeDay.getId());
                candidate = candidate.toLocalDate().plusDays(1).atStartOfDay();
                continue;
            }
            // 2) 临时闭馆区间（含单日闭馆归一化为整日）
            HolidayRule blocking = blockingClosedRange(holidays, candidate);
            if (blocking != null) {
                applied.add(blocking.getId());
                LocalDateTime reopen = blocking.getClosedTo() != null
                        ? blocking.getClosedTo()
                        : blocking.getHolidayDate().plusDays(1).atStartOfDay();
                candidate = reopen.isAfter(candidate) ? reopen : candidate.toLocalDate().plusDays(1).atStartOfDay();
                continue;
            }
            // 3) 工作日区间
            int weekday = candidate.getDayOfWeek().getValue();
            List<DeliveryWindowSlot> today = slots.stream()
                    .filter(s -> s.getWeekday().equals(weekday))
                    .sorted(Comparator.comparingInt(DeliveryWindowSlot::getStartMinute))
                    .collect(Collectors.toList());
            if (today.isEmpty()) {
                candidate = candidate.toLocalDate().plusDays(1).atStartOfDay();
                continue;
            }
            int minuteOfDay = candidate.getHour() * 60 + candidate.getMinute();
            DeliveryWindowSlot hit = null;
            for (DeliveryWindowSlot s : today) {
                if (minuteOfDay < s.getStartMinute()) {
                    // 早于当日首个/本区间起点：等到该区间起点
                    return resolutionAt(candidate.toLocalDate().atTime(toLocalTime(s.getStartMinute())),
                            rule, applied);
                }
                if (minuteOfDay >= s.getStartMinute() && minuteOfDay < s.getEndMinute()) {
                    hit = s;
                    break;
                }
            }
            if (hit != null) {
                return resolutionAt(candidate, rule, applied);
            }
            // 落在区间空档/当日已截收：次日再算
            candidate = candidate.toLocalDate().plusDays(1).atStartOfDay();
        }
        throw new BusinessException(String.format(
                "收货机构 %s 的时窗规则在未来 %d 天内没有任何可接收时段，请检查工作日区间配置",
                org.getOrgName(), RESOLVE_MAX_DAYS));
    }

    private WindowResolution resolutionAt(LocalDateTime eta, DeliveryWindowRule rule, Set<Long> applied) {
        return new WindowResolution(eta, rule.getId(), rule.getVersionNo(), new ArrayList<>(applied));
    }

    /** 指定日期是否被 HOLIDAY 或单日 CLOSED 整日覆盖 */
    private HolidayRule wholeDayHoliday(List<HolidayRule> holidays, LocalDate date) {
        for (HolidayRule h : holidays) {
            if (CcConstants.HOLIDAY_HOLIDAY.equals(h.getRuleKind()) && date.equals(h.getHolidayDate())) {
                return h;
            }
            if (CcConstants.HOLIDAY_CLOSED.equals(h.getRuleKind())
                    && h.getClosedFrom() == null && date.equals(h.getHolidayDate())) {
                return h;
            }
        }
        return null;
    }

    /** candidate 是否落在某段区间闭馆内（边界：闭馆 [from,to)） */
    private HolidayRule blockingClosedRange(List<HolidayRule> holidays, LocalDateTime candidate) {
        for (HolidayRule h : holidays) {
            if (!CcConstants.HOLIDAY_CLOSED.equals(h.getRuleKind()) || h.getClosedFrom() == null) {
                continue;
            }
            boolean geFrom = !candidate.isBefore(h.getClosedFrom());
            boolean ltTo = candidate.isBefore(h.getClosedTo());
            if (geFrom && ltTo) {
                return h;
            }
        }
        return null;
    }

    // ---------------- 草稿/版本/区间校验 ----------------

    private DeliveryRuleDraft findDraft(Long orgId) {
        DeliveryWindowRule rule = ruleMapper.selectOne(new QueryWrapper<DeliveryWindowRule>()
                .eq("org_id", orgId)
                .eq("status", CcConstants.WINDOW_DRAFT)
                .orderByDesc("version_no").last("LIMIT 1"));
        return rule == null ? null : new DeliveryRuleDraft(rule);
    }

    private Integer nextVersionNo(Long orgId) {
        DeliveryWindowRule latest = ruleMapper.selectOne(new QueryWrapper<DeliveryWindowRule>()
                .eq("org_id", orgId).orderByDesc("version_no").last("LIMIT 1"));
        return latest == null ? 1 : latest.getVersionNo() + 1;
    }

    private void validateSlots(List<WindowRuleDraftRequest.SlotDraft> slots) {
        for (WindowRuleDraftRequest.SlotDraft s : slots) {
            parseHourMinute(s); // 格式与起终先后由 parse 统一校验
        }
        // 按星期分组后检查同日区间不重叠（端点相接允许）
        java.util.Map<Integer, List<WindowRuleDraftRequest.SlotDraft>> byWeekday = slots.stream()
                .collect(Collectors.groupingBy(WindowRuleDraftRequest.SlotDraft::getWeekday));
        for (java.util.Map.Entry<Integer, List<WindowRuleDraftRequest.SlotDraft>> e : byWeekday.entrySet()) {
            List<int[]> ranges = new ArrayList<>();
            for (WindowRuleDraftRequest.SlotDraft s : e.getValue()) {
                int[] hm = parseHourMinute(s);
                ranges.add(hm);
            }
            ranges.sort(Comparator.comparingInt(a -> a[0]));
            for (int i = 1; i < ranges.size(); i++) {
                if (ranges.get(i)[0] < ranges.get(i - 1)[1]) {
                    throw new BusinessException(String.format(
                            "星期%d 的可接收区间存在重叠：%02d:%02d 与前一区间终点 %02d:%02d 冲突",
                            e.getKey(), ranges.get(i)[0] / 60, ranges.get(i)[0] % 60,
                            ranges.get(i - 1)[1] / 60, ranges.get(i - 1)[1] % 60));
                }
            }
        }
    }

    /** 解析 HH:mm 为 [startMinute,endMinute] 并校验 0<=start<end<=1440 */
    private int[] parseHourMinute(WindowRuleDraftRequest.SlotDraft s) {
        LocalTime start;
        LocalTime end;
        try {
            start = LocalTime.parse(s.getStartTime().trim(), HM);
            end = LocalTime.parse(s.getEndTime().trim(), HM);
        } catch (Exception e) {
            throw new BusinessException(String.format(
                    "星期%d 区间时间格式错误，应为 HH:mm（如 09:00）", s.getWeekday()));
        }
        int startMinute = start.getHour() * 60 + start.getMinute();
        int endMinute = end.getHour() * 60 + end.getMinute();
        if (startMinute >= endMinute) {
            throw new BusinessException(String.format(
                    "星期%d 区间起点 %s 必须早于终点 %s（不支持跨午夜区间，请拆为两个工作日区间）",
                    s.getWeekday(), s.getStartTime(), s.getEndTime()));
        }
        return new int[]{startMinute, endMinute};
    }

    private LocalTime toLocalTime(int minuteOfDay) {
        return LocalTime.of(minuteOfDay / 60, minuteOfDay % 60);
    }

    private String normalizeKind(String kind) {
        if (kind == null) {
            throw new BusinessException("规则类别不能为空：HOLIDAY 节假日 / CLOSED 临时闭馆");
        }
        String k = kind.trim().toUpperCase();
        if (!CcConstants.HOLIDAY_HOLIDAY.equals(k) && !CcConstants.HOLIDAY_CLOSED.equals(k)) {
            throw new BusinessException("规则类别非法：HOLIDAY 节假日 / CLOSED 临时闭馆");
        }
        return k;
    }

    private String describeHoliday(HolidayRule rule) {
        if (rule.getHolidayDate() != null) {
            return rule.getHolidayDate().toString();
        }
        return rule.getClosedFrom() + " ~ " + rule.getClosedTo();
    }

    private String trimToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    /** 草稿包装（当前只有一层，保留以便与 RouteService 同口径扩展） */
    private static class DeliveryRuleDraft {
        private final DeliveryWindowRule rule;
        private DeliveryRuleDraft(DeliveryWindowRule rule) {
            this.rule = rule;
        }
    }
}
