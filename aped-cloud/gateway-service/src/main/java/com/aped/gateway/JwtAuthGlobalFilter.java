package com.aped.gateway;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 网关全局鉴权过滤器，对应原版 server.js 的 requireAuth + gate() 分发：
 * 1) 除 /ping、/auth/login 外，所有经网关的请求必须带有效 JWT，否则 401；
 * 2) 命中计费/揽派/理赔前缀时按角色拦截（规则与角色表逐条照抄原版），越权 403，admin 恒通过；
 * 3) 校验通过后把身份写入 X-User-* 头供下游服务使用，并先剥除客户端自带的同名头防伪造。
 * JWT 为 HS256，密钥与 auth-service 一致；载荷格式见 auth-service 的 JwtCodec。
 */
@Component
public class JwtAuthGlobalFilter implements GlobalFilter, Ordered {

    // 照抄原版 server.js 的三组前缀与角色
    private static final List<String> BILLING_PATHS = List.of("/rates", "/pricing", "/billing");
    private static final List<String> BILLING_ROLES = List.of("finance", "customer");
    private static final List<String> DISPATCH_PATHS = List.of("/dispatch", "/employees", "/flights", "/pickup", "/transit", "/delivery");
    private static final List<String> DISPATCH_ROLES = List.of("dispatcher", "courier");
    private static final List<String> CLAIM_PATHS = List.of("/exceptions", "/claims");
    private static final List<String> CLAIM_ROLES = List.of("claims");

    private static final List<String> IDENTITY_HEADERS =
            List.of("X-User-Id", "X-User-Name", "X-User-Role", "X-User-Customer-Id", "X-User-Employee-Id");

    private final byte[] secret;

    public JwtAuthGlobalFilter(@Value("${aped.jwt.secret}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public int getOrder() {
        return -100; // 在路由转发前执行（此时路径仍带 /gw/<服务> 前缀，本过滤器内做归一化）
    }

    /**
     * 路径归一化到服务内路径：/gw/<服务>/auth/login → /auth/login；
     * /api/orders → /orders（批次 7 起的正式入口，与原版 server.js 的 /api 挂载一致）；
     * 其余路径原样返回。
     */
    private static String effectivePath(String rawPath) {
        if (rawPath.startsWith("/gw/")) {
            int secondSlash = rawPath.indexOf('/', 4);
            if (secondSlash > 0) return rawPath.substring(secondSlash);
        }
        if (rawPath.startsWith("/api/")) return rawPath.substring(4);
        if (rawPath.equals("/api")) return "/";
        return rawPath;
    }

    private static boolean hit(String path, List<String> prefixes) {
        for (String x : prefixes) {
            if (path.equals(x) || path.startsWith(x + "/")) return true;
        }
        return false;
    }

    /** 命中的角色组（null = 只需登录）；对应原版 gate() 的挂载顺序：计费 → 揽派 → 理赔。 */
    private static List<String> requiredRoles(String path) {
        if (hit(path, BILLING_PATHS)) return BILLING_ROLES;
        if (hit(path, DISPATCH_PATHS)) return DISPATCH_ROLES;
        if (hit(path, CLAIM_PATHS)) return CLAIM_ROLES;
        return null;
    }

    private Mono<Void> deny(ServerWebExchange exchange, HttpStatus status, String msg) {
        ServerHttpResponse res = exchange.getResponse();
        res.setStatusCode(status);
        res.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"ok\":false,\"error\":\"" + msg + "\"}").getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = res.bufferFactory().wrap(body);
        return res.writeWith(Mono.just(buffer));
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("JWT 签名失败", e);
        }
    }

    /** 校验 JWT 签名与过期；通过返回载荷字段，失败返回 null。 */
    private Map<String, String> verify(String token) {
        if (token == null || token.isBlank()) return null;
        String[] parts = token.split("\\.");
        if (parts.length != 3) return null;
        String data = parts[0] + "." + parts[1];
        if (!MessageDigest.isEqual(sign(data).getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        Map<String, String> claims = new LinkedHashMap<>();
        Matcher m = Pattern.compile("\"(\\w+)\"\\s*:\\s*(\"[^\"]*\"|null|-?\\d+)").matcher(payload);
        while (m.find()) {
            String v = m.group(2);
            if ("null".equals(v)) claims.put(m.group(1), null);
            else if (v.startsWith("\"")) claims.put(m.group(1), v.substring(1, v.length() - 1));
            else claims.put(m.group(1), v);
        }
        String exp = claims.get("exp");
        if (exp == null || Instant.now().getEpochSecond() >= Long.parseLong(exp)) return null;
        return claims;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest req = exchange.getRequest();
        String path = effectivePath(req.getPath().value());

        // 先剥除客户端自带的身份头（防伪造），后面只写网关自己验出的身份
        ServerHttpRequest.Builder mutated = req.mutate().headers(h -> IDENTITY_HEADERS.forEach(h::remove));

        boolean publicPath = path.equals("/ping") || path.equals("/auth/login") || path.equals("/health");
        if (publicPath) {
            return chain.filter(exchange.mutate().request(mutated.build()).build());
        }

        String token = "";
        String auth = req.getHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) token = auth.substring(7).trim();
        if (token.isEmpty()) {
            String q = req.getQueryParams().getFirst("token"); // 与原版一致：允许查询串传 token
            if (q != null) token = q;
        }
        Map<String, String> claims = verify(token);
        if (claims == null) {
            return deny(exchange, HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
        String role = claims.get("role");
        List<String> roles = requiredRoles(path);
        if (roles != null && !"admin".equals(role) && !roles.contains(role)) {
            return deny(exchange, HttpStatus.FORBIDDEN, "当前角色（" + role + "）无此操作权限");
        }
        mutated.headers(h -> {
            h.set("X-User-Id", claims.get("uid"));
            h.set("X-User-Name", claims.get("sub"));
            h.set("X-User-Role", role);
            if (claims.get("cid") != null) h.set("X-User-Customer-Id", claims.get("cid"));
            if (claims.get("eid") != null) h.set("X-User-Employee-Id", claims.get("eid"));
        });
        return chain.filter(exchange.mutate().request(mutated.build()).build());
    }
}
