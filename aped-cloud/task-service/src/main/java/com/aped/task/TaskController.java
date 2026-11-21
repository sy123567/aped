package com.aped.task;

import feign.FeignException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务服务（对应 services/dispatch.js 的「任务部分」+ core/routing.js）：
 * 员工主数据、揽收任务（分配编排/接单/回滚/揽收登记）、分拣中转（登记/路由建议）、
 * 派件任务（分配/签收/异常转件）。跨服务协作（计划调用矩阵）：
 * - 定人 → dispatch-service 分配引擎（task → dispatch）；负载数据源在本服务（pickup_tasks/employees）；
 * - 订单读/状态回写 → order-service 内部接口；运单生成/读/状态推进 → billing-service 内部接口
 *   （运单状态机 WB_FLOW 在 billing 侧校验，错误文案与原版 setWaybillStatus 一致并原样透传）；
 * - 异常登记 → exception-service 内部接口（批次 4 提前量）。
 * 轨迹 events 归 track-service：批次 4/5 未写，本批次（6）收口——分配/回滚/揽收/中转/
 * 派件/签收/派送异常七个写点经 Feign 写 track（计划矩阵「最终一致」，best-effort 不阻断主流程）；
 * 签收另写 notify 通知记录。
 * 角色门在网关（揽派前缀 dispatcher/courier，admin 恒过）；原版 dispatch.js 对 courier
 * 无任务归属过滤（accept/sign 不校验任务归属），本服务照原版不自创收紧，仅校验登录态存在。
 */
@RestController
public class TaskController {

    private final TaskStore store;
    private final DispatchClient dispatchClient;
    private final OrderClient orderClient;
    private final BillingClient billingClient;
    private final ExceptionClient exceptionClient;
    private final TrackClient trackClient;
    private final NotifyClient notifyClient;

    public TaskController(TaskStore store, DispatchClient dispatchClient, OrderClient orderClient,
                          BillingClient billingClient, ExceptionClient exceptionClient,
                          TrackClient trackClient, NotifyClient notifyClient) {
        this.store = store;
        this.dispatchClient = dispatchClient;
        this.orderClient = orderClient;
        this.billingClient = billingClient;
        this.exceptionClient = exceptionClient;
        this.trackClient = trackClient;
        this.notifyClient = notifyClient;
    }

    /* ---------------- 轨迹/通知旁路写入（best-effort，失败不阻断主流程） ---------------- */

