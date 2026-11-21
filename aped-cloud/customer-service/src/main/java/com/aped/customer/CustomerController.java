package com.aped.customer;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户档案子集（对应原版 services/order.js 的 /customers、/customers/:id）。
 * 身份不由本服务验签：网关（gateway-service）校验 JWT 后注入 X-User-* 请求头，
 * 本服务只按头里的角色/customer_id 做数据范围控制；无身份头一律 401。
 * 批次1 范围：只返回客户档案本体；原版附带的订单数/费用汇总与 orders/waybills/bills
 * 子列表依赖 order/billing 批次，本批次不返回（见 PROGRESS 批次1 记录）。
 */
@RestController
public class CustomerController {

    private record Customer(long id, String name, String phone, String addr, int monthly, String createdAt) {}

    private static final List<Customer> CUSTOMERS = new ArrayList<>();
    private static final Map<Long, Customer> BY_ID = new LinkedHashMap<>();

    static {
        // 与原版 db.js 种子一致：张三（散客）/ 李四（月结）/ 王五（月结）
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        add(new Customer(1, "张三", "13800001111", "杭州市滨江区江陵路88号", 0, now));
        add(new Customer(2, "李四", "13900002222", "杭州市西湖区文三路100号", 1, now));
        add(new Customer(3, "王五", "13700003333", "上海市浦东新区张江路500号", 1, now));
    }

    private static void add(Customer c) {
        CUSTOMERS.add(c);
        BY_ID.put(c.id(), c);
    }

    private static Map<String, Object> json(Customer c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.id());
        m.put("name", c.name());
        m.put("phone", c.phone());
        m.put("addr", c.addr());
        m.put("monthly", c.monthly());
        m.put("created_at", c.createdAt());
        return m;
    }

    private static ResponseEntity<Map<String, Object>> fail(String msg, HttpStatus status) {
        return ResponseEntity.status(status).body(Map.of("ok", false, "error", msg));
    }

    /** 网关注入的身份；缺失返回 null。 */
    private record Identity(String role, Long customerId) {}

    private Identity identity(HttpServletRequest req) {
        String role = req.getHeader("X-User-Role");
        if (role == null || role.isBlank()) return null;
        String cid = req.getHeader("X-User-Customer-Id");
        return new Identity(role, (cid == null || cid.isBlank() || "null".equals(cid)) ? null : Long.valueOf(cid));
    }

    @GetMapping("/customers")
    public ResponseEntity<?> list(HttpServletRequest req) {
        Identity idn = identity(req);
        if (idn == null) return fail("未登录或登录已过期", HttpStatus.UNAUTHORIZED);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Customer c : CUSTOMERS) {
            // 对应原版：客户角色只查到自己一行，其余角色全量
            if ("customer".equals(idn.role()) && (idn.customerId() == null || idn.customerId() != c.id())) continue;
            out.add(json(c));
        }
        return ResponseEntity.ok(Map.of("ok", true, "data", out));
    }

    @GetMapping("/customers/{id}")
    public ResponseEntity<?> detail(@PathVariable long id, HttpServletRequest req) {
        Identity idn = identity(req);
        if (idn == null) return fail("未登录或登录已过期", HttpStatus.UNAUTHORIZED);
        if ("customer".equals(idn.role()) && (idn.customerId() == null || idn.customerId() != id)) {
            return fail("只能查看自己的账户", HttpStatus.FORBIDDEN); // 原版同文案、同状态码
        }
        Customer c = BY_ID.get(id);
        if (c == null) return fail("客户不存在", HttpStatus.NOT_FOUND);
        return ResponseEntity.ok(Map.of("ok", true, "data", Map.of("customer", json(c))));
    }
}
