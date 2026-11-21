package com.aped.billing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * billing → customer 同步调用（对应全量计划调用矩阵“billing → customer：出账校验月结客户”）。
 * 账单列表/详情里的 customer_name、monthly 也经此取（原版是 JOIN customers）。
 * 身份头由 FeignHeaderConfig 从当前请求透传（财务/管理员可查任意客户，客户只能查自己，
 * 与 customer-service 的数据范围控制正好咬合）。
 * 降级：customer-service 不可用时回落到与原版 db.js 种子一致的本地副本（与批次 2
 * order-service 的口径相同），保证出账链路不因档案服务抖动而中断；种子外 id 返回 ok:false。
 */
@FeignClient(name = "customer-service", fallback = CustomerClient.CustomerClientFallback.class)
public interface CustomerClient {

    @GetMapping("/customers/{id}")
    Map<String, Object> getCustomer(@PathVariable("id") long id);

    @GetMapping("/customers")
    Map<String, Object> listCustomers();

    @Component
    class CustomerClientFallback implements CustomerClient {

        private static Map<String, Object> customerJson(BillingStore.Cust c) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("name", c.name());
            m.put("phone", c.phone());
            m.put("addr", c.addr());
            m.put("monthly", c.monthly());
            m.put("created_at", BillingStore.now());
            return m;
        }

        @Override
        public Map<String, Object> getCustomer(long id) {
            BillingStore.Cust c = BillingStore.seedCustomer(id);
            if (c == null) return Map.of("ok", false, "error", "客户不存在");
            return Map.of("ok", true, "data", Map.of("customer", customerJson(c)));
        }

        @Override
        public Map<String, Object> listCustomers() {
            List<Map<String, Object>> out = new ArrayList<>();
            for (BillingStore.Cust c : BillingStore.CUSTOMER_SEED) out.add(customerJson(c));
            return Map.of("ok", true, "data", out);
        }
    }
}
