package com.aped.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 极简 HS256 JWT 编解码（只用 JDK，不引第三方库）。
 * 与 gateway-service 的 JwtAuthGlobalFilter 使用同一密钥与载荷格式，载荷为扁平 JSON：
 * {"sub":username,"uid":id,"role":role,"eid":employee_id|null,"cid":customer_id|null,
 *  "name":display_name,"pwdv":口令版本号,"iat":epochSec,"exp":epochSec}
 */
public class JwtCodec {

    private final byte[] secret;

    public JwtCodec(String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return b64(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("JWT 签名失败", e);
        }
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 签发 token，返回 {token, exp(Instant)} */
    public Map<String, Object> issue(UserStore.User u, long ttlHours) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(ttlHours * 3600);
        String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String payload = "{"
                + "\"sub\":\"" + esc(u.username) + "\","
                + "\"uid\":" + u.id + ","
                + "\"role\":\"" + esc(u.role) + "\","
                + "\"eid\":" + (u.employeeId == null ? "null" : u.employeeId) + ","
                + "\"cid\":" + (u.customerId == null ? "null" : u.customerId) + ","
                + "\"name\":\"" + esc(u.displayName) + "\","
                + "\"pwdv\":" + u.passwordVersion + ","
                + "\"iat\":" + now.getEpochSecond() + ","
                + "\"exp\":" + exp.getEpochSecond()
                + "}";
        String data = b64(header.getBytes(StandardCharsets.UTF_8)) + "."
                + b64(payload.getBytes(StandardCharsets.UTF_8));
        return Map.of("token", data + "." + sign(data), "exp", exp);
    }

    /** 校验签名与过期时间；通过返回载荷字段（字符串形式），失败返回 null。 */
    public Map<String, String> verify(String token) {
        if (token == null) return null;
        String[] parts = token.split("\\.");
        if (parts.length != 3) return null;
        String data = parts[0] + "." + parts[1];
        String expected = sign(data);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        Map<String, String> claims = parseFlatJson(payload);
        String exp = claims.get("exp");
        if (exp == null || Instant.now().getEpochSecond() >= Long.parseLong(exp)) return null;
        return claims;
    }

    /** 解析扁平 JSON（值只含字符串/数字/null），供 JWT 载荷使用。 */
    static Map<String, String> parseFlatJson(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = Pattern.compile("\"(\\w+)\"\\s*:\\s*(\"[^\"]*\"|null|-?\\d+)").matcher(json);
        while (m.find()) {
            String v = m.group(2);
            if ("null".equals(v)) out.put(m.group(1), null);
            else if (v.startsWith("\"")) out.put(m.group(1), v.substring(1, v.length() - 1));
            else out.put(m.group(1), v);
        }
        return out;
    }
}
