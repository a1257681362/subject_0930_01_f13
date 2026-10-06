package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 离线回执批量同步请求（恢复网络后一次上传多张回执）。
 *
 * 每个回执独立处理、独立记账：全部成功 SUCCESS；有成功有失败为部分成功 PARTIAL——
 * 此时为每张失败回执成对开立电子签收侧（ESIGN）与离线回执侧（OFFLINE）补偿记录，
 * 两条补偿 pair_id 互指、彼此对应；requestId 幂等。
 */
public class EsignSyncRequest {
    @NotBlank(message = "幂等键requestId不能为空")
    private String requestId;
    @NotEmpty(message = "同步回执列表不能为空")
    private List<EsignUploadRequest> receipts;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public List<EsignUploadRequest> getReceipts() { return receipts; }
    public void setReceipts(List<EsignUploadRequest> receipts) { this.receipts = receipts; }
}
