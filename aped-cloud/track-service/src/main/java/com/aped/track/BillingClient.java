package com.aped.track;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * track → billing 同步调用：轨迹查询按运单号取运单行（waybills 归 billing-service 独占）。
 * 运单不存在时下游 404，由调用方按「无此运单」分支处理。
 */
@FeignClient(name = "billing-service")
public interface BillingClient {

    @GetMapping("/internal/waybills/{no}")
    Map<String, Object> getWaybill(@PathVariable("no") String no);
}
