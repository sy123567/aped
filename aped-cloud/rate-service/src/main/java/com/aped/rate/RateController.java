package com.aped.rate;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 计费服务接口：
 * - GET  /rates         运价卡列表（对应原版 services/billing.js 的 GET /rates → helpers.getRates()）
 * - GET  /pricing/rules 计费规则/阶梯/区域展示（对应原版 GET /pricing/rules → pricing.pricingMeta()）
 * - POST /pricing/quote 运费试算（计费引擎 computeFee 本体，供 order-service 经 Feign 调用；
 *                        返回体与 computeFee 结果逐字一致，另加内部字段 promise_hours
 *                        供下单方计算承诺送达时间，order 对外响应会剔除该字段）
 * - PUT  /rates/:code、PUT /pricing/rules/:key 运价/规则维护（原版挂在 billing.js，
 *   但 products/pricing_rules 两表按全量计划归 rate-service，故落在本服务；批次3 迁入）。
 *   角色限制对应原版 allow('finance')（admin 恒通过），按网关注入的 X-User-Role 头执行。
 */
@RestController
public class RateController {

    @GetMapping("/rates")
    public ResponseEntity<Map<String, Object>> rates() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PricingEngine.Product p : PricingEngine.getRates()) out.add(PricingEngine.productJson(p));
        return ResponseEntity.ok(Map.of("ok", true, "data", out));
    }

    @GetMapping("/pricing/rules")
    public ResponseEntity<Map<String, Object>> pricingRules() {
        return ResponseEntity.ok(Map.of("ok", true, "data", PricingEngine.pricingMeta()));
    }

    @PostMapping("/pricing/quote")
    public ResponseEntity<Map<String, Object>> quote(@RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> body = b == null ? Map.of() : b;
        PricingEngine.QuoteInput in = new PricingEngine.QuoteInput(
                body.get("product") instanceof String s ? s : null,
                asDouble(body.get("weight")),
                asDouble(body.get("volumeL")),
                asDouble(body.get("insured")),
                asString(body.get("destAddr")),
                asDouble(body.get("length")),
                asDouble(body.get("width")),
                asDouble(body.get("height")),
                Boolean.TRUE.equals(body.get("pack")),
                body.get("monthly") instanceof Number n ? n.intValue() : null);
        Map<String, Object> fee = new LinkedHashMap<>(PricingEngine.computeFee(in));
        // 内部字段：承诺时效（小时），供 order-service 计算 promise_time；不属于 computeFee 原始返回
        fee.put("promise_hours", PricingEngine.findRate(in.product()).promiseHours());
        return ResponseEntity.ok(Map.of("ok", true, "data", fee));
    }

    /* ---------------- 运价/规则维护（批次3，对应原版 billing.js 的两个 PUT） ---------------- */

    private static ResponseEntity<Map<String, Object>> fail(String msg, HttpStatus status) {
        return ResponseEntity.status(status).body(Map.of("ok", false, "error", msg));
    }

    /** 对应原版 allow('finance')：无身份 401「未登录」；admin 恒通过；其余角色 403（文案同原版）。 */
    private static ResponseEntity<Map<String, Object>> requireFinance(HttpServletRequest req) {
        String role = req.getHeader("X-User-Role");
        if (role == null || role.isBlank()) return fail("未登录", HttpStatus.UNAUTHORIZED);
        if ("admin".equals(role) || "finance".equals(role)) return null;
        return fail("当前角色（" + role + "）无此操作权限", HttpStatus.FORBIDDEN);
    }

    /** 对应原版 num(v, d)：v 为 null/空串时取原值，否则 Number(v)；解析不了按保持原值处理。 */
    private static Double numOrKeep(Object v) {
        if (v == null) return null;
        if (v instanceof String s && s.isEmpty()) return null;
        return asDouble(v);
    }

    @PutMapping("/rates/{code}")
    public ResponseEntity<?> updateRate(@PathVariable String code,
                                        @RequestBody(required = false) Map<String, Object> b,
                                        HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> denied = requireFinance(req);
        if (denied != null) return denied;
        PricingEngine.Product cur = PricingEngine.productByCode(code);
        if (cur == null) return fail("产品不存在", HttpStatus.NOT_FOUND);
        Map<String, Object> body = b == null ? Map.of() : b;
        Double ph = numOrKeep(body.get("promise_hours"));
        PricingEngine.Product updated = PricingEngine.updateProduct(code,
                numOrKeep(body.get("coef")),
                numOrKeep(body.get("first_kg")),
                numOrKeep(body.get("first_price")),
                numOrKeep(body.get("cont_price")),
                numOrKeep(body.get("vol_divisor")),
                ph == null ? null : (int) Math.round(ph));
        return ResponseEntity.ok(Map.of("ok", true, "data", PricingEngine.productJson(updated)));
    }

    @PutMapping("/pricing/rules/{key}")
    public ResponseEntity<?> updateRule(@PathVariable String key,
                                        @RequestBody(required = false) Map<String, Object> b,
                                        HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> denied = requireFinance(req);
        if (denied != null) return denied;
        if (!PricingEngine.hasRule(key)) return fail("规则不存在", HttpStatus.NOT_FOUND);
        Object raw = b == null ? null : b.get("value");
        // 对应原版 Number(req.body?.value)：空串按 0（JS Number('')===0），缺失/非数字 → NaN → 400
        Double v;
        if (raw instanceof String s && s.isEmpty()) v = 0.0;
        else v = asDouble(raw);
        if (v == null || !Double.isFinite(v)) return fail("value 必须是数字", HttpStatus.BAD_REQUEST);
        PricingEngine.updateRule(key, v);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("key", key);
        data.put("value", PricingEngine.num(v));
        data.put("note", PricingEngine.ruleNote(key));
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    private static Double asDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static String asString(Object o) {
        return o instanceof String s ? s : null;
    }
}
