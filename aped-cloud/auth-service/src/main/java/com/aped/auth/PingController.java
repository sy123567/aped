package com.aped.auth;

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
                "service", "auth-service",
                "desc", "登录鉴权 / JWT 签发",
                "ts", Instant.now().toString()));
    }
}
