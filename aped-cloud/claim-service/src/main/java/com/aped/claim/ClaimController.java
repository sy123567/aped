package com.aped.claim;

import feign.FeignException;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 理赔单对外接口：对应原版 services/claim.js 的 /claims 部分，路径/返回/文案逐字一致。
 * 定责核定不在本服务重算：经 exception-service 的内部试算接口取结果（引擎只一份，避免口径漂移）。
 * 跨服务多写链无分布式事务：按「先做可失败的跨服务校验/推进、再落本地行」的顺序串行，
 * 失败语义等价于原版事务回滚结果（同批次 3/4 口径）；关单靠运单状态机 + 状态对账兜底。
 * 角色门只在网关层（理赔前缀 → claims，admin 恒通过），原版 claim.js 路由内无 allow 调用。
 */
@RestController
public class ClaimController {

    /** 下游服务不可用（区别于业务拒绝）。 */
    private static final class Down extends RuntimeException {
        Down(String name) { super(name); }
    }

    private final ClaimStore store;
    private final ExceptionClient exceptionClient;
    private final BillingClient billingClient;
    private final NotifyClient notifyClient;

    public ClaimController(ClaimStore store, ExceptionClient exceptionClient, BillingClient billingClient,
                           NotifyClient notifyClient) {
        this.store = store;
        this.exceptionClient = exceptionClient;
        this.billingClient = billingClient;
        this.notifyClient = notifyClient;
    }

    /** 通知旁路写入（批次 6）：best-effort，失败不阻断理赔主流程（计划矩阵口径）。 */
    private void writeNotify(String type, String refNo, String content) {
        try {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("type", type);
            n.put("ref_no", refNo);
            n.put("content", content);
            n.put("recipient", null);
            notifyClient.create(n);
        } catch (Exception ignored) {
            // 通知为横切旁路
        }
    }

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

    /* ---------------- 下游访问封装 ---------------- */

    /** 取异常单整行：不存在返回 null；服务异常抛 Down。 */
    private Map<String, Object> fetchException(long id) {
        Map<String, Object> resp;
        try {
            resp = exceptionClient.get(id);
        } catch (FeignException e) {
            if (e.status() == 404) return null;
            throw new Down("异常服务");
        } catch (Exception e) {
            throw new Down("异常服务");
        }
        Map<String, Object> data = ClaimJson.dataMap(resp);
        if (data == null) throw new Down("异常服务");
        return data;
    }

    /** 回写异常单字段（severity 落档 / status 流转）：失败抛 Down。 */
    private void updateException(long id, Map<String, Object> fields) {
        try {
            exceptionClient.updateStatus(id, fields);
        } catch (Exception e) {
            throw new Down("异常服务");
        }
    }