    /** 对应原版 helpers.event(waybill_no, node, desc, order_no)。 */
    private void writeEvent(String waybillNo, String node, String desc, String orderNo) {
        try {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("waybill_no", waybillNo == null ? "" : waybillNo);
            e.put("order_no", orderNo);
            e.put("node", node);
            e.put("desc_text", desc);
            trackClient.addEvent(e);
        } catch (Exception ignored) {
            // 轨迹为旁路日志（计划矩阵：最终一致），不阻断揽派主流程
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

    /** JS 模板字符串里的数字形态：整数不带小数，其余最短表示。 */
    private static String jsFmt(Object o) {
        if (o instanceof Number n) {
            double v = n.doubleValue();
            if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
            return Double.toString(v);
        }
        return o == null ? "" : String.valueOf(o);
    }

    /* ---------------- 基础响应/身份/下游异常 ---------------- */

    private static ResponseEntity<Map<String, Object>> fail(String msg, HttpStatus status) {
        return ResponseEntity.status(status).body(Map.of("ok", false, "error", msg));
    }

    private static ResponseEntity<Map<String, Object>> degraded(String service) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", service + "暂不可用（降级）");
        m.put("degraded", true);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(m);
    }

    private static boolean loggedIn(HttpServletRequest req) {
        String role = req.getHeader("X-User-Role");
        return role != null && !role.isBlank();
    }

    /** 下游服务不可用（非业务错误）：由各端点转 503 降级响应。 */
    private static final class Down extends RuntimeException {
        final String service;
        Down(String service) { super(service); this.service = service; }
    }

    /** 下游业务错误（如运单状态机拒绝）：状态码 + 原版文案原样透传。 */
    private static final class BizDown extends RuntimeException {
        final HttpStatus status;
        BizDown(HttpStatus status, String msg) { super(msg); this.status = status; }
    }

    /* ---------------- 下游调用小工具 ---------------- */

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataMap(Map<String, Object> resp) {
        if (resp != null && Boolean.TRUE.equals(resp.get("ok")) && resp.get("data") instanceof Map<?, ?> d) {
            return (Map<String, Object>) d;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> dataList(Map<String, Object> resp) {
        if (resp != null && Boolean.TRUE.equals(resp.get("ok")) && resp.get("data") instanceof List<?> l) {
            return (List<Map<String, Object>>) l;
        }
        return null;
    }

    /** 取订单整行：不存在返回 null；服务异常抛 Down。 */
    private Map<String, Object> fetchOrder(long id) {
        Map<String, Object> resp;
        try {
            resp = orderClient.get(id);
        } catch (FeignException e) {
            if (e.status() == 404) return null;
            throw new Down("订单服务");
        } catch (Exception e) {
            throw new Down("订单服务");
        }
        Map<String, Object> data = dataMap(resp);
        if (data == null) throw new Down("订单服务");
        return data;
    }

    private void setOrderStatus(long id, String status) {
        try {
            orderClient.setStatus(id, Map.of("status", status));
        } catch (Exception e) {
            throw new Down("订单服务");
        }
    }

    /** 取运单行（billing 内部口径，含订单拼装字段）：不存在返回 null；服务异常抛 Down。 */
    private Map<String, Object> fetchWaybill(String no) {
        Map<String, Object> resp;
        try {
            resp = billingClient.getWaybill(no);
        } catch (FeignException e) {
            if (e.status() == 404) return null;
            throw new Down("计费服务");
        } catch (Exception e) {
            throw new Down("计费服务");
        }
        Map<String, Object> data = dataMap(resp);
        if (data == null) throw new Down("计费服务");
        return data;
    }

    /** 按运单状态机推进状态：业务拒绝抛 BizDown（文案透传），服务异常抛 Down。 */
    private void setWaybillStatus(String no, Map<String, Object> body) {
        Map<String, Object> resp;
        try {
            resp = billingClient.setWaybillStatus(no, body);
        } catch (FeignException e) {
            String msg = feignError(e);
            if (e.status() == 400 && msg != null) throw new BizDown(HttpStatus.BAD_REQUEST, msg);
            if (e.status() == 404) throw new BizDown(HttpStatus.NOT_FOUND, msg == null ? "运单不存在" : msg);
            throw new Down("计费服务");
        } catch (Exception e) {
            throw new Down("计费服务");
        }
        if (resp == null || !Boolean.TRUE.equals(resp.get("ok"))) throw new Down("计费服务");
    }

    /** 定人：返回候选第一名（含 reason/same_area/load_before），无可用运力返回 null。 */
    private Map<String, Object> pickCourier(String addr) {
        Map<String, Object> resp;
        try {
            resp = dispatchClient.pick(addr == null ? "" : addr);
        } catch (Exception e) {
            throw new Down("派单服务");
        }
        if (resp == null || !Boolean.TRUE.equals(resp.get("ok"))) throw new Down("派单服务");
        Object d = resp.get("data");
        if (d == null) return null;
        if (d instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mm = (Map<String, Object>) m;
            return mm;
        }
        throw new Down("派单服务");
    }

    /** 候选排名（派件候选页）：字段与原版 candidatesForAddr 行一致。 */
    private List<Map<String, Object>> candidates(String addr) {
        Map<String, Object> resp;
        try {
            resp = dispatchClient.candidates(addr == null ? "" : addr, 10);
        } catch (Exception e) {
            throw new Down("派单服务");
        }
        List<Map<String, Object>> list = dataList(resp);
        if (list == null) throw new Down("派单服务");
        return list;
    }

    /** 从 Feign 异常体里取下游的 error 文案（下游响应为 {ok:false,error} 时）。 */
    private static String feignError(FeignException e) {
        try {
            String body = e.contentUTF8();
            if (body == null) return null;
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            return node.hasNonNull("error") ? node.get("error").asText() : null;
        } catch (Exception ex) {
            return null;
        }
    }

    /* ---------------- JS 语义小工具 ---------------- */

    /** JS Number()：Number/字符串数字 → 数值，其余（null/布尔外）→ NaN；空串 → 0（同 JS）。 */
    private static double jsNumber(Object o) {
        if (o == null) return Double.NaN;
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof Boolean b) return b ? 1 : 0;
        if (o instanceof String s) {
            String t = s.trim();
            if (t.isEmpty()) return 0;
            try { return Double.parseDouble(t); } catch (NumberFormatException e) { return Double.NaN; }
        }
        return Double.NaN;
    }

    /** JS 真值判定（!!v）。 */
    private static boolean truthy(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        if (o instanceof Number n) return n.doubleValue() != 0 && !Double.isNaN(n.doubleValue());
        if (o instanceof String s) return !s.isEmpty();
        return true;
    }

    private static Long asLongId(Object o) {
        double d = jsNumber(o);
        if (Double.isNaN(d)) return null;
        return (long) d;
    }

    private static String asString(Object o) {
        return o instanceof String s ? s : null;
    }

    /* ================= 员工主数据 ================= */

    @GetMapping("/employees")
    public ResponseEntity<?> employees(@RequestParam(value = "role", required = false) String role,
                                       HttpServletRequest req) {
        if (!loggedIn(req)) return fail("未登录或登录已过期", HttpStatus.UNAUTHORIZED);
        List<Map<String, Object>> out = new ArrayList<>();
        for (TaskStore.Employee e : TaskStore.EMPLOYEES) {
            if (role != null && !role.isEmpty() && !e.role().equals(role)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("code", e.code());
            m.put("name", e.name());
            m.put("role", e.role());
            m.put("grid_code", e.gridCode());
            out.add(m);
        }
        return ResponseEntity.ok(Map.of("ok", true, "data", out));
    }

    /* ================= 内部接口：负载行与任务统计（供 dispatch-service） ================= */

    @GetMapping("/internal/courier-loads")
    public ResponseEntity<?> courierLoads() {
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (store.lock) {
            for (TaskStore.Employee e : TaskStore.EMPLOYEES) {
                if (!"courier".equals(e.role())) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", e.id());
                m.put("code", e.code());
                m.put("name", e.name());
                m.put("grid_code", e.gridCode());
                m.put("load", store.courierLoad(e.id()));
                m.put("capacity", 20);
                out.add(m);
            }
        }
        return ResponseEntity.ok(Map.of("ok", true, "data", out));
    }

    @GetMapping("/internal/task-stats")
    public ResponseEntity<?> taskStats() {
        String today = LocalDate.now().toString(); // 对应 date(assigned_at)=date('now','localtime')
        long n = 0;
        synchronized (store.lock) {
            for (TaskStore.PickupTask t : store.pickupTasks) {
                if (t.assignedAt != null && t.assignedAt.length() >= 10
                        && t.assignedAt.substring(0, 10).equals(today)) {
                    n++;
                }
            }
        }
        return ResponseEntity.ok(Map.of("ok", true, "data", Map.of("today_assigned", n)));
    }

    /* ================= ③ 揽收任务 ================= */

    @GetMapping("/pickup/tasks")
    public ResponseEntity<?> pickupTasks(@RequestParam(value = "status", required = false) String status,
                                         HttpServletRequest req) {
        if (!loggedIn(req)) return fail("未登录或登录已过期", HttpStatus.UNAUTHORIZED);
        List<TaskStore.PickupTask> rows = new ArrayList<>();
        synchronized (store.lock) {
            for (TaskStore.PickupTask t : store.pickupTasks) {
                if (status != null && !status.isEmpty() && !status.equals(t.status)) continue;
                rows.add(t);
            }
        }
        rows.sort((a, b) -> Long.compare(b.id, a.id)); // ORDER BY t.id DESC
        Map<Long, Map<String, Object>> orderCache = new HashMap<>();
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (TaskStore.PickupTask t : rows) {
                Map<String, Object> o = orderCache.computeIfAbsent(t.orderId, this::fetchOrder);
                if (o == null) continue; // 原版 INNER JOIN orders：订单不存在则行不出现
                TaskStore.Employee e = TaskStore.employeeById(t.courierId);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", t.id);
                m.put("order_no", o.get("order_no"));
                m.put("status", t.status);
                m.put("assigned_at", t.assignedAt);
                m.put("accepted_at", t.acceptedAt);
                m.put("rollback_reason", t.rollbackReason);
                m.put("courier", e == null ? null : e.name());
                m.put("grid_code", e == null ? null : e.gridCode());
                m.put("sender", o.get("sender"));
                m.put("recv_addr", o.get("recv_addr"));
                m.put("slot", o.get("slot"));
                m.put("weight", o.get("weight"));
                m.put("category", o.get("category"));
                out.add(m);
            }
        } catch (Down d) {
            return degraded(d.service);
        }
        return ResponseEntity.ok(Map.of("ok", true, "data", out));
    }

    /**
     * 揽收任务分配（对应 assignOrders + 路由编排）：逐单定人（dispatch 引擎），
     * 定完一单立刻落任务并回写订单状态，下一单查到的负载已 +1——与原版
     * 「派完立刻落库，下一单重新查」的语义一致（负载由本服务任务表实时算出）。
     */
    @PostMapping("/pickup/assign")
    public ResponseEntity<?> pickupAssign(@RequestBody(required = false) Map<String, Object> b,
                                          HttpServletRequest req) {
        if (!loggedIn(req)) return fail("未登录或登录已过期", HttpStatus.UNAUTHORIZED);
        Map<String, Object> body = b == null ? Map.of() : b;
        List<Double> ids = new ArrayList<>();
        if (body.get("order_ids") instanceof List<?> raw) {
            for (Object x : raw) {
                double d = jsNumber(x);
                if (d != 0 && !Double.isNaN(d)) ids.add(d); // .map(Number).filter(Boolean)
            }
        }
        if (ids.isEmpty()) return fail("order_ids 必填", HttpStatus.BAD_REQUEST);

        List<Map<String, Object>> assignments = new ArrayList<>();
        List<Map<String, Object>> unassigned = new ArrayList<>();
        try {
            for (double rawId : ids) {
                Object idEcho = rawId == Math.rint(rawId) ? (Object) (long) rawId : (Object) rawId;
                Map<String, Object> o = rawId == Math.rint(rawId) ? fetchOrder((long) rawId) : null;
                if (o == null) {
                    Map<String, Object> u = new LinkedHashMap<>();
                    u.put("order_id", idEcho);
                    u.put("why", "订单不存在");
                    unassigned.add(u);
                    continue;
                }
                long oid = ((Number) o.get("id")).longValue();
                if (!"待派单".equals(o.get("status"))) {
                    Map<String, Object> u = new LinkedHashMap<>();
                    u.put("order_id", idEcho);
                    u.put("order_no", o.get("order_no"));
                    u.put("why", "状态为「" + o.get("status") + "」，不在待派单池");
                    unassigned.add(u);
                    continue;
                }
                Map<String, Object> c = pickCourier(asString(o.get("recv_addr")));
                if (c == null) {
                    Map<String, Object> u = new LinkedHashMap<>();
                    u.put("order_id", idEcho);
                    u.put("order_no", o.get("order_no"));
                    u.put("why", "无可用运力（全部满载）");
                    unassigned.add(u);
                    continue;
                }
                long courierId = ((Number) c.get("id")).longValue();
                synchronized (store.lock) {
                    TaskStore.PickupTask t = new TaskStore.PickupTask();
                    t.id = store.nextPickupId();
                    t.orderId = oid;
                    t.courierId = courierId;
                    t.status = "待接单";
                    t.assignedAt = TaskStore.now();
                    store.pickupTasks.add(t);
                }
                setOrderStatus(oid, "待揽收");
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("order_id", oid);
                a.put("order_no", o.get("order_no"));
                a.put("recv_addr", o.get("recv_addr"));
                a.put("slot", o.get("slot"));
                a.put("courier_id", courierId);
                a.put("courier", c.get("name"));
                a.put("courier_code", c.get("code"));
                a.put("grid_code", c.get("grid_code"));
                a.put("same_area", truthy(c.get("same_area")));
                a.put("load_before", c.get("load"));
                a.put("capacity", c.get("capacity"));
                a.put("reason", c.get("reason"));
                assignments.add(a);
                writeEvent("", "揽收任务已分配",
                        "自动分配 → " + c.get("name") + "｜" + c.get("reason"),
                        String.valueOf(o.get("order_no")));
            }
        } catch (Down d) {
            return degraded(d.service);
        }
        if (assignments.isEmpty()) {
            Object why = unassigned.isEmpty() ? "没有可分配的订单" : unassigned.get(0).get("why");
            return fail(String.valueOf(why), HttpStatus.BAD_REQUEST);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("assigned", assignments.size());
        data.put("assignments", assignments);
        data.put("unassigned", unassigned);
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    @PostMapping("/pickup/accept")
    public ResponseEntity<?> pickupAccept(@RequestBody(required = false) Map<String, Object> b,
                                          HttpServletRequest req) {
        if (!loggedIn(req)) return fail("未登录或登录已过期", HttpStatus.UNAUTHORIZED);
        Long id = b == null ? null : asLongId(b.get("id"));
        synchronized (store.lock) {
            TaskStore.PickupTask t = id == null ? null : store.pickupById(id);
            if (t == null) return fail("任务不存在", HttpStatus.NOT_FOUND);
            if (!"待接单".equals(t.status)) return fail("任务状态为「" + t.status + "」，不能接单", HttpStatus.BAD_REQUEST);
            t.status = "已接单";
            t.acceptedAt = TaskStore.now();
        }
        return ResponseEntity.ok(Map.of("ok", true, "data", Map.of()));
    }

    @PostMapping("/pickup/rollback")
    public ResponseEntity<?> pickupRollback(@RequestBody(required = false) Map<String, Object> b,
                                            HttpServletRequest req) {
        if (!loggedIn(req)) return fail("未登录或登录已过期", HttpStatus.UNAUTHORIZED);
        Map<String, Object> body = b == null ? Map.of() : b;
        Long id = asLongId(body.get("id"));
        TaskStore.PickupTask t;
        synchronized (store.lock) {
            t = id == null ? null : store.pickupById(id);
        }
        if (t == null) return fail("任务不存在", HttpStatus.NOT_FOUND);
        Map<String, Object> o;
        try {
            o = fetchOrder(t.orderId);
        } catch (Down d) {
            return degraded(d.service);
        }
        if (o == null) return fail("任务不存在", HttpStatus.NOT_FOUND); // 原版 JOIN 无行同 404
        if (!"待接单".equals(t.status)) return fail("任务状态为「" + t.status + "」，不能回滚", HttpStatus.BAD_REQUEST);
        String reason = asString(body.get("reason"));
        if (reason == null) reason = "超时未接单";
        synchronized (store.lock) {
            t.status = "已回滚";
            t.rollbackReason = reason;
        }
        try {
            setOrderStatus(t.orderId, "待派单");
        } catch (Down d) {
            return degraded(d.service);
        }
        writeEvent("", "揽收任务已回滚", reason, String.valueOf(o.get("order_no")));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("order_no", o.get("order_no"));
        data.put("status", "已回滚");
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    /* ---------- ③+② 上门揽收登记（称重 → 生成运单 → 计费） ---------- */

    @PostMapping("/pickup")
    public ResponseEntity<?> pickup(@RequestBody(required = false) Map<String, Object> b,
                                    HttpServletRequest req) {
        if (!loggedIn(req)) return fail("未登录或登录已过期", HttpStatus.UNAUTHORIZED);
        Map<String, Object> body = b == null ? Map.of() : b;
        Long taskId = asLongId(body.get("task_id"));
        TaskStore.PickupTask task;
        synchronized (store.lock) {
            task = taskId == null ? null : store.pickupById(taskId);
        }
        if (task == null) return fail("揽收任务不存在", HttpStatus.NOT_FOUND);
        if ("已揽收".equals(task.status)) return fail("该任务已揽收", HttpStatus.BAD_REQUEST);
        if (!"已接单".equals(task.status) && !"待接单".equals(task.status)) {
            return fail("任务状态为「" + task.status + "」，不能揽收", HttpStatus.BAD_REQUEST);
        }
        double weight = jsNumber(body.get("weight"));
        if (!(weight > 0) || weight > 1000) return fail("重量须在 0.01~1000kg", HttpStatus.BAD_REQUEST);

        Map<String, Object> order;
        try {
            order = fetchOrder(task.orderId);
        } catch (Down d) {
            return degraded(d.service);
        }
        if (order == null) return fail("揽收任务不存在", HttpStatus.NOT_FOUND);

        Map<String, Object> wbReq = new LinkedHashMap<>();
        wbReq.put("orderId", task.orderId);
        wbReq.put("customerId", order.get("customer_id"));
        wbReq.put("destAddr", order.get("recv_addr"));
        wbReq.put("orderNo", order.get("order_no"));
        wbReq.put("sender", order.get("sender"));
        wbReq.put("recvName", order.get("recv_name"));
        String product = asString(body.get("product"));
        wbReq.put("product", product == null ? "隔日达" : product); // b.product ?? '隔日达'
        wbReq.put("weight", weight);
        wbReq.put("volumeL", numOr0(body.get("volumeL")));
        wbReq.put("insured", numOr0(body.get("insured")));
        wbReq.put("length", numOr0(body.get("length")));
        wbReq.put("width", numOr0(body.get("width")));
        wbReq.put("height", numOr0(body.get("height")));
        wbReq.put("pack", truthy(body.get("pack")));

        Map<String, Object> wb;
        try {
            Map<String, Object> resp = billingClient.createWaybill(wbReq);
            wb = dataMap(resp);
            if (wb == null) return degraded("计费服务");
        } catch (FeignException e) {
            // billing 侧 rate 降级时返回 503 degraded 体，原样透传
            if (e.status() == 503) {
                String err = feignError(e);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ok", false);
                m.put("error", err == null ? "计费服务暂不可用，暂时无法生成运单（降级）" : err);
                m.put("degraded", true);
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(m);
            }
            return degraded("计费服务");
        } catch (Exception e) {
            return degraded("计费服务");
        }

        synchronized (store.lock) {
            task.status = "已揽收";
        }
        try {
            setOrderStatus(task.orderId, "已揽收");
        } catch (Down d) {
            return degraded(d.service);
        }
        writeEvent(String.valueOf(wb.get("waybill_no")), "揽收点",
                "快件已揽收，运费 ¥" + jsFmt(wb.get("fee")) + "（" + wb.get("detail") + "），承诺送达 " + wb.get("promise_time"),
                String.valueOf(order.get("order_no")));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("waybill_no", wb.get("waybill_no"));
        data.put("fee", wb.get("fee"));
        data.put("detail", wb.get("detail"));
        data.put("promise_time", wb.get("promise_time"));
        data.put("chargeKg", wb.get("chargeKg"));
        data.put("volWeight", wb.get("volWeight"));
        data.put("items", wb.get("items"));
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    /** Number(v) || 0：NaN/0 → 0（揽收登记的体积/保价/尺寸入参口径）。 */
    private static double numOr0(Object o) {
        double d = jsNumber(o);
        return (Double.isNaN(d) || d == 0) ? 0 : d;
    }

    /* ================= ④ 分拣中转登记 ================= */

    @GetMapping("/transit")
    public ResponseEntity<?> transitList(@RequestParam(value = "waybill_no", required = false) String waybillNo,
                                         HttpServletRequest req) {
        if (!loggedIn(req)) return fail("未登录或登录已过期", HttpStatus.UNAUTHORIZED);
        List<TaskStore.TransitLog> rows = new ArrayList<>();
        synchronized (store.lock) {
            for (TaskStore.TransitLog t : store.transitLogs) {
                if (waybillNo != null && !waybillNo.isEmpty() && !waybillNo.equals(t.waybillNo)) continue;
                rows.add(t);
            }
        }
        rows.sort((a, b) -> Long.compare(b.id, a.id)); // ORDER BY t.id DESC
        Map<String, Map<String, Object>> wbCache = new HashMap<>();
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (TaskStore.TransitLog t : rows) {
                Map<String, Object> w = wbCache.computeIfAbsent(t.waybillNo, this::fetchWaybill);
                TaskStore.Employee e = TaskStore.employeeById(t.operatorId);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", t.id);
                m.put("waybill_no", t.waybillNo);
                m.put("node", t.node);
                m.put("next_hop", t.nextHop);
                m.put("flight", t.flight);
                m.put("operator_id", t.operatorId);
                m.put("security", t.security);
                m.put("created_at", t.createdAt);
                m.put("operator", e == null ? null : e.name());
                m.put("waybill_status", w == null ? null : w.get("status"));
                m.put("product", w == null ? null : w.get("product"));
                out.add(m);
            }
        } catch (Down d) {
            return degraded(d.service);
        }
        return ResponseEntity.ok(Map.of("ok", true, "data", out));
    }

    @GetMapping("/transit/suggest")
    public ResponseEntity<?> transitSuggest(@RequestParam(value = "waybill_no", required = false) String waybillNo,
                                            HttpServletRequest req) {
        if (!loggedIn(req)) return fail("未登录或登录已过期", HttpStatus.UNAUTHORIZED);
        if (waybillNo == null || waybillNo.isEmpty()) return fail("waybill_no 必填", HttpStatus.BAD_REQUEST);
        Map<String, Object> w;
        try {
            w = fetchWaybill(waybillNo);
        } catch (Down d) {
            return degraded(d.service);
        }
        if (w == null) return fail("运单不存在", HttpStatus.NOT_FOUND);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("waybill_no", w.get("waybill_no"));
        data.put("product", w.get("product"));
        data.put("dest_addr", w.get("recv_addr"));
        data.putAll(RoutingEngine.planRoute(asString(w.get("recv_addr")), asString(w.get("product"))));
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    @PostMapping("/transit")
    public ResponseEntity<?> transitCreate(@RequestBody(required = false) Map<String, Object> b,
                                           HttpServletRequest req) {
        if (!loggedIn(req)) return fail("未登录或登录已过期", HttpStatus.UNAUTHORIZED);
        Map<String, Object> body = b == null ? Map.of() : b;
        String waybillNo = asString(body.get("waybill_no"));
        String node = asString(body.get("node"));
        if (waybillNo == null || waybillNo.isEmpty() || node == null || node.isEmpty()) {
            return fail("运单号与当前节点必填", HttpStatus.BAD_REQUEST);
        }
        Map<String, Object> w;
        try {
            w = fetchWaybill(waybillNo);
        } catch (Down d) {
            return degraded(d.service);
        }
        if (w == null) return fail("运单不存在", HttpStatus.NOT_FOUND);

        Object flightRaw = body.get("flight");
        String flight = asString(flightRaw);
        if (truthy(flightRaw)) {
            try {
                dispatchClient.flight(flight == null ? String.valueOf(flightRaw) : flight);
            } catch (FeignException e) {
                if (e.status() == 404) {
                    return fail("航班 " + flight + " 不在航班主数据中，请先在基础数据中维护", HttpStatus.BAD_REQUEST);
                }
                return degraded("派单服务");
            } catch (Exception e) {
                return degraded("派单服务");
            }
        }
        String wStatus = asString(w.get("status"));
        if (!"已揽收".equals(wStatus) && !"转运中".equals(wStatus)) {
            return fail("运单状态为「" + wStatus + "」，不能做分拣中转登记", HttpStatus.BAD_REQUEST);
        }

        Map<String, Object> route = RoutingEngine.planRoute(asString(w.get("recv_addr")), asString(w.get("product")));
        Object nextHopRaw = body.get("next_hop");
        String hop = truthy(nextHopRaw) ? String.valueOf(nextHopRaw) : asString(route.get("next_hop"));

        // 原版在同一事务内：先写日志再推状态，状态非法则整体回滚；
        // 微服务版先推状态（billing 状态机校验）再写本地日志，失败时本地无脏行，等价于回滚结果。
        if ("已揽收".equals(wStatus)) {
            try {
                setWaybillStatus(waybillNo, Map.of("to", "转运中"));
            } catch (BizDown bd) {
                return fail(bd.getMessage(), bd.status);
            } catch (Down d) {
                return degraded(d.service);
            }
        }
        TaskStore.TransitLog log = new TaskStore.TransitLog();
        synchronized (store.lock) {
            log.id = store.nextTransitId();
            log.waybillNo = waybillNo;
            log.node = node;
            log.nextHop = hop == null ? "" : hop;
            log.flight = flight == null ? "" : flight;
            log.operatorId = asLongId(body.get("operator_id"));
            log.security = truthy(body.get("security")) ? 1 : 0;
            log.createdAt = TaskStore.now();
            store.transitLogs.add(log);
        }
        Object eta = route.get("eta_hours");
        writeEvent(waybillNo, node,
                "分拣中转登记，下一跳：" + (hop == null || hop.isEmpty() ? "—" : hop)
                        + (flight != null && !flight.isEmpty() ? "，航班 " + flight : "")
                        + (log.security == 1 ? "，已过安检" : "")
                        + "（算法路由：" + route.get("mode_label") + "｜" + route.get("zone_label")
                        + "｜ETA " + (eta == null ? "—" : jsFmt(eta)) + "h）",
                null);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", log.id);
        data.put("next_hop", hop);
        data.put("route", route);
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    /* ================= ⑤ 派件任务 ================= */

    @GetMapping("/delivery/tasks")
    public ResponseEntity<?> deliveryTasks(HttpServletRequest req) {
        if (!loggedIn(req)) return fail("未登录或登录已过期", HttpStatus.UNAUTHORIZED);
        List<TaskStore.DeliveryTask> rows = new ArrayList<>();
        synchronized (store.lock) {
            rows.addAll(store.deliveryTasks);
        }
        rows.sort((a, b) -> Long.compare(b.id, a.id)); // ORDER BY d.id DESC
        Map<String, Map<String, Object>> wbCache = new HashMap<>();
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (TaskStore.DeliveryTask t : rows) {
                Map<String, Object> w = wbCache.computeIfAbsent(t.waybillNo, this::fetchWaybill);
                TaskStore.Employee e = TaskStore.employeeById(t.courierId);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", t.id);
                m.put("waybill_no", t.waybillNo);
                m.put("courier_id", t.courierId);
                m.put("receiver", t.receiver);
                m.put("addr", t.addr);
                m.put("status", t.status);
                m.put("signer", t.signer);
                m.put("sign_way", t.signWay);
                m.put("signed_at", t.signedAt);
                m.put("courier", e == null ? null : e.name());
                m.put("promise_time", w == null ? null : w.get("promise_time"));
                m.put("product", w == null ? null : w.get("product"));
                out.add(m);
            }
        } catch (Down d) {
            return degraded(d.service);
        }
        return ResponseEntity.ok(Map.of("ok", true, "data", out));
    }

    @GetMapping("/delivery/candidates")
    public ResponseEntity<?> deliveryCandidates(@RequestParam(value = "waybill_no", required = false) String waybillNo,
                                                HttpServletRequest req) {
        if (!loggedIn(req)) return fail("未登录或登录已过期", HttpStatus.UNAUTHORIZED);
        Map<String, Object> w;
        try {
            w = waybillNo == null ? null : fetchWaybill(waybillNo);
        } catch (Down d) {
            return degraded(d.service);
        }
        if (w == null) return fail("运单不存在", HttpStatus.NOT_FOUND);
        List<Map<String, Object>> cands;
        try {
            cands = candidates(asString(w.get("recv_addr")));
        } catch (Down d) {
            return degraded(d.service);
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < cands.size(); i++) {
            Map<String, Object> m = new LinkedHashMap<>(cands.get(i));
            m.put("rank", i + 1);
            list.add(m);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("waybill_no", w.get("waybill_no"));
        data.put("recv_addr", w.get("recv_addr"));
        data.put("promise_time", w.get("promise_time"));
        data.put("capacity", 20);
        data.put("candidates", list);
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    @PostMapping("/delivery/assign")
    public ResponseEntity<?> deliveryAssign(@RequestBody(required = false) Map<String, Object> b,
                                            HttpServletRequest req) {
        if (!loggedIn(req)) return fail("未登录或登录已过期", HttpStatus.UNAUTHORIZED);
        Map<String, Object> body = b == null ? Map.of() : b;
        List<String> nos = new ArrayList<>();
        if (body.get("waybill_nos") instanceof List<?> raw) {
            for (Object x : raw) {
                if (x instanceof String s) nos.add(s);
                else if (x instanceof Number n) nos.add(String.valueOf(n));
            }
        }
        if (nos.isEmpty()) return fail("waybill_nos 必填", HttpStatus.BAD_REQUEST);

        List<Map<String, Object>> done = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        try {
            for (String no : nos) {
                Map<String, Object> w = fetchWaybill(no);
                if (w == null || !"转运中".equals(w.get("status"))) {
                    skipped.add(no);
                    continue;
                }
                Map<String, Object> c = pickCourier(asString(w.get("recv_addr")));
                if (c == null) {
                    skipped.add(no + "（无可用运力）");
                    continue;
                }
                synchronized (store.lock) {
                    TaskStore.DeliveryTask t = new TaskStore.DeliveryTask();
                    t.id = store.nextDeliveryId();
                    t.waybillNo = no;
                    t.courierId = ((Number) c.get("id")).longValue();
                    t.receiver = asString(w.get("recv_name")) == null ? "" : asString(w.get("recv_name"));
                    t.addr = asString(w.get("recv_addr")) == null ? "" : asString(w.get("recv_addr"));
                    t.status = "待派送";
                    store.deliveryTasks.add(t);
                }
                // 转运中 → 派送中 在 WB_FLOW 内恒合法（状态刚校验过），失败按业务错误透传
                setWaybillStatus(no, Map.of("to", "派送中"));
                writeEvent(no, "目的网点",
                        "派件任务单已生成 → " + c.get("name") + "｜" + c.get("reason"),
                        asString(w.get("order_no")));
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("waybill_no", no);
                a.put("courier", c.get("name"));
                a.put("grid_code", c.get("grid_code"));
                a.put("reason", c.get("reason"));
                done.add(a);
            }
        } catch (BizDown bd) {
            return fail(bd.getMessage(), bd.status);
        } catch (Down d) {
            return degraded(d.service);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("assigned", done.size());
        data.put("assignments", done);
        data.put("skipped", skipped);
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    @PostMapping("/delivery/sign")
    public ResponseEntity<?> deliverySign(@RequestBody(required = false) Map<String, Object> b,
                                          HttpServletRequest req) {
        if (!loggedIn(req)) return fail("未登录或登录已过期", HttpStatus.UNAUTHORIZED);
        Map<String, Object> body = b == null ? Map.of() : b;
        Long id = asLongId(body.get("id"));
        TaskStore.DeliveryTask t;
        synchronized (store.lock) {
            t = id == null ? null : store.deliveryById(id);
        }
        if (t == null) return fail("派件任务不存在", HttpStatus.NOT_FOUND);
        if ("已签收".equals(t.status)) return fail("该任务已签收", HttpStatus.BAD_REQUEST);
        if (!"待派送".equals(t.status) && !"派送中".equals(t.status)) {
            return fail("任务状态为「" + t.status + "」，不能签收", HttpStatus.BAD_REQUEST);
        }
        String signer = asString(body.get("signer"));
        if (signer == null || signer.isEmpty()) return fail("请填写签收人", HttpStatus.BAD_REQUEST);
        String signWay = asString(body.get("sign_way"));
        if (signWay == null) signWay = "本人签收";

        String signedAt = TaskStore.now();
        // 原版同一事务内先改任务再推运单状态、非法则整体回滚；微服务版先经 billing 状态机
        // 校验并推进运单（派送中 → 已签收），成功后再落本地任务，结果等价于回滚口径。
        try {
            Map<String, Object> st = new LinkedHashMap<>();
            st.put("to", "已签收");
            st.put("signed_at", signedAt);
            setWaybillStatus(t.waybillNo, st);
        } catch (BizDown bd) {
            return fail(bd.getMessage(), bd.status);
        } catch (Down d) {
            return degraded(d.service);
        }
        synchronized (store.lock) {
            t.status = "已签收";
            t.signer = signer;
            t.signWay = signWay;
            t.signedAt = signedAt;
        }
        // 轨迹「收件地址」：原版在签收事务内按 promise_time 判超时标注（取运单失败则不标注）
        String late = "";
        try {
            Map<String, Object> w = fetchWaybill(t.waybillNo);
            Object pt = w == null ? null : w.get("promise_time");
            if (pt != null && signedAt.compareTo(String.valueOf(pt)) > 0) late = "（超承诺时效）";
        } catch (Down ignored) {
            // 只读拼装降级：不标注
        }
        writeEvent(t.waybillNo, "收件地址",
                "快件已签收，签收人：" + signer + "（" + signWay + "）" + late, null);
        writeNotify("waybill_signed", t.waybillNo,
                "运单 " + t.waybillNo + " 已签收，签收人：" + signer, null);
        return ResponseEntity.ok(Map.of("ok", true, "data", Map.of()));
    }

    @PostMapping("/delivery/fail")
    public ResponseEntity<?> deliveryFail(@RequestBody(required = false) Map<String, Object> b,
                                          HttpServletRequest req) {
        if (!loggedIn(req)) return fail("未登录或登录已过期", HttpStatus.UNAUTHORIZED);
        Map<String, Object> body = b == null ? Map.of() : b;
        Long id = asLongId(body.get("id"));
        TaskStore.DeliveryTask t;
        synchronized (store.lock) {
            t = id == null ? null : store.deliveryById(id);
        }
        if (t == null) return fail("派件任务不存在", HttpStatus.NOT_FOUND);
        if ("已签收".equals(t.status) || "派送异常".equals(t.status)) {
            return fail("任务状态为「" + t.status + "」，不能再登记派送异常", HttpStatus.BAD_REQUEST);
        }
        String type = asString(body.get("type"));
        if (type == null) type = "拒收";
        if (!"拒收".equals(type) && !"破损".equals(type) && !"丢失".equals(type)) {
            return fail("异常类型须为 拒收 / 破损 / 丢失（联系不上请改约派送，不要登记为异常件）", HttpStatus.BAD_REQUEST);
        }
        String rawDesc = asString(body.get("desc"));
        String desc = rawDesc == null ? "派送异常登记" : rawDesc;

        synchronized (store.lock) {
            t.status = "派送异常";
        }
        try {
            Map<String, Object> ex = new LinkedHashMap<>();
            ex.put("waybill_no", t.waybillNo);
            ex.put("type", type);
            ex.put("desc_text", desc);
            Map<String, Object> resp = exceptionClient.create(ex);
            if (resp == null || !Boolean.TRUE.equals(resp.get("ok"))) throw new Down("异常服务");
        } catch (Down d) {
            return degraded(d.service);
        } catch (Exception e) {
            return degraded("异常服务");
        }
        try {
            setWaybillStatus(t.waybillNo, Map.of("to", "异常"));
        } catch (BizDown bd) {
            return fail(bd.getMessage(), bd.status);
        } catch (Down d) {
            return degraded(d.service);
        }
        // 轨迹「派送异常」：原版只在请求带 desc 时才拼描述（与异常记录的缺省文案口径不同）
        writeEvent(t.waybillNo, "派送异常",
                "类型：" + type + (rawDesc != null && !rawDesc.isEmpty() ? "，" + rawDesc : ""), null);
        return ResponseEntity.ok(Map.of("ok", true, "data", Map.of()));
    }
}
