package com.aped.claim;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * claim → billing 同步调用：理赔审批赔付时按运单状态机把运单推入「已关闭」
 * （waybills 归 billing-service 独占；原版 claim.js 在单体内经 helpers.setWaybillStatus 关单）。
 * 状态非法转移时下游 400 且 error 文案与原版一致，由调用方原样透传。
 */
@FeignClient(name = "billing-service")
public interface BillingClient {

    @PostMapping("/internal/waybills/{no}/status")
    Map<String, Object> setWaybillStatus(@PathVariable("no") String no, @RequestBody Map<String, Object> body);
}
