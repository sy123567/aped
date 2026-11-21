package com.aped.billing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运单计费快照（原版 waybills 表按全量计划归 billing-service）：
 * - POST /internal/waybills        生成运单并计费：对应原版 billing.js 的 createWaybillForOrder
 *                                  （原版由 dispatch 揽收登记在进程内调用；微服务版改为服务间接口，
 *                                  批次 4 由 task-service 揽收环节调用）。计费经 Feign 调 rate-service，
 *                                  本地不算钱；费率行（首重/续重等）一并快照进运单行。
 * - POST /internal/waybills/:no/sign 标记已签收 + signed_at（原版由派送签收环节写 waybills；
 *                                  批次 4 接入后由 task-service 驱动，本批次供出账链路验收）。
 * - GET  /waybills                 运单计费查询（对应原版 order.js 的 GET /waybills，客户只看自己）。
 * 内部接口走服务间调用，不挂用户角色限制（网关层仍要求登录态）。
 */
@RestController
public class WaybillController {

    private final BillingStore store;
    private final RateClient rateClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WaybillController(BillingStore store, RateClient rateClient) {
        this.store = store;
        this.rateClient = rateClient;
    }

    private static ResponseEntity<Map<String, Object>> fail(String msg, HttpStatus status) {
        return ResponseEntity.status(status).body(Map.of("ok", false, "error", msg));
    }

