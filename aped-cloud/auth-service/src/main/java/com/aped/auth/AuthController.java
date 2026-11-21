package com.aped.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录 / 当前用户 / 退出 / 改密 —— 路径与返回格式与原版 services/auth.js 保持一致：
 * 成功 {ok:true,data:{...}}，失败 {ok:false,error:"..."} + 对应状态码。
 * 差异：token 由原版随机串 + 服务端会话表换成 JWT（自包含），其余字段不变。
 */
@RestController
public class AuthController {

    /** 七种角色可见模块（照抄原版 ROLE_MODULES） */
    static final Map<String, List<String>> ROLE_MODULES = Map.of(
            "admin", List.of("*"),
            "customer", List.of("my", "orders", "tracking"),
            "dispatcher", List.of("board", "orders", "dispatch", "pickup", "transit", "delivery", "tracking"),
            "courier", List.of("board", "pickup", "delivery", "tracking"),
            "claims", List.of("board", "exceptions", "tracking"),
            "finance", List.of("board", "billing", "customers", "reports"),
            "ops", List.of("board", "performance", "reports", "tracking"));

    /** 角色中文名（照抄原版 ROLE_LABEL） */
    static final Map<String, String> ROLE_LABEL = Map.of(
            "admin", "系统管理员",
            "customer", "寄件客户",
            "dispatcher", "调度员",
            "courier", "快递员",
            "claims", "理赔专员",
            "finance", "财务人员",
            "ops", "运营管理员");

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final UserStore users;
    private final JwtCodec jwt;
    private final long ttlHours;
    /** 已退出登录的 token 黑名单（原版删会话行；JWT 无状态，这里用内存黑名单等价实现）。 */
    private final Set<String> revoked = ConcurrentHashMap.newKeySet();

    public AuthController(UserStore users,
                          @Value("${aped.jwt.secret}") String secret,
                          @Value("${aped.jwt.ttl-hours:12}") long ttlHours) {
        this.users = users;
        this.jwt = new JwtCodec(secret);
        this.ttlHours = ttlHours;
    }

    private static ResponseEntity<Map<String, Object>> ok(Object data) {
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    private static ResponseEntity<Map<String, Object>> fail(String msg, HttpStatus status) {
        return ResponseEntity.status(status).body(Map.of("ok", false, "error", msg));
    }

    private static Map<String, Object> userJson(UserStore.User u, boolean withMeta) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.id);
        m.put("username", u.username);
        m.put("role", u.role);
        if (withMeta) m.put("role_label", ROLE_LABEL.getOrDefault(u.role, u.role));
        m.put("display_name", u.displayName);
        m.put("employee_id", u.employeeId);
        m.put("customer_id", u.customerId);
        if (withMeta) m.put("modules", ROLE_MODULES.getOrDefault(u.role, List.of()));
        return m;
    }

    /** 对应原版 tokenOf：Authorization: Bearer，或查询串 ?token=（报表导出场景）。 */
    private String tokenOf(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) return h.substring(7).trim();
        String q = req.getParameter("token");
        return q == null ? "" : q;
    }

    /** 校验当前请求 token，返回用户；无效返回 null（对应原版 currentUser + requireAuth）。 */
    private UserStore.User currentUser(HttpServletRequest req) {
        String token = tokenOf(req);
        if (token.isEmpty() || revoked.contains(token)) return null;
        Map<String, String> claims = jwt.verify(token);
        if (claims == null || claims.get("uid") == null) return null;
        UserStore.User u = users.byId(Long.parseLong(claims.get("uid")));
        if (u == null || !u.active) return null;
        int pwdv = claims.get("pwdv") == null ? -1 : Integer.parseInt(claims.get("pwdv"));
        if (pwdv != u.passwordVersion) return null; // 改密前的 token 已失效
        return u;
    }

    @PostMapping("/auth/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) Map<String, String> body) {
        String username = body == null ? null : body.get("username");
        String password = body == null ? null : body.get("password");
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return fail("请输入账号和密码", HttpStatus.BAD_REQUEST);
        }
        UserStore.User u = users.byUsername(username.trim());
        if (u == null || !u.active || !u.verify(password)) {
            return fail("账号或密码错误", HttpStatus.UNAUTHORIZED);
        }
        Map<String, Object> issued = jwt.issue(u, ttlHours);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", issued.get("token"));
        data.put("expires_at", FMT.format((Instant) issued.get("exp")));
        data.put("user", userJson(u, true));
        return ok(data);
    }

    @GetMapping("/auth/me")
    public ResponseEntity<Map<String, Object>> me(HttpServletRequest req) {
        UserStore.User u = currentUser(req);
        if (u == null) return fail("未登录或登录已过期", HttpStatus.UNAUTHORIZED);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.id);
        m.put("username", u.username);
        m.put("role", u.role);
        m.put("employee_id", u.employeeId);
        m.put("customer_id", u.customerId);
        m.put("display_name", u.displayName);
        m.put("active", u.active ? 1 : 0);
        m.put("role_label", ROLE_LABEL.getOrDefault(u.role, u.role));
        m.put("modules", ROLE_MODULES.getOrDefault(u.role, List.of()));
        return ok(m);
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest req) {
        UserStore.User u = currentUser(req);
        if (u == null) return fail("未登录或登录已过期", HttpStatus.UNAUTHORIZED);
        revoked.add(tokenOf(req));
        return ok(Map.of());
    }

    @PostMapping("/auth/password")
    public ResponseEntity<Map<String, Object>> password(HttpServletRequest req,
                                                        @RequestBody(required = false) Map<String, String> body) {
        UserStore.User u = currentUser(req);
        if (u == null) return fail("未登录或登录已过期", HttpStatus.UNAUTHORIZED);
        String oldPw = body == null ? null : body.get("old_password");
        String newPw = body == null ? null : body.get("new_password");
        if (!u.verify(oldPw == null ? "" : oldPw)) return fail("原密码不正确", HttpStatus.BAD_REQUEST);
        if (newPw == null || newPw.length() < 6) return fail("新密码至少 6 位", HttpStatus.BAD_REQUEST);
        u.setPassword(newPw);
        u.passwordVersion++; // 旧 token 全部失效，强制重登
        return ok(Map.of("ok", true));
    }
}
