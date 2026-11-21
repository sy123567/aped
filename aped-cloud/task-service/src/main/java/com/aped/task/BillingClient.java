package com.aped.task;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * task → billing 同步调用：揽收登记生成运单计费快照（原版 createWaybillForOrder 的服务间版本）、
 * 读运单行（中转/派件/签收校验与拼装）、按运单状态机推进状态（转运中/派送中/已签收/异常）。
 * waybills 归 billing-service 独占，本服务不自持运单状态。运单不存在时下游 404；
 * 状态非法转移时下游 400 且 error 文案与原版 setWaybillStatus 一致，由调用方原样透传。
 */
@FeignClient(name = "billing-service")
public interface BillingClient {

    @PostMapping("/internal/waybills")
    Map<String, Object> createWaybill(@RequestBody Map<String, Object> body);

    @GetMapping("/internal/waybills/{no}")
    Map<String, Object> getWaybill(@PathVariable("no") String no);

    @PostMapping("/internal/waybills/{no}/status")
    Map<String, Object> setWaybillStatus(@PathVariable("no") String no, @RequestBody Map<String, Object> body);
}
