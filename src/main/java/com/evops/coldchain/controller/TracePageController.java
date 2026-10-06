package com.evops.coldchain.controller;

import com.evops.coldchain.service.TraceOrgAuthService;
import com.evops.common.UserContext;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.annotation.Resource;

/**
 * 药品批次追溯图页面（V13，Thymeleaf 服务端渲染 + fetch 调用追溯 API）。
 * 页面数据与后台接口/CSV 导出读同一口径：按当前账号授权机构过滤、
 * 联系方式按授权明文/脱敏；页面内登记/发布/反查/导出再经 API 权限点二次校验。
 */
@Controller
@RequestMapping("/ui/cold-chain")
public class TracePageController {

    @Resource
    private TraceOrgAuthService orgAuthService;

    @GetMapping("/trace")
    public String trace(Model model) {
        model.addAttribute("currentUser", UserContext.currentName());
        model.addAttribute("auths", orgAuthService.myAuths());
        model.addAttribute("orgCodes", orgAuthService.myOrgCodes());
        return "coldchain/trace";
    }
}
