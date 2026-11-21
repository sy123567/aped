package com.aped.exception;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 异常件对外接口：对应原版 services/claim.js 的 /exceptions 部分，路径/返回/文案逐字一致。
 * 角色门与原版一致地只在网关层（理赔前缀 → claims，admin 恒通过），本服务不另设 allow
 * （原版 claim.js 路由内无 allow 调用）。运单读写经 billing 内部接口，理赔 JOIN 经 claim 内部接口。
 */
@RestController
public class ExceptionController {

    private static final List<String> TYPES = List.of("拒收", "破损", "丢失");
    /** 登记异常时需要把运单推入「异常」状态的原状态集合（原版 claim.js 原样）。 */
    private static final List<String> PUSH_ON_REGISTER = List.of("已揽收", "转运中", "派送中");

    private final ExceptionStore store;
    private final WaybillGateway waybills;
    private final Assessor assessor;
    private final ClaimClient claimClient;
    private final EventPublisher events;

    public ExceptionController(ExceptionStore store, WaybillGateway waybills,
                               Assessor assessor, ClaimClient claimClient, EventPublisher events) {
        this.store = store;
        this.waybills = waybills;
        this.assessor = assessor;
        this.claimClient = claimClient;
        this.events = events;
    }

    private static ResponseEntity<Map<String, Object>> fail(String msg, HttpStatus status) {
        return ResponseEntity.status(status).body(Map.of("ok", false, "error", msg));
    }

