package com.aped.notify;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通知服务（全量计划新增模块，原 Node 版无对应实现，按架构文档横切设计落地）：
 * - POST /internal/notifications  各服务状态变更时写通知记录（计划矩阵：各服务 → notify，
 *                                 Feign 调用、失败不阻断主流程，容错在调用方）。
 * - GET  /notifications           通知列表（登录可见，可按关联单号过滤），新通知在前。
 * - POST /notifications/{id}/read 标记已读。
 */
@RestController
public class NotifyController {

    private final NotifyStore store;

    public NotifyController(NotifyStore store) {
        this.store = store;
    }

    private static ResponseEntity<Map<String, Object>> fail(String msg, HttpStatus status) {
        return ResponseEntity.status(status).body(Map.of("ok", false, "error", msg));
    }

    private static Map<String, Object> noticeJson(NotifyStore.Notice n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.id);
        m.put("type", n.type);
        m.put("ref_no", n.refNo);
        m.put("content", n.content);
        m.put("recipient", n.recipient);
        m.put("created_at", n.createdAt);
        m.put("read", n.read);
        return m;
    }

    @PostMapping("/internal/notifications")
    public ResponseEntity<?> create(@RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> body = b == null ? Map.of() : b;
        NotifyStore.Notice n = new NotifyStore.Notice();
        synchronized (store.lock) {
            n.id = store.nextId();
            n.type = asString(body.get("type"));
            n.refNo = asString(body.get("ref_no"));
            n.content = asString(body.get("content"));
            n.recipient = asString(body.get("recipient"));
            n.createdAt = NotifyStore.now();
            n.read = false;
            store.notices.add(n);
        }
        return ResponseEntity.ok(Map.of("ok", true, "data", noticeJson(n)));
    }

    @GetMapping("/notifications")
    public ResponseEntity<?> list(@RequestParam(name = "ref_no", required = false) String refNo,
                                  HttpServletRequest req) {
        String role = req.getHeader("X-User-Role");
        if (role == null || role.isBlank()) return fail("未登录或登录已过期", HttpStatus.UNAUTHORIZED);
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (store.lock) {
            List<NotifyStore.Notice> copy = new ArrayList<>(store.notices);
            copy.sort((a, b) -> Long.compare(b.id, a.id)); // 新通知在前
            for (NotifyStore.Notice n : copy) {
                if (refNo != null && !refNo.isEmpty() && !refNo.equals(n.refNo)) continue;
                out.add(noticeJson(n));
            }
        }
        return ResponseEntity.ok(Map.of("ok", true, "data", out));
    }

    @PostMapping("/notifications/{id}/read")
    public ResponseEntity<?> markRead(@PathVariable long id, HttpServletRequest req) {
        String role = req.getHeader("X-User-Role");
        if (role == null || role.isBlank()) return fail("未登录或登录已过期", HttpStatus.UNAUTHORIZED);
        synchronized (store.lock) {
            NotifyStore.Notice n = store.byId(id);
            if (n == null) return fail("通知不存在", HttpStatus.NOT_FOUND);
            n.read = true;
            return ResponseEntity.ok(Map.of("ok", true, "data", noticeJson(n)));
        }
    }

    private static String asString(Object o) {
        return o instanceof String s ? s : null;
    }
}