    private static ResponseEntity<Map<String, Object>> degraded() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", "计费服务暂不可用，暂时无法生成运单（降级）");
        m.put("degraded", true);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(m);
    }

    /* ---------------- 生成运单并计费（createWaybillForOrder 的服务间版本） ---------------- */

    @PostMapping("/internal/waybills")
    public ResponseEntity<?> create(@RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> body = b == null ? Map.of() : b;

        Long orderId = asLong(body.get("orderId"));
        Long customerId = asLong(body.get("customerId"));
        String destAddr = asString(body.get("destAddr"));
        if (destAddr == null) destAddr = asString(body.get("recvAddr"));

        // 月结标记：显式入参优先，否则按客户种子副本（与批次 2 order-service 同口径）
        Integer monthly = body.get("monthly") instanceof Number n ? n.intValue() : null;
        if (monthly == null) {
            BillingStore.Cust seed = BillingStore.seedCustomer(customerId);
            monthly = seed == null ? 0 : seed.monthly();
        }

        Map<String, Object> rateReq = new LinkedHashMap<>();
        rateReq.put("product", body.get("product"));
        rateReq.put("weight", body.get("weight"));
        rateReq.put("volumeL", body.get("volumeL"));
        rateReq.put("insured", body.get("insured"));
        rateReq.put("destAddr", destAddr == null ? "" : destAddr);
        rateReq.put("length", body.get("length"));
        rateReq.put("width", body.get("width"));
        rateReq.put("height", body.get("height"));
        rateReq.put("pack", Boolean.TRUE.equals(body.get("pack")));
        rateReq.put("monthly", monthly);

        Map<String, Object> fee = quoteViaRate(rateReq);
        if (fee == null) return degraded();

        // 费率快照行：对应原版 findRate(f.product) 读 products（按名匹配，未命中回落隔日达）
        Map<String, Object> rate = rateRowOf(String.valueOf(fee.get("product")));
        if (rate == null) return degraded();

        long promiseHours = fee.get("promise_hours") instanceof Number n ? n.longValue() : 48;
        String promiseTime = LocalDateTime.now().plusHours(promiseHours).format(BillingStore.FMT);

        double weight = asDoubleOr(body.get("weight"), 0);
        double volumeL = asDoubleOr(body.get("volumeL"), 0);
        double insured = asDoubleOr(body.get("insured"), 0);

        // fee_snapshot：键序与内容对应原版 createWaybillForOrder 的快照对象
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("chargeKg", fee.get("chargeKg"));
        snapshot.put("rawKg", fee.get("rawKg"));
        snapshot.put("volWeight", fee.get("volWeight"));
        snapshot.put("dims", fee.get("dims"));
        snapshot.put("zone", fee.get("zone"));
        snapshot.put("zone_label", fee.get("zone_label"));
        snapshot.put("zone_coef", fee.get("zone_coef"));
        snapshot.put("product", fee.get("product"));
        snapshot.put("weight", BillingStore.num(weight));
        snapshot.put("volumeL", BillingStore.num(volumeL));
        snapshot.put("insured", BillingStore.num(insured));
        snapshot.put("items", fee.get("items"));
        snapshot.put("total", fee.get("total"));
        snapshot.put("rules_version", "pricing-v3-dims");
        String snapshotJson;
        try {
            snapshotJson = objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            snapshotJson = "{}";
        }

        BillingStore.Waybill w = new BillingStore.Waybill();
        synchronized (store.lock) {
            List<String> nos = new ArrayList<>();
            for (BillingStore.Waybill x : store.waybills) nos.add(x.waybillNo);
            w.id = store.nextWaybillId();
            w.waybillNo = store.nextNo("88", 5, nos);
            w.orderId = orderId;
            w.orderNo = asString(body.get("orderNo"));
            w.customerId = customerId;
            w.sender = asString(body.get("sender"));
            w.recvName = asString(body.get("recvName"));
            w.recvAddr = destAddr;
            w.product = String.valueOf(fee.get("product"));
            w.firstKg = asDoubleOr(rate.get("first_kg"), 1);
            w.firstPrice = asDoubleOr(rate.get("first_price"), 12);
            w.contPrice = asDoubleOr(rate.get("cont_price"), 4);
            w.volDivisor = asDoubleOr(rate.get("vol_divisor"), 6000);
            w.insured = insured;
            w.fee = asDoubleOr(fee.get("total"), 0);
            w.feeDetail = asString(fee.get("detail"));
            w.status = "已揽收";
            w.createdAt = BillingStore.now();
            w.promiseTime = promiseTime;
            w.signedAt = null;
            w.feeSnapshot = snapshotJson;
            w.destZone = asString(fee.get("zone"));
            store.waybills.add(w);
        }

        // 返回对应原版 createWaybillForOrder 的返回对象
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("waybill_no", w.waybillNo);
        data.put("fee", fee.get("total"));
        data.put("detail", fee.get("detail"));
        data.put("promise_time", promiseTime);
        data.put("items", fee.get("items"));
        data.put("zone", fee.get("zone"));
        data.put("chargeKg", fee.get("chargeKg"));
        data.put("volWeight", fee.get("volWeight"));
        data.put("dims", fee.get("dims"));
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    /** 调 rate 试算；降级/异常/格式不对返回 null。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> quoteViaRate(Map<String, Object> rateReq) {
        Map<String, Object> resp;
        try {
            resp = rateClient.quote(rateReq);
        } catch (Exception e) {
            return null;
        }
        if (resp == null || Boolean.TRUE.equals(resp.get("degraded"))
                || !Boolean.TRUE.equals(resp.get("ok"))
                || !(resp.get("data") instanceof Map<?, ?> data)) {
            return null;
        }
        return new LinkedHashMap<>((Map<String, Object>) data);
    }

    /** 从 rate-service 的运价卡里找费率行（对应 findRate：按名 → 隔日达兜底）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> rateRowOf(String productName) {
        Map<String, Object> resp;
        try {
            resp = rateClient.rates();
        } catch (Exception e) {
            return null;
        }
        if (resp == null || Boolean.TRUE.equals(resp.get("degraded"))
                || !(resp.get("data") instanceof List<?> list)) {
            return null;
        }
        Map<String, Object> fallback = null;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> row)) continue;
            if (productName.equals(row.get("name"))) return (Map<String, Object>) row;
            if ("隔日达".equals(row.get("name"))) fallback = (Map<String, Object>) row;
        }
        return fallback;
    }

    /* ---------------- 签收标记（出账前置状态） ---------------- */

    @PostMapping("/internal/waybills/{no}/sign")
    public ResponseEntity<?> sign(@PathVariable String no,
                                  @RequestBody(required = false) Map<String, Object> b) {
        synchronized (store.lock) {
            BillingStore.Waybill w = store.waybillByNo(no);
            if (w == null) return fail("运单不存在", HttpStatus.NOT_FOUND);
            String signedAt = b == null ? null : asString(b.get("signed_at"));
            w.status = "已签收";
            w.signedAt = signedAt == null ? BillingStore.now() : signedAt;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("waybill_no", w.waybillNo);
            data.put("status", w.status);
            data.put("signed_at", w.signedAt);
            return ResponseEntity.ok(Map.of("ok", true, "data", data));
        }
    }

    /* ---------------- 服务间内部接口（批次 4：task-service 读运单、按状态机推进运单状态） ----------------
     * 原版单体内 dispatch.js 直接 SQL 读 waybills 并经 helpers.setWaybillStatus 推进状态；
     * 微服务版 waybills 归 billing 独占，故以内部接口等价开放。状态机 WB_FLOW 逐字照 helpers.js。 */

    private static final Map<String, List<String>> WB_FLOW = Map.of(
            "待揽收", List.of("已揽收", "已关闭"),
            "已揽收", List.of("转运中", "异常", "已关闭"),
            "转运中", List.of("派送中", "异常"),
            "派送中", List.of("已签收", "异常"),
            "异常", List.of("派送中", "已签收", "已关闭"),
            "已签收", List.of("已关闭"),
            "已关闭", List.of());

    @GetMapping("/internal/waybills/{no}")
    public ResponseEntity<?> internalGet(@PathVariable String no) {
        synchronized (store.lock) {
            BillingStore.Waybill w = store.waybillByNo(no);
            if (w == null) return fail("运单不存在", HttpStatus.NOT_FOUND);
            return ResponseEntity.ok(Map.of("ok", true, "data", waybillJson(w, true)));
        }
    }

    /**
     * 对应 helpers.setWaybillStatus：同状态幂等直接成功（不套用附加字段，同原版早返回）；
     * 非法转移 400 文案与原版一致；to=已签收 时可附带 signed_at（原版签收走 extraSql 写 signed_at）。
     */
    @PostMapping("/internal/waybills/{no}/status")
    public ResponseEntity<?> internalStatus(@PathVariable String no,
                                            @RequestBody(required = false) Map<String, Object> b) {
        String to = b == null ? null : asString(b.get("to"));
        if (to == null || to.isBlank()) return fail("to 必填", HttpStatus.BAD_REQUEST);
        String signedAt = b == null ? null : asString(b.get("signed_at"));
        synchronized (store.lock) {
            BillingStore.Waybill w = store.waybillByNo(no);
            if (w == null) return fail("运单不存在", HttpStatus.NOT_FOUND);
            if (!w.status.equals(to)) {
                if (!WB_FLOW.getOrDefault(w.status, List.of()).contains(to)) {
                    return fail("运单状态不能从「" + w.status + "」变更为「" + to + "」", HttpStatus.BAD_REQUEST);
                }
                w.status = to;
                if (signedAt != null) w.signedAt = signedAt;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("waybill_no", w.waybillNo);
            data.put("status", w.status);
            data.put("signed_at", w.signedAt);
            return ResponseEntity.ok(Map.of("ok", true, "data", data));
        }
    }

    /* ---------------- 服务间内部接口（批次 6：report-service 报表/汇总聚合读全量运单） ---------------- */

    @GetMapping("/internal/waybills")
    public ResponseEntity<?> internalList() {
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (store.lock) {
            for (BillingStore.Waybill w : store.waybills) { // 插入序 = id 升序，聚合口径与原版全表扫描一致
                out.add(waybillJson(w, true));
            }
        }
        return ResponseEntity.ok(Map.of("ok", true, "data", out));
    }

    /* ---------------- 运单计费查询 ---------------- */

    @GetMapping("/waybills")
    public ResponseEntity<?> list(HttpServletRequest req) {
        String role = req.getHeader("X-User-Role");
        if (role == null || role.isBlank()) return fail("未登录或登录已过期", HttpStatus.UNAUTHORIZED);
        Long ownId = null;
        if ("customer".equals(role)) {
            String cid = req.getHeader("X-User-Customer-Id");
            if (cid != null && !cid.isBlank() && !"null".equals(cid)) {
                try { ownId = Long.valueOf(cid); } catch (NumberFormatException ignored) { /* 保持 null */ }
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (store.lock) {
            List<BillingStore.Waybill> copy = new ArrayList<>(store.waybills);
            copy.sort((a, b2) -> Long.compare(b2.id, a.id)); // ORDER BY w.id DESC
            for (BillingStore.Waybill w : copy) {
                if (ownId != null && !ownId.equals(w.customerId)) continue;
                if ("customer".equals(role) && ownId == null) continue;
                out.add(waybillJson(w, true));
            }
        }
        return ResponseEntity.ok(Map.of("ok", true, "data", out));
    }

    private static Map<String, Object> waybillJson(BillingStore.Waybill w, boolean withOrder) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", w.id);
        m.put("waybill_no", w.waybillNo);
        m.put("order_id", w.orderId);
        m.put("product", w.product);
        m.put("first_kg", BillingStore.num(w.firstKg));
        m.put("first_price", BillingStore.num(w.firstPrice));
        m.put("cont_price", BillingStore.num(w.contPrice));
        m.put("vol_divisor", BillingStore.num(w.volDivisor));
        m.put("insured", BillingStore.num(w.insured));
        m.put("fee", BillingStore.num(w.fee));
        m.put("fee_detail", w.feeDetail);
        m.put("status", w.status);
        m.put("created_at", w.createdAt);
        m.put("promise_time", w.promiseTime);
        m.put("signed_at", w.signedAt);
        m.put("fee_snapshot", w.feeSnapshot);
        m.put("dest_zone", w.destZone);
        if (withOrder) {
            m.put("order_no", w.orderNo);
            m.put("recv_name", w.recvName);
            m.put("recv_addr", w.recvAddr);
            m.put("sender", w.sender);
            m.put("customer_id", w.customerId);
        }
        return m;
    }

    /* ---------------- 入参小工具 ---------------- */

    private static Double asDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static double asDoubleOr(Object o, double dflt) {
        Double v = asDouble(o);
        return v == null ? dflt : v;
    }

    private static Long asLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    private static String asString(Object o) {
        return o instanceof String s ? s : null;
    }
}
