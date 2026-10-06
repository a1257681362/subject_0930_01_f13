package com.evops.coldchain.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.coldchain.service.TraceService;
import org.apache.shiro.SecurityUtils;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.annotation.Resource;
import java.util.List;

/**
 * 药品批次追溯图页面（V13，Thymeleaf 服务端渲染 + fetch 调用既有 API）。
 * 图/反查/导出与后台 JSON 读同一服务口径：未授权机构与联系方式统一掩码，
 * 导出 CSV 标明数据截止时间与过滤条件。
 */
@Controller
@RequestMapping("/ui/cold-chain")
public class TracePageController {

    @Resource
    private TraceService traceService;
    @Resource
    private TransportBatchMapper batchMapper;

    @GetMapping("/traces")
    public String traces(Model model) {
        List<TransportBatch> batches = batchMapper.selectList(new QueryWrapper<TransportBatch>()
                .orderByDesc("id").last("LIMIT 100"));
        model.addAttribute("batches", batches);
        model.addAttribute("canPublish", SecurityUtils.getSubject()
                .isPermitted(com.evops.coldchain.CcConstants.PERM_TRACE_PUBLISH));
        model.addAttribute("canExport", SecurityUtils.getSubject()
                .isPermitted(com.evops.coldchain.CcConstants.PERM_TRACE_EXPORT));
        model.addAttribute("canGrant", SecurityUtils.getSubject()
                .isPermitted(com.evops.coldchain.CcConstants.PERM_TRACE_GRANT));
        return "coldchain/traces";
    }
}
