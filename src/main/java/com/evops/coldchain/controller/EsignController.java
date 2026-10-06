package com.evops.coldchain.controller;

import com.evops.coldchain.dto.EsignAdjudicateRequest;
import com.evops.coldchain.dto.EsignCorrectRequest;
import com.evops.coldchain.dto.EsignSyncRequest;
import com.evops.coldchain.dto.EsignUploadRequest;
import com.evops.coldchain.entity.SignCompensation;
import com.evops.coldchain.entity.SignCorrection;
import com.evops.coldchain.service.EsignService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * 电子签收与离线回执（V12）：
 * 离线草稿按回执编号合并上传签名/照片/定位；同一批次两份不同签收冻结待主管裁决；
 * 签收时间设备/服务器并列展示；人工修正只追加不覆盖；部分成功补偿成对彼此对应。
 */
@RestController
@RequestMapping("/api/cold-chain/esign")
public class EsignController {

    private final EsignService esignService;

    public EsignController(EsignService esignService) {
        this.esignService = esignService;
    }

    /** 单回执上传（离线草稿/补传签名、照片、定位；按回执编号合并，requestId幂等） */
    @PostMapping("/receipts")
    public ApiResponse<Map<String, Object>> upload(@Valid @RequestBody EsignUploadRequest req) {
        return ApiResponse.ok(esignService.upload(req));
    }

    /** 离线回执批量同步（部分成功时为失败回执成对开立电子签收侧↔离线回执侧补偿） */
    @PostMapping("/sync")
    public ApiResponse<Map<String, Object>> sync(@Valid @RequestBody EsignSyncRequest req) {
        return ApiResponse.ok(esignService.sync(req));
    }

    /** 主管裁决：同一批次两份不同签收冻结后，选定生效回执，其余置REJECTED（事实保留） */
    @PostMapping("/receipts/adjudicate")
    public ApiResponse<Map<String, Object>> adjudicate(@Valid @RequestBody EsignAdjudicateRequest req) {
        return ApiResponse.ok(esignService.adjudicate(req));
    }

    /** 人工修正（只追加修正记录，绝不覆盖原始回执） */
    @PostMapping("/receipts/corrections")
    public ApiResponse<SignCorrection> correct(@Valid @RequestBody EsignCorrectRequest req) {
        return ApiResponse.ok(esignService.correct(req));
    }

    /** 回执详情：原始要素+照片+修正+补偿+有效值视图+设备/服务器双时间并列 */
    @GetMapping("/receipts/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable("id") Long id) {
        return ApiResponse.ok(esignService.detail(id));
    }

    /** 批次电子签收台账：全部回执（含照片/修正）+ 补偿记录 + 冻结状态 */
    @GetMapping("/receipts/batch/{batchId}")
    public ApiResponse<Map<String, Object>> batchEsign(@PathVariable("batchId") Long batchId) {
        return ApiResponse.ok(esignService.batchEsignInfo(batchId));
    }

    /** 补偿记录台账（可按批次过滤；电子签收侧与离线回执侧成对互指） */
    @GetMapping("/compensations")
    public ApiResponse<List<SignCompensation>> compensations(
            @RequestParam(value = "batchId", required = false) Long batchId) {
        return ApiResponse.ok(esignService.listCompensations(batchId));
    }
}