    private static ResponseEntity<Map<String, Object>> degraded() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", "运单服务暂不可用（降级）");
        m.put("degraded", true);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(m);
    }

    /* ---------------- 异常列表（LEFT JOIN 理赔单） ---------------- */

    @GetMapping("/exceptions")
    public ResponseEntity<?> list(@RequestParam(name = "waybill_no", required = false) String waybillNo) {
        // 理赔单按 exception_id 分组（原版 LEFT JOIN：无理赔单的异常也出一行，理赔字段为 null；
        // 一个异常单有多张理赔单时出多行，同原版 SQL 语义）。claim 不可用时按无理赔单降级。
        Map<Long, List<Map<String, Object>>> claimsByEx = new HashMap<>();
        try {
            List<Map<String, Object>> all = ExJson.dataList(claimClient.listAll());
            if (all != null) {
                for (Map<String, Object> c : all) {
                    Long eid = ExJson.asLong(c.get("exception_id"));
                    if (eid != null) claimsByEx.computeIfAbsent(eid, k -> new ArrayList<>()).add(c);
                }
            }
        } catch (Exception ignored) {
            // 降级为无理赔单 JOIN
        }

        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (store.lock) {
            List<ExceptionStore.ExceptionRow> rows = new ArrayList<>();
            for (ExceptionStore.ExceptionRow r : store.exceptions) {
                if (waybillNo == null || waybillNo.equals(r.waybillNo)) rows.add(r);
            }
            rows.sort((a, b) -> Long.compare(b.id, a.id)); // ORDER BY e.id DESC
            for (ExceptionStore.ExceptionRow r : rows) {
                List<Map<String, Object>> cs = claimsByEx.getOrDefault(r.id, List.of());
                if (cs.isEmpty()) {
                    out.add(joinedRow(r, null));
                } else {
                    for (Map<String, Object> c : cs) out.add(joinedRow(r, c));
                }
            }
        }
        return ResponseEntity.ok(Map.of("ok", true, "data", out));
    }

    private static Map<String, Object> joinedRow(ExceptionStore.ExceptionRow r, Map<String, Object> claim) {
        Map<String, Object> m = ExJson.exceptionJson(r);
        m.put("claim_id", claim == null ? null : claim.get("id"));
        m.put("amount", claim == null ? null : claim.get("amount"));
        m.put("claim_status", claim == null ? null : claim.get("status"));
        m.put("rule", claim == null ? null : claim.get("rule"));
        m.put("approved_by", claim == null ? null : claim.get("approved_by"));
        return m;
    }

    /* ---------------- 异常详情 ---------------- */

    @GetMapping("/exceptions/{id}")
    public ResponseEntity<?> detail(@PathVariable String id) {
        Long eid = ExJson.asLong(id);
        ExceptionStore.ExceptionRow ex = null;
        if (eid != null) {
            synchronized (store.lock) {
                ex = store.byId(eid);
            }
        }
        if (ex == null) return fail("异常单不存在", HttpStatus.NOT_FOUND);

        Map<String, Object> waybill = null;
        try {
            waybill = waybills.fetch(ex.waybillNo);
        } catch (WaybillGateway.Down ignored) {
            // 详情只读拼装：运单服务不可用时 waybill 降级为 null
        }

        List<Map<String, Object>> claims = new ArrayList<>();
        try {
            List<Map<String, Object>> cs = ExJson.dataList(claimClient.listByException(ex.id));
            if (cs != null) claims.addAll(cs); // 下游内部接口已按 id 倒序（对应原版 ORDER BY id DESC）
        } catch (Exception ignored) {
            // 降级为无理赔单
        }

        // 批次 6 起详情 events 为运单全量轨迹（对应原版 SELECT * FROM events WHERE waybill_no=?）：
        // 优先从 track-service 读（各环节事件已统一收口），track 不可用时降级读本服务本地日志。
        List<Map<String, Object>> eventRows = events.trackEventsOf(ex.waybillNo);
        if (eventRows == null) {
            eventRows = new ArrayList<>();
            synchronized (store.lock) {
                for (ExceptionStore.EventRow e : store.eventsOf(ex.waybillNo)) eventRows.add(ExJson.eventJson(e));
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("exception", ExJson.exceptionJson(ex));
        data.put("waybill", waybill);
        data.put("claims", claims);
        data.put("events", eventRows);
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    /* ---------------- 异常登记 ---------------- */

    @PostMapping("/exceptions")
    public ResponseEntity<?> create(@RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> body = b == null ? Map.of() : b;
        String waybillNo = ExJson.asString(body.get("waybill_no"));
        String type = ExJson.asString(body.get("type"));
        if (waybillNo == null || waybillNo.isEmpty() || type == null || type.isEmpty()) {
            return fail("运单号与异常类型必填", HttpStatus.BAD_REQUEST);
        }
        if (!TYPES.contains(type)) {
            return fail("异常类型须为 " + String.join(" / ", TYPES), HttpStatus.BAD_REQUEST);
        }

        Map<String, Object> w;
        try {
            w = waybills.fetch(waybillNo);
        } catch (WaybillGateway.Down e) {
            return degraded();
        }
        if (w == null) return fail("运单不存在", HttpStatus.NOT_FOUND);
        String wStatus = ExJson.asString(w.get("status"));
        if ("已关闭".equals(wStatus)) return fail("运单已关闭，不能登记异常", HttpStatus.BAD_REQUEST);

        String photos = ExJson.asString(body.get("photos"));
        String desc = ExJson.asString(body.get("desc"));
        ExceptionStore.ExceptionRow r = new ExceptionStore.ExceptionRow();
        synchronized (store.lock) {
            r.id = store.nextId();
            r.waybillNo = waybillNo;
            r.type = type;
            r.photos = photos == null ? "" : photos;
            r.descText = desc == null ? "" : desc;
            r.status = "待处理";
            r.createdAt = ExceptionStore.now();
            r.closedAt = null;
            r.severity = "中度";
            store.exceptions.add(r);
        }

        // 原版在同一事务里推运单状态：失败整体回滚（此处以补偿删除等价实现）
        if (PUSH_ON_REGISTER.contains(wStatus)) {
            String err;
            try {
                err = waybills.pushStatus(waybillNo, "异常");
            } catch (WaybillGateway.Down e) {
                removeRow(r.id);
                return degraded();
            }
            if (err != null) {
                removeRow(r.id);
                return fail(err, HttpStatus.BAD_REQUEST);
            }
        }

        String regDesc = "异常类型：" + type + (desc != null && !desc.isEmpty() ? "，" + desc : "");
        synchronized (store.lock) {
            store.addEvent(waybillNo, "异常登记", regDesc);
        }
        events.publish(waybillNo, "异常登记", regDesc);
        return ResponseEntity.ok(Map.of("ok", true, "data", Map.of("id", r.id)));
    }

    private void removeRow(long id) {
        synchronized (store.lock) {
            store.exceptions.removeIf(r -> r.id == id);
        }
    }

    /* ---------------- 定责与赔付试算（不落库） ---------------- */

    @GetMapping("/exceptions/{id}/assess")
    public ResponseEntity<?> assess(@PathVariable String id,
                                    @RequestParam(name = "severity", required = false) String severity) {
        Long eid = ExJson.asLong(id);
        Map<String, Object> a = eid == null ? null : assessor.assessForException(eid, severity, null);
        if (a == null) return fail("异常单不存在", HttpStatus.NOT_FOUND);
        Map<String, Object> data = new LinkedHashMap<>(a);
        List<Map<String, Object>> options = new ArrayList<>();
        for (Map.Entry<String, ClaimEngine.Sev> e : ClaimEngine.SEVERITY.entrySet()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("key", e.getKey());
            o.put("ratio", ExceptionStore.num(e.getValue().ratio));
            o.put("label", e.getValue().label);
            options.add(o);
        }
        data.put("severity_options", options);
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    /* ---------------- 备选流：仅拒收无损失 → 直接退回关闭，不走理赔 ---------------- */

    @PostMapping("/exceptions/{id}/close")
    public ResponseEntity<?> close(@PathVariable String id,
                                   @RequestBody(required = false) Map<String, Object> b) {
        Long eid = ExJson.asLong(id);
        ExceptionStore.ExceptionRow ex = null;
        if (eid != null) {
            synchronized (store.lock) {
                ex = store.byId(eid);
            }
        }
        if (ex == null) return fail("异常单不存在", HttpStatus.NOT_FOUND);
        if ("已理赔".equals(ex.status) || "已关闭".equals(ex.status)) {
            return fail("异常单已" + ex.status, HttpStatus.BAD_REQUEST);
        }
        String reason = b == null ? null : ExJson.asString(b.get("reason"));
        if (reason == null) reason = "拒收".equals(ex.type) ? "收件人拒收，原件退回" : "无需理赔，直接关闭";

        // 先推运单状态（可失败步），成功后再落本地关闭，等价原版事务回滚语义
        String err;
        try {
            err = waybills.pushStatus(ex.waybillNo, "已关闭");
        } catch (WaybillGateway.Down e) {
            return degraded();
        }
        if (err != null) return fail(err, HttpStatus.BAD_REQUEST);

        synchronized (store.lock) {
            ex.status = "已关闭";
            ex.closedAt = ExceptionStore.now();
            store.addEvent(ex.waybillNo, "异常关闭", reason);
        }
        events.publish(ex.waybillNo, "异常关闭", reason);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "已关闭");
        data.put("reason", reason);
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }
}
