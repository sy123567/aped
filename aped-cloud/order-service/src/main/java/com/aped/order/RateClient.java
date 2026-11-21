package com.aped.order;

import java.util.Map;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * order → rate 同步调用（对应全量计划的 Feign 调用矩阵）。
 * 按服务名 rate-service 经 Eureka + LoadBalancer 调用计费引擎试算，
 * 本地不做任何金额计算（对照验收时停掉 rate 即可证明链路真实性）。
 */
@FeignClient(name = "rate-service", fallback = RateClient.RateClientFallback.class)
public interface RateClient {

    /** 计费试算：请求/响应字段与 rate-service 的 POST /pricing/quote 契约一致。 */
    @PostMapping("/pricing/quote")
    Map<String, Object> quote(@RequestBody Map<String, Object> body);

    /**
     * 降级：rate-service 不可用（连接失败/熔断打开）时返回带 degraded 标记的结果，
     * 由 OrderController 统一转成 503 {ok:false,error:"计费服务暂不可用…",degraded:true}。
     * 降级时不本地估算、不臆造金额——计费口径只能出自计费引擎。
     */
    @Component
    class RateClientFallback implements RateClient {
        @Override
        public Map<String, Object> quote(Map<String, Object> body) {
            return Map.of("degraded", true);
        }
    }
}
