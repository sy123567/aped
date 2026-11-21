package com.aped.exception;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * exception → billing 同步调用：读运单行（异常登记校验/定责取保价额与运费/详情拼装）、
 * 按运单状态机推进状态（登记异常→「异常」、异常关闭/理赔关单→「已关闭」）。
 * waybills 归 billing-service 独占，本服务不自持运单状态。运单不存在时下游 404；
 * 状态非法转移时下游 400 且 error 文案与原版 setWaybillStatus 一致，由调用方原样透传。
 */
@FeignClient(name = "billing-service")
public interface BillingClient {

    @GetMapping("/internal/waybills/{no}")
    Map<String, Object> getWaybill(@PathVariable("no") String no);

    @PostMapping("/internal/waybills/{no}/status")
    Map<String, Object> setWaybillStatus(@PathVariable("no") String no, @RequestBody Map<String, Object> body);
}
