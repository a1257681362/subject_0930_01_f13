package com.evops.coldchain.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.entity.ReturnBatch;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.coldchain.service.ReturnService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * 退货批次隔离处理页面（V11，Thymeleaf 服务端渲染 + fetch 调用既有 API）。
 * 页面与后台接口、列表导出读同一视图查询（ReturnService.listReturnViews）：
 * 冷链药品运输对象被置为无效（撤回）后，页面展示的退货快照、原批次状态与
 * 导出 CSV、后台 JSON 严格同口径；页面内各操作再经 API 权限点二次校验。
 */
@Controller
@RequestMapping("/ui/cold-chain")
public class ReturnPageController {

    @Resource
    private ReturnService returnService;
    @Resource
    private TransportBatchMapper batchMapper;

    @GetMapping("/returns")
    public String returns(Model model) {
        // 与后台列表接口、列表导出完全相同的视图数据（同口径锚点）
        List<Map<String, Object>> views = returnService.listReturnViews(null);
        for (Map<String, Object> view : views) {
            ReturnBatch ret = (ReturnBatch) view.get("returnBatch");
            view.put("movements", returnService.listMovements(ret.getId()));
            view.put("inspections", returnService.listInspections(ret.getId()));
            view.put("decisions", returnService.listDecisions(ret.getId()));
        }
        model.addAttribute("returnViews", views);

        // 可登记退货的原销售批次（已作废批次不再提供登记入口；既有退货不受影响）
        List<TransportBatch> sourceCandidates = batchMapper.selectList(new QueryWrapper<TransportBatch>()
                .ne("status", CcConstants.BATCH_REVOKED)
                .orderByDesc("id"));
        model.addAttribute("sourceCandidates", sourceCandidates);
        return "coldchain/returns";
    }
}
