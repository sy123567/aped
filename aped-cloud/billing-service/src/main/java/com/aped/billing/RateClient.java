package com.aped.billing;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * billing → rate 同步调用（对应全量计划调用矩阵“billing → rate：揽收生成运单时计费”）。
 * 运单计费只认计费引擎：本地不算钱，rate 不可用时返回 degraded 标记，由调用方转 503。
 */
@FeignClient(name = "rate-service", fallback = RateClient.RateClientFallback.class)
public interface RateClient {

    /** 计费试算：契约与 rate-service 的 POST /pricing/quote 一致。 */
    @PostMapping("/pricing/quote")
    Map<String, Object> quote(@RequestBody Map<String, Object> body);

    /** 运价卡：生成运单时要把首重/续重等费率快照进运单行（对应原版 helpers.findRate 读 products）。 */
    @GetMapping("/rates")
    Map<String, Object> rates();

    @Component
    class RateClientFallback implements RateClient {
        @Override
        public Map<String, Object> quote(Map<String, Object> body) {
            return Map.of("degraded", true);
        }

        @Override
        public Map<String, Object> rates() {
            return Map.of("degraded", true);
        }
    }
}
