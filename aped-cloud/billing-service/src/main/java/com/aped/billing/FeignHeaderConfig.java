package com.aped.billing;

import feign.RequestInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 把网关注入的 X-User-* 身份头透传给下游 Feign 调用：
 * billing 调 customer-service 查客户档案/月结标记时，下游按这组头做数据范围控制，
 * 语义与原版单体内 req.user 一致。无当前请求（如内部定时调用）时不加头。
 */
@Configuration
public class FeignHeaderConfig {

    private static final String[] IDENTITY_HEADERS = {
            "X-User-Id", "X-User-Name", "X-User-Role", "X-User-Customer-Id", "X-User-Employee-Id"};

    @Bean
    public RequestInterceptor identityHeaderInterceptor() {
        return template -> {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
                HttpServletRequest req = attrs.getRequest();
                for (String h : IDENTITY_HEADERS) {
                    String v = req.getHeader(h);
                    if (v != null && !v.isBlank()) template.header(h, v);
                }
            }
        };
    }
}
