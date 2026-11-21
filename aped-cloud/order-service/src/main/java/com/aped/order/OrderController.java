package com.aped.order;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单服务（对应原版 services/order.js 的订单部分）：
 * - POST /orders/quote 运费试算：本地不算钱，组装参数后经 Feign 调 rate-service 计费引擎，
 *   返回体与原版一致（computeFee 结果 + promise_time）。
 * - POST /orders       下单与取件预约登记：校验/服务范围/编号规则与原版逐项一致，
 *   预估费同样来自 rate-service；订单先存内存（落库留待后续批次）。
 * - GET  /orders       订单列表：客户只看自己（原版口径），附 customer_name/monthly。
 * 身份来自网关注入的 X-User-* 头（同批次 1 口径）；POST /orders 的角色限制
 * 对应原版 allow('dispatcher','customer')（admin 恒通过），在服务内执行。
 * 批次 6 收口：下单受理/订单取消的轨迹事件经 Feign 写 track-service（events 归 track 独占，
 * best-effort 不阻断主流程，计划矩阵「最终一致」口径）；下单成功另写 notify 通知记录；
 * POST /orders/:id/cancel 在本批次补迁（原版备选流：客户取消，含揽收任务回滚联动）。
 */
@RestController
public class OrderController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RateClient rateClient;
    private final TrackClient trackClient;
    private final NotifyClient notifyClient;
    private final TaskClient taskClient;

    /** 内存订单表（原版 orders 表的内存等价；重启即重置，落库留待后续批次）。 */
    private final List<Map<String, Object>> orders = new ArrayList<>();
    private final AtomicLong idSeq = new AtomicLong(0);
    private final Object lock = new Object();

    public OrderController(RateClient rateClient, TrackClient trackClient,
                           NotifyClient notifyClient, TaskClient taskClient) {
        this.rateClient = rateClient;
        this.trackClient = trackClient;
        this.notifyClient = notifyClient;
        this.taskClient = taskClient;
    }

    /* ---------------- 轨迹/通知旁路写入（best-effort，失败不阻断主流程） ---------------- */

    /** 对应原版 helpers.event()：waybill_no 下单阶段为空串，order_no 为订单号。 */
    private void writeEvent(String node, String desc, String orderNo) {
        try {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("waybill_no", "");
            e.put("order_no", orderNo);
            e.put("node", node);
            e.put("desc_text", desc);
            trackClient.addEvent(e);
        } catch (Exception ignored) {
            // 轨迹为旁路日志：track 不可用时不阻断下单/取消（最终一致，见 PROGRESS 批次 6）
        }
    }

    private void writeNotify(String type, String refNo, String content, String recipient) {
        try {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("type", type);
            n.put("ref_no", refNo);
            n.put("content", content);
            n.put("recipient", recipient);
            notifyClient.create(n);
        } catch (Exception ignored) {
            // 通知为横切旁路：失败不阻断主流程（计划矩阵口径）
        }
    }

    /** JS 模板字符串里的数字形态：整数不带小数，其余最短表示（与 BillingStore.fmt 同口径）。 */
    private static String jsFmt(Object o) {
        if (o instanceof Number n) {
            double v = n.doubleValue();
            if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
            return Double.toString(v);
        }
        return String.valueOf(o);
    }

    /* ---------------- 身份（网关注入） ---------------- */

    private record Identity(String role, Long customerId) {}

    private Identity identity(HttpServletRequest req) {
        String role = req.getHeader("X-User-Role");
        if (role == null || role.isBlank()) return null;
        String cid = req.getHeader("X-User-Customer-Id");
        Long custId = null;
        if (cid != null && !cid.isBlank() && !"null".equals(cid)) {
            try { custId = Long.valueOf(cid); } catch (NumberFormatException ignored) { /* 保持 null */ }
        }
        return new Identity(role, custId);
    }

    private static ResponseEntity<Map<String, Object>> fail(String msg, HttpStatus status) {
        return ResponseEntity.status(status).body(Map.of("ok", false, "error", msg));
    }

    private static ResponseEntity<Map<String, Object>> degraded() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", "计费服务暂不可用，暂时无法试算（降级）");
        m.put("degraded", true);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(m);
    }

    /* ---------------- 试算：组装参数 → Feign 调 rate ---------------- */

    /** 调 rate-service 试算；返回 data（含 promise_hours 已剔除前的原始 data），降级/异常返回 null。 */
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
        @SuppressWarnings("unchecked")
        Map<String, Object> fee = new LinkedHashMap<>((Map<String, Object>) data);
        return fee;
    }

    private static Map<String, Object> rateRequest(Map<String, Object> b, String destAddr, Integer monthly) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("product", b.get("product"));
        req.put("weight", b.get("weight"));
        req.put("volumeL", b.get("volumeL"));
        req.put("insured", b.get("insured"));
        req.put("destAddr", destAddr);
        req.put("length", b.get("length"));
        req.put("width", b.get("width"));
        req.put("height", b.get("height"));
        req.put("pack", Boolean.TRUE.equals(b.get("pack")));
        req.put("monthly", monthly);
        return req;
    }

    @PostMapping("/orders/quote")
    public ResponseEntity<?> quote(@RequestBody(required = false) Map<String, Object> b,
                                   HttpServletRequest http) {
        if (identity(http) == null) return fail("未登录或登录已过期", HttpStatus.UNAUTHORIZED);
        Map<String, Object> body = b == null ? Map.of() : b;
        Double weight = asDouble(body.get("weight"));
        if (weight == null || !(weight > 0)) return fail("请填写预估重量", HttpStatus.BAD_REQUEST);

        Long custId = asLong(body.get("customer_id"));
        OrderData.CustomerInfo cust = OrderData.customer(custId);
        String dest = asString(body.get("dest_addr"));
        if (dest == null) dest = asString(body.get("recv_addr"));
        Map<String, Object> fee = quoteViaRate(rateRequest(body, dest == null ? "" : dest,
                cust == null ? null : cust.monthly()));
        if (fee == null) return degraded();

        Object ph = fee.remove("promise_hours");
        long promiseHours = ph instanceof Number n ? n.longValue() : 48;
        fee.put("promise_time", LocalDateTime.now().plusHours(promiseHours).format(FMT));
        return ResponseEntity.ok(Map.of("ok", true, "data", fee));
    }

    /* ---------------- 下单与取件预约登记 ---------------- */

    @PostMapping("/orders")
    public ResponseEntity<?> create(@RequestBody(required = false) Map<String, Object> b,
                                    HttpServletRequest http) {
        Identity idn = identity(http);
        if (idn == null) return fail("未登录", HttpStatus.UNAUTHORIZED);
        // 对应原版 allow('dispatcher','customer')：admin 恒通过，其余角色 403（文案同原版）
        if (!"admin".equals(idn.role()) && !"dispatcher".equals(idn.role()) && !"customer".equals(idn.role())) {
            return fail("当前角色（" + idn.role() + "）无此操作权限", HttpStatus.FORBIDDEN);
        }
        Map<String, Object> body = b == null ? Map.of() : b;

        // 客户自己下单：客户身份锁定为本人；后台（调度）可代客下单
        Long customerId = "customer".equals(idn.role()) ? idn.customerId() : asLong(body.get("customer_id"));

        String sender = asString(body.get("sender"));
        String recvName = asString(body.get("recv_name"));
        String recvAddr = asString(body.get("recv_addr"));
        if (isBlank(sender) || isBlank(recvName) || isBlank(recvAddr)) {
            return fail("寄件人、收件人、收件地址必填", HttpStatus.BAD_REQUEST);
        }
        Object weightRaw = body.get("weight");
        boolean hasWeight = weightRaw != null && !"".equals(weightRaw);
        Double weight = asDouble(weightRaw);
        // 原版口径：Number(weight) 为 NaN 时两个比较都为假、不拦（引擎内按 0 处理），故仅数值型才查范围
        if (hasWeight && weight != null && (weight <= 0 || weight > 1000)) {
            return fail("预估重量须在 0.01~1000kg", HttpStatus.BAD_REQUEST);
        }
        String slot = asString(body.get("slot"));
        if (isBlank(slot)) return fail("请选择上门取件时段", HttpStatus.BAD_REQUEST);

        OrderData.AreaResult area = OrderData.checkArea(recvAddr);
        if (!area.supported()) return fail(area.note(), HttpStatus.BAD_REQUEST);

        OrderData.CustomerInfo cust = OrderData.customer(customerId);
        Map<String, Object> rateReq = rateRequest(body, recvAddr, cust == null ? null : cust.monthly());
        if (!hasWeight) rateReq.put("weight", 1); // 原版：weight ?? 1
        Map<String, Object> est = quoteViaRate(rateReq);
        if (est == null) return degraded();
        est.remove("promise_hours");

        Map<String, Object> order;
        synchronized (lock) {
            String orderNo = nextOrderNo();
            long id = idSeq.incrementAndGet();
            order = new LinkedHashMap<>();
            order.put("id", id);
            order.put("order_no", orderNo);
            order.put("customer_id", customerId);
            order.put("sender", sender);
            order.put("recv_name", recvName);
            order.put("recv_phone", asString(body.get("recv_phone")) == null ? "" : asString(body.get("recv_phone")));
            order.put("recv_addr", recvAddr);
            order.put("category", asString(body.get("category")) == null ? "" : asString(body.get("category")));
            order.put("weight", hasWeight ? PricingNum.of(weight) : null);
            order.put("slot", slot);
            order.put("status", "待派单");
            order.put("created_at", LocalDateTime.now().format(FMT));
            order.put("est_fee", est.get("total"));
            order.put("area_note", area.note());
            order.put("cancel_reason", null);
            order.put("cancelled_at", null);
            orders.add(order);
        }

        // 轨迹「下单受理」+ 通知记录（原版在下单事务内写 event；微服务版旁路 best-effort）
        writeEvent("下单受理",
                recvAddr + "，预估运费 ¥" + jsFmt(est.get("total")) + "（" + est.get("zone_label") + "），取件时段 " + slot,
                String.valueOf(order.get("order_no")));
        writeNotify("order_created", String.valueOf(order.get("order_no")),
                "新订单 " + order.get("order_no") + " 已受理，等待派单",
                customerId == null ? null : "customer:" + customerId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", order.get("id"));
        data.put("order_no", order.get("order_no"));
        data.put("est_fee", est.get("total"));
        data.put("est_detail", est.get("detail"));
        data.put("zone", est.get("zone"));
        data.put("zone_label", est.get("zone_label"));
        data.put("items", est.get("items"));
        data.put("area_note", area.note());
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    /* ---------------- ① 备选流：客户取消（批次 6 补迁） ---------------- */

    @PostMapping("/orders/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String id,
                                    @RequestBody(required = false) Map<String, Object> b,
                                    HttpServletRequest http) {
        Identity idn = identity(http);
        if (idn == null) return fail("未登录或登录已过期", HttpStatus.UNAUTHORIZED);
        // 对应原版 allow('dispatcher','customer')：admin 恒通过，其余角色 403（文案同原版）
        if (!"admin".equals(idn.role()) && !"dispatcher".equals(idn.role()) && !"customer".equals(idn.role())) {
            return fail("当前角色（" + idn.role() + "）无此操作权限", HttpStatus.FORBIDDEN);
        }
        Long oid = asLong(id);
        Map<String, Object> order = null;
        synchronized (lock) {
            if (oid != null) {
                for (Map<String, Object> o : orders) {
                    if (oid.equals(o.get("id"))) { order = o; break; }
                }
            }
        }
        if (order == null) return fail("订单不存在", HttpStatus.NOT_FOUND);
        if ("customer".equals(idn.role())) {
            Long oc = order.get("customer_id") instanceof Number n ? n.longValue() : null;
            if (idn.customerId() == null || !idn.customerId().equals(oc)) {
                return fail("只能取消自己的订单", HttpStatus.FORBIDDEN);
            }
        }
        String status = String.valueOf(order.get("status"));
        if ("已取消".equals(status)) return fail("该订单已取消", HttpStatus.BAD_REQUEST);
        if (!"待派单".equals(status) && !"待揽收".equals(status)) {
            return fail("订单已" + status + "，不能取消", HttpStatus.BAD_REQUEST);
        }
        String reason = b == null ? null : asString(b.get("reason"));
        if (reason == null || reason.isEmpty()) reason = "客户主动取消";

        // 先联动 task 回滚待接单的揽收任务（原版同一事务）：task 不可用则整单不取消（降级）
        try {
            Map<String, Object> rb = new LinkedHashMap<>();
            rb.put("order_id", order.get("id"));
            rb.put("reason", "订单取消");
            taskClient.rollbackByOrder(rb);
        } catch (Exception e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("error", "任务服务暂不可用（降级）");
            m.put("degraded", true);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(m);
        }

        synchronized (lock) {
            order.put("status", "已取消");
            order.put("cancel_reason", reason);
            order.put("cancelled_at", LocalDateTime.now().format(FMT));
        }
        writeEvent("订单取消", reason, String.valueOf(order.get("order_no")));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("order_no", order.get("order_no"));
        data.put("status", "已取消");
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    /** 对应 helpers.js nextNo('SO','orders','order_no')：SO + yymmdd + 5 位流水（按当日已有单量续编）。 */
    private String nextOrderNo() {
        String base = "SO" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMdd"));
        long count = orders.stream()
                .filter(o -> String.valueOf(o.get("order_no")).startsWith(base)).count();
        long n = count + 1;
        String no;
        while (true) {
            no = base + String.format("%05d", n);
            String candidate = no;
            if (orders.stream().noneMatch(o -> candidate.equals(o.get("order_no")))) return no;
            n++;
        }
    }

    /* ---------------- 订单列表 ---------------- */

    @GetMapping("/orders")
    public ResponseEntity<?> list(HttpServletRequest http) {
        Identity idn = identity(http);
        if (idn == null) return fail("未登录或登录已过期", HttpStatus.UNAUTHORIZED);
        boolean own = "customer".equals(idn.role());
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (lock) {
            List<Map<String, Object>> copy = new ArrayList<>(orders);
            copy.sort((a, b2) -> Long.compare((Long) b2.get("id"), (Long) a.get("id")));
            for (Map<String, Object> o : copy) {
                Long cid = (Long) o.get("customer_id");
                if (own && (idn.customerId() == null || !idn.customerId().equals(cid))) continue;
                Map<String, Object> row = new LinkedHashMap<>(o);
                OrderData.CustomerInfo c = OrderData.customer(cid);
                row.put("customer_name", c == null ? null : c.name());
                row.put("monthly", c == null ? null : c.monthly());
                out.add(row);
            }
        }
        return ResponseEntity.ok(Map.of("ok", true, "data", out));
    }

    /** 对应原版 order.js GET /service-areas：{...checkArea(addr), areas:[服务范围全表]}，登录即可。 */
    @GetMapping("/service-areas")
    public ResponseEntity<?> serviceAreas(HttpServletRequest http,
            @org.springframework.web.bind.annotation.RequestParam(value = "addr", required = false) String addr) {
        if (identity(http) == null) return fail("未登录或登录已过期", HttpStatus.UNAUTHORIZED);
        OrderData.AreaResult r = OrderData.checkArea(addr);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("supported", r.supported());
        data.put("checked", r.checked());
        data.put("note", r.note());
        data.put("areas", OrderData.areas());
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    /* ---------------- 服务间内部接口（批次 4：task/dispatch 读订单、回写订单状态、运力统计） ----------------
     * 原版单体内 dispatch.js 直接 SQL 读写 orders 表；微服务版 orders 归 order-service 独占，
     * 故以内部接口等价开放：按 id 取整行、回写 status（原版为直接 UPDATE，无订单状态机校验）、待派单计数。
     * 内部接口不挂用户角色限制（网关层仍要求登录态），与 billing /internal/** 口径一致。 */

    @GetMapping("/internal/orders/{id}")
    public ResponseEntity<?> internalGet(@PathVariable long id) {
        synchronized (lock) {
            for (Map<String, Object> o : orders) {
                if ((Long) o.get("id") == id) {
                    return ResponseEntity.ok(Map.of("ok", true, "data", new LinkedHashMap<>(o)));
                }
            }
        }
        return fail("订单不存在", HttpStatus.NOT_FOUND);
    }

    @PostMapping("/internal/orders/{id}/status")
    public ResponseEntity<?> internalStatus(@PathVariable long id,
                                            @RequestBody(required = false) Map<String, Object> b) {
        String status = b == null ? null : asString(b.get("status"));
        if (status == null || status.isBlank()) return fail("status 必填", HttpStatus.BAD_REQUEST);
        synchronized (lock) {
            for (Map<String, Object> o : orders) {
                if ((Long) o.get("id") == id) {
                    o.put("status", status);
                    return ResponseEntity.ok(Map.of("ok", true, "data", new LinkedHashMap<>(o)));
                }
            }
        }
        return fail("订单不存在", HttpStatus.NOT_FOUND);
    }

    /** 按订单号取整行（批次 6：track-service 以订单号查轨迹时反查订单）。 */
    @GetMapping("/internal/orders/by-no/{orderNo}")
    public ResponseEntity<?> internalGetByNo(@PathVariable String orderNo) {
        synchronized (lock) {
            for (Map<String, Object> o : orders) {
                if (orderNo.equals(o.get("order_no"))) {
                    return ResponseEntity.ok(Map.of("ok", true, "data", new LinkedHashMap<>(o)));
                }
            }
        }
        return fail("订单不存在", HttpStatus.NOT_FOUND);
    }

    @GetMapping("/internal/orders/stats")
    public ResponseEntity<?> internalStats() {
        synchronized (lock) {
            long pending = orders.stream().filter(o -> "待派单".equals(o.get("status"))).count();
            // 批次 6 工作台口径：待派单 + 待揽收（原版 /board 的 pendingOrders）
            long pendingOrders = orders.stream()
                    .filter(o -> "待派单".equals(o.get("status")) || "待揽收".equals(o.get("status"))).count();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("pending", pending);
            data.put("pending_orders", pendingOrders);
            return ResponseEntity.ok(Map.of("ok", true, "data", data));
        }
    }

    /* ---------------- 入参小工具 ---------------- */

    private static Double asDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
        }
        return null;
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

    private static boolean isBlank(String s) {
        return s == null || s.isEmpty();
    }

    /** JSON 数值形态与 JS 一致：整数不带小数点。 */
    private static final class PricingNum {
        static Object of(Double v) {
            if (v == null) return null;
            if (v == Math.rint(v) && Math.abs(v) < 1e15) return (long) v.doubleValue();
            return v;
        }
    }
}
