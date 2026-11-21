package com.aped.report;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * report → customer 只读：客户档案列表（账单数据集 JOIN 客户名用）。
 * 身份头由 FeignHeaderConfig 从当前请求透传，数据范围与原版 req.user 口径一致
 * （运营/财务/管理员可见全量）。
 */
@FeignClient(name = "customer-service")
public interface CustomerClient {

    @GetMapping("/customers")
    Map<String, Object> list();
}
