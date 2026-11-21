package com.aped.report;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * report → billing 只读聚合：运单计费快照与账单全量（waybills/bills 归 billing-service 独占），
 * 供运营汇总、工作台与运单/账单数据集在本服务内按原版 SQL 口径聚合。
 */
@FeignClient(name = "billing-service")
public interface BillingClient {

    @GetMapping("/internal/waybills")
    Map<String, Object> waybills();

    @GetMapping("/internal/bills")
    Map<String, Object> bills();
}