    /** 写理赔轨迹事件：失败抛 Down。 */
    private void postEvent(String waybillNo, String node, String desc) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("waybill_no", waybillNo);
        body.put("node", node);
        body.put("desc_text", desc);
        try {
            exceptionClient.postEvent(body);
        } catch (Exception e) {
            throw new Down("异常服务");
        }
    }

    /* ---------------- 理赔单列表（JOIN 异常单） ---------------- */

    @GetMapping("/claims")
    public ResponseEntity<?> list() {
        List<ClaimStore.ClaimRow> rows;
        synchronized (store.lock) {
            rows = new ArrayList<>(store.claims);
        }
        rows.sort((a, b) -> Long.compare(b.id, a.id)); // ORDER BY c.id DESC
        Map<Long, Map<String, Object>> exCache = new HashMap<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (ClaimStore.ClaimRow c : rows) {
            Map<String, Object> m = ClaimJson.claimJson(c);
            Map<String, Object> ex = exCache.computeIfAbsent(c.exceptionId, id -> {
                try {
                    return fetchException(id);
                } catch (Down d) {
                    return null; // JOIN 拼装降级：异常服务不可用时关联字段为 null
                }
            });
            m.put("waybill_no", ex == null ? null : ex.get("waybill_no"));
            m.put("type", ex == null ? null : ex.get("type"));
            m.put("exception_status", ex == null ? null : ex.get("status"));
            out.add(m);
        }
        return ResponseEntity.ok(Map.of("ok", true, "data", out));
    }

    /* ---------------- 发起理赔 ---------------- */

    @PostMapping("/claims")
    public ResponseEntity<?> create(@RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> body = b == null ? Map.of() : b;
        Long eid = ClaimJson.asLong(body.get("exception_id"));

        Map<String, Object> ex;
        try {
            ex = eid == null ? null : fetchException(eid);
        } catch (Down d) {
            return degraded("异常服务");
        }
        if (ex == null) return fail("异常单不存在", HttpStatus.NOT_FOUND);
        String exStatus = ClaimJson.asString(ex.get("status"));
        if ("已理赔".equals(exStatus) || "已关闭".equals(exStatus)) {
            return fail("异常单已" + exStatus + "，不能再发起理赔", HttpStatus.BAD_REQUEST);
        }
        synchronized (store.lock) {
            for (ClaimStore.ClaimRow c : store.claims) {
                if (c.exceptionId == eid && ("待审批".equals(c.status) || "已赔付".equals(c.status))) {
                    return fail("该异常单已存在待审批或已赔付的理赔单", HttpStatus.BAD_REQUEST);
                }
            }
        }

        // 定责 + 核定：责任方、折损分档、赔付上限全部由规则引擎给出（在 exception-service）
        Map<String, Object> a;
        try {
            Map<String, Object> resp = exceptionClient.assess(eid,
                    ClaimJson.asString(body.get("severity")), ClaimJson.asString(ex.get("photos")));
            a = ClaimJson.dataMap(resp);
        } catch (Exception e) {
            a = null;
        }
        if (a == null) return fail("核定失败", HttpStatus.INTERNAL_SERVER_ERROR);

        double amt = ClaimJson.jsNumber(a.get("amount"));
        List<String> ruleList = new ArrayList<>();
        if (a.get("rules") instanceof List<?> rules) {
            for (Object r : rules) if (r instanceof String s) ruleList.add(s);
        }
        // 只有显式 manual=true 才允许人工改编金额（需在审批里写明理由）
        Object amountRaw = body.get("amount");
        if (ClaimJson.truthy(body.get("manual")) && amountRaw != null && ClaimJson.jsNumber(amountRaw) > 0) {
            amt = ClaimJson.jsNumber(amountRaw);
            ruleList.add("人工核定调整至 ¥" + ClaimJson.jsNum(amt));
        }
        if (!(amt > 0)) {
            return fail("customer".equals(a.get("liable"))
                    ? "该异常属非承运责任，赔付金额为 0；如确需赔付请传 manual=true 人工核定"
                    : "核定金额为 0，请检查保价额与折损程度", HttpStatus.BAD_REQUEST);
        }
        double cap = ClaimJson.jsNumber(a.get("cap"));
        if (cap > 0 && amt > cap) {
            return fail("核定金额 ¥" + ClaimJson.jsNum(amt) + " 超过上限 ¥" + ClaimJson.jsNum(cap),
                    HttpStatus.BAD_REQUEST);
        }

        String evidence = ClaimJson.asString(body.get("evidence"));
        if (evidence == null) evidence = ClaimJson.asString(ex.get("photos"));
        if (evidence == null) evidence = "";

        ClaimStore.ClaimRow row = new ClaimStore.ClaimRow();
        try {
            // 对应原版 tx 内的三写一事件：severity 落档 → 理赔单落库 → 异常单转待审批 → 轨迹
            updateException(eid, Map.of("severity", String.valueOf(a.get("severity"))));
            synchronized (store.lock) {
                row.id = store.nextId();
                row.exceptionId = eid;
                row.amount = ClaimJson.round2(amt);
                row.rule = String.join("；", ruleList);
                row.status = "待审批";
                row.evidence = evidence;
                row.approvedBy = null;
                row.paidAt = null;
                store.claims.add(row);
            }
            updateException(eid, Map.of("status", "待审批"));
            postEvent(ClaimJson.asString(ex.get("waybill_no")), "理赔受理",
                    a.get("liable_label") + "｜" + a.get("severity_label") + "｜核定 ¥" + ClaimJson.fixed2(amt));
        } catch (Down d) {
            return degraded("异常服务");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("claim_id", row.id);
        data.put("amount", ClaimStore.num(ClaimJson.round2(amt)));
        data.put("liable", a.get("liable"));
        data.put("liable_label", a.get("liable_label"));
        data.put("severity", a.get("severity"));
        data.put("severity_label", a.get("severity_label"));
        data.put("ratio", a.get("ratio"));
        data.put("cap", a.get("cap"));
        data.put("rules", ruleList);
        data.put("need_evidence", a.get("need_evidence"));
        data.put("need_second_approval", a.get("need_second_approval"));
        return ResponseEntity.ok(Map.of("ok", true, "data", data));
    }

    /* ---------------- 审批（赔付 / 驳回） ---------------- */

    @PostMapping("/claims/approve")
    public ResponseEntity<?> approve(@RequestBody(required = false) Map<String, Object> b) {
        Map<String, Object> body = b == null ? Map.of() : b;
        Long cid = ClaimJson.asLong(body.get("id"));
        ClaimStore.ClaimRow c = null;
        if (cid != null) {
            synchronized (store.lock) {
                c = store.byId(cid);
            }
        }
        if (c == null) return fail("理赔单不存在", HttpStatus.NOT_FOUND);
        if (!"待审批".equals(c.status)) {
            return fail("理赔单已" + c.status + "，不能重复审批", HttpStatus.BAD_REQUEST);
        }

        Map<String, Object> ex;
        try {
            ex = fetchException(c.exceptionId);
        } catch (Down d) {
            return degraded("异常服务");
        }
        if (ex == null) return fail("异常单不存在", HttpStatus.NOT_FOUND);
        String waybillNo = ClaimJson.asString(ex.get("waybill_no"));
        String by = ClaimJson.asString(body.get("by"));
        if (by == null) by = "理赔专员";
        boolean reject = body.get("approved") instanceof Boolean approvedFlag && !approvedFlag;

        try {
            if (reject) {
                synchronized (store.lock) {
                    c.status = "已驳回";
                    c.approvedBy = by;
                }
                updateException(c.exceptionId, Map.of("status", "待处理"));
                postEvent(waybillNo, "理赔驳回", "审批人：" + by);
                return ResponseEntity.ok(Map.of("ok", true, "data", Map.of("status", "已驳回")));
            }
            // 赔付：先推运单关单（唯一可业务失败步），成功后再落本地与异常侧状态，
            // 等价于原版「关单失败则整事务回滚」的结果。
            String err = pushWaybillClosed(waybillNo);
            if (err != null) return fail(err, HttpStatus.BAD_REQUEST);
            synchronized (store.lock) {
                c.status = "已赔付";
                c.approvedBy = by;
                c.paidAt = ClaimStore.now();
            }
            updateException(c.exceptionId, Map.of("status", "已理赔"));
            postEvent(waybillNo, "理赔赔付", "赔付 ¥" + ClaimJson.jsNum(c.amount) + "，审批人：" + by);
            writeNotify("claim_paid", waybillNo,
                    "运单 " + waybillNo + " 理赔已赔付 ¥" + ClaimJson.jsNum(c.amount));
            return ResponseEntity.ok(Map.of("ok", true, "data", Map.of("status", "已赔付")));
        } catch (Down d) {
            return degraded(d.getMessage());
        }
    }

    /** 推运单「已关闭」：成功返回 null；业务拒绝返回下游文案；服务异常抛 Down。 */
    private String pushWaybillClosed(String waybillNo) {
        try {
            billingClient.setWaybillStatus(waybillNo, Map.of("to", "已关闭"));
            return null;
        } catch (FeignException e) {
            String msg = ClaimJson.feignError(e);
            if (e.status() == 400 && msg != null) return msg;
            if (e.status() == 404) return msg == null ? "运单不存在" : msg;
            throw new Down("运单服务");
        } catch (Exception e) {
            throw new Down("运单服务");
        }
    }
}
