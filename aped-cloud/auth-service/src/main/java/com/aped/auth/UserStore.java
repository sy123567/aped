package com.aped.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.stereotype.Component;

/**
 * 账号种子（与原版 db.js 完全一致）：角色账号 admin/dispatch/courier/claims/finance/ops，
 * 客户账号 zhangsan/lisi/wangwu（绑 customers 1/2/3），初始密码统一 123456。
 * 批次1 用内存存储；口令以 PBKDF2 加盐哈希保存（原版为 SQLite + scrypt，落库留待后续批次）。
 */
@Component
public class UserStore {

    public static class User {
        public final long id;
        public final String username;
        public final String role;
        public final Long employeeId;
        public final Long customerId;
        public final String displayName;
        public final boolean active = true;
        private String salt;
        private String pwdHash;
        /** 口令版本号：改密即 +1，此前签发的 token（pwdv 不符）全部失效（对应原版“改密删全部会话”）。 */
        public volatile int passwordVersion = 1;

        User(long id, String username, String role, Long employeeId, Long customerId,
             String displayName, String rawPassword) {
            this.id = id;
            this.username = username;
            this.role = role;
            this.employeeId = employeeId;
            this.customerId = customerId;
            this.displayName = displayName;
            setPassword(rawPassword);
        }

        void setPassword(String raw) {
            byte[] s = new byte[16];
            new SecureRandom().nextBytes(s);
            this.salt = Base64.getEncoder().encodeToString(s);
            this.pwdHash = hash(raw, s);
        }

        boolean verify(String raw) {
            if (raw == null) return false;
            String h = hash(raw, Base64.getDecoder().decode(salt));
            return MessageDigest.isEqual(h.getBytes(StandardCharsets.UTF_8),
                    pwdHash.getBytes(StandardCharsets.UTF_8));
        }

        private static String hash(String raw, byte[] salt) {
            try {
                PBEKeySpec spec = new PBEKeySpec(raw.toCharArray(), salt, 60_000, 256);
                byte[] out = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        .generateSecret(spec).getEncoded();
                return Base64.getEncoder().encodeToString(out);
            } catch (Exception e) {
                throw new IllegalStateException("口令哈希失败", e);
            }
        }
    }

    private final Map<String, User> byUsername = new LinkedHashMap<>();
    private final Map<Long, User> byId = new LinkedHashMap<>();

    public UserStore() {
        // employees 种子顺序（db.js）：S6001=1, K1001=2, K1002=3, D2001=4, C3001=5, F4001=6, O5001=7
        add(new User(1, "admin", "admin", null, null, "系统管理员", "123456"));
        add(new User(2, "dispatch", "dispatcher", 4L, null, "调度员老周", "123456"));
        add(new User(3, "courier", "courier", 2L, null, "快递员小李", "123456"));
        add(new User(4, "claims", "claims", 5L, null, "理赔专员小陈", "123456"));
        add(new User(5, "finance", "finance", 6L, null, "财务小赵", "123456"));
        add(new User(6, "ops", "ops", 7L, null, "运营小孙", "123456"));
        add(new User(7, "zhangsan", "customer", null, 1L, "张三", "123456"));
        add(new User(8, "lisi", "customer", null, 2L, "李四", "123456"));
        add(new User(9, "wangwu", "customer", null, 3L, "王五", "123456"));
    }

    private void add(User u) {
        byUsername.put(u.username, u);
        byId.put(u.id, u);
    }

    public User byUsername(String username) {
        return username == null ? null : byUsername.get(username);
    }

    public User byId(long id) {
        return byId.get(id);
    }
}
