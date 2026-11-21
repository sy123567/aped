package com.aped.claim;

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
                "service", "claim-service",
                "desc", "理赔核定、审批、赔付",
                "ts", Instant.now().toString()));
    }
}
