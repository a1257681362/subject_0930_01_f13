package com.evops.coldchain.controller;

import com.evops.coldchain.dto.HolidayRuleRequest;
import com.evops.coldchain.dto.HolidayVoidRequest;
import com.evops.coldchain.dto.ReceiveOrgCreateRequest;
import com.evops.coldchain.dto.WindowRuleDraftRequest;
import com.evops.coldchain.entity.DeliveryWindowRule;
import com.evops.coldchain.entity.HolidayRule;
import com.evops.coldchain.entity.ReceiveOrg;
import com.evops.coldchain.service.DeliveryWindowService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 送达时窗管理（V10）：
 * 收货机构建档 -> 按工作日维护可接收区间（规则只出新版本）-> 节假日/临时闭馆独立保存 ->
 * 派车解析承诺到达、改期重算预计到达。
 */
@RestController
@RequestMapping("/api/cold-chain/delivery-windows")
public class DeliveryWindowController {

    private final DeliveryWindowService windowService;

    public DeliveryWindowController(DeliveryWindowService windowService) {
        this.windowService = windowService;
    }

    /** 收货机构建档 */
    @PostMapping("/orgs/create")
    public ApiResponse<ReceiveOrg> createOrg(@Valid @RequestBody ReceiveOrgCreateRequest req) {
        return ApiResponse.ok(windowService.createOrg(req));
    }

    /** 收货机构列表 */
    @GetMapping("/orgs")
    public ApiResponse<List<ReceiveOrg>> listOrgs() {
        return ApiResponse.ok(windowService.listOrgs());
    }

    /** 收货机构详情（含当前生效版本ID） */
    @GetMapping("/orgs/{id}")
    public ApiResponse<ReceiveOrg> orgDetail(@PathVariable("id") Long id) {
        return ApiResponse.ok(windowService.requireOrg(id));
    }

    /** 保存时窗规则草稿（同一机构至多一个草稿；重复保存覆盖草稿区间，不产生新版本号） */
    @PostMapping("/orgs/{id}/rules/draft")
    public ApiResponse<DeliveryWindowRule> saveDraft(@PathVariable("id") Long id,
                                                     @Valid @RequestBody WindowRuleDraftRequest req) {
        return ApiResponse.ok(windowService.saveDraft(id, req));
    }

    /** 发布草稿（热替换当前生效版本；已固化承诺的批次仍按原版本） */
    @PostMapping("/orgs/{id}/rules/publish")
    public ApiResponse<DeliveryWindowRule> publish(@PathVariable("id") Long id) {
        return ApiResponse.ok(windowService.publish(id));
    }

    /** 机构时窗版本历史（含草稿/生效/被替换），历史版本可追查 */
    @GetMapping("/orgs/{id}/rules")
    public ApiResponse<List<DeliveryWindowRule>> listRules(@PathVariable("id") Long id) {
        return ApiResponse.ok(windowService.listRules(id));
    }

    /** 某版本全部工作日可接收区间 */
    @GetMapping("/rules/{ruleId}/slots")
    public ApiResponse<List<Map<String, Object>>> slots(@PathVariable("ruleId") Long ruleId) {
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (com.evops.coldchain.entity.DeliveryWindowSlot slot : windowService.listSlots(ruleId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("slot", slot);
            m.put("startTime", fmt(slot.getStartMinute()));
            m.put("endTime", fmt(slot.getEndMinute()));
            result.add(m);
        }
        return ApiResponse.ok(result);
    }

    /** 当前生效版本（无版本返回 data=null） */
    @GetMapping("/orgs/{id}/effective")
    public ApiResponse<Map<String, Object>> effective(@PathVariable("id") Long id) {
        DeliveryWindowRule rule = windowService.findEffectiveRule(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rule", rule);
        if (rule != null) {
            m.put("slots", windowService.listSlots(rule.getId()));
        }
        return ApiResponse.ok(m);
    }

    // ---------------- 节假日 / 临时闭馆（独立维护） ----------------

    /** 新增节假日（整日闭馆）或临时闭馆（单日/区间） */
    @PostMapping("/orgs/{id}/holidays/add")
    public ApiResponse<HolidayRule> addHoliday(@PathVariable("id") Long id,
                                               @Valid @RequestBody HolidayRuleRequest req) {
        return ApiResponse.ok(windowService.addHoliday(id, req));
    }

    /** 节假日/临时闭馆列表（默认仅生效，includeAll=true 含已作废） */
    @GetMapping("/orgs/{id}/holidays")
    public ApiResponse<List<HolidayRule>> listHolidays(@PathVariable("id") Long id,
                                                       @RequestParam(value = "includeAll", defaultValue = "false")
                                                       boolean includeAll) {
        return ApiResponse.ok(windowService.listHolidays(id, !includeAll));
    }

    /** 节假日/临时闭馆详情（历史版本可追查） */
    @GetMapping("/holidays/{holidayId}")
    public ApiResponse<HolidayRule> holidayDetail(@PathVariable("holidayId") Long holidayId) {
        return ApiResponse.ok(windowService.requireHoliday(holidayId));
    }

    /** 作废（冻结/已参与旧结论的规则拒绝；只追加状态，不删行） */
    @PostMapping("/holidays/{holidayId}/void")
    public ApiResponse<HolidayRule> voidHoliday(@PathVariable("holidayId") Long holidayId,
                                                @Valid @RequestBody HolidayVoidRequest req) {
        return ApiResponse.ok(windowService.voidHoliday(holidayId, req.getVoidReason()));
    }

    private String fmt(int minuteOfDay) {
        return String.format("%02d:%02d", minuteOfDay / 60, minuteOfDay % 60);
    }
}
