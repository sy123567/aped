package com.aped.customer;

import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 批次0骨架探活接口：证明服务可启动、可注册、可被网关转发。业务接口在后续批次按域迁移。 */
@RestController
public class PingController {
    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of("ok", true, "data", Map.of(
                "service", "customer-service",
                "desc", "客户档案、地址簿、月结协议、服务范围",
                "ts", Instant.now().toString()));
    }
}
