package com.aped.billing;

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
import org.springframework.web.bind.annotation.RestController;

/**
 * 月结账单（对应原版 services/billing.js 的账单部分，逐项照原版语义）：
 * - GET  /billing                 账单列表：客户只看自己（原版 WHERE customer_id），附 customer_name/monthly
 * - POST /billing/generate        月结出账（allow finance）：同客户同账期已有账单 → 拒绝重复出账（幂等口径照原版）；
 *                                 只汇总该账期内 status=已签收 的运单计费快照
 * - GET  /billing/:id             账单详情：bill + 运单级明细 + 核销流水 + 已冻结金额；客户看他人 403
 * - POST /billing/items/:id/freeze 冻结/解冻单条明细（allow finance），账单金额按未冻结明细重算；已结清账单不可再动
 * - POST /billing/settle          回款核销（allow finance）：写 receipts 流水 + 累计 paid_amount，
 *                                 付满转「已结清」否则「部分核销」；兼容旧前端字段名 paid
 * - GET  /billing/:id/receipts    核销流水（原版此接口无归属校验，照原版保留并在 PROGRESS 注明）
 * 身份来自网关注入的 X-User-* 头；客户信息（名称/月结标记）经 Feign 向 customer-service 取，
 * 对应计划调用矩阵 billing → customer。账单数据先存内存（落库留待后续批次）。
 */
@RestController
public class BillingController {

    private final BillingStore store;
    private final CustomerClient customerClient;

    public BillingController(BillingStore store, CustomerClient customerClient) {
        this.store = store;
        this.customerClient = customerClient;
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

    /** 对应原版 allow('finance')：无身份 401「未登录」；admin 恒通过；其余 403（文案同原版）。 */
    private ResponseEntity<Map<String, Object>> requireFinance(Identity idn) {
        if (idn == null) return fail("未登录", HttpStatus.UNAUTHORIZED);
        if ("admin".equals(idn.role()) || "finance".equals(idn.role())) return null;
        return fail("当前角色（" + idn.role() + "）无此操作权限", HttpStatus.FORBIDDEN);
    }

    /* ---------------- 客户信息（Feign → customer-service，降级走本地种子副本） ---------------- */

    /** 查单个客户档案；不存在返回 null。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> customerOf(long id) {
        try {
            Map<String, Object> resp = customerClient.getCustomer(id);
            if (resp != null && Boolean.TRUE.equals(resp.get("ok"))
                    && resp.get("data") instanceof Map<?, ?> data
                    && data.get("customer") instanceof Map<?, ?> cust) {
                return (Map<String, Object>) cust;
            }
        } catch (Exception ignored) { /* 落到种子兜底 */ }
        BillingStore.Cust seed = BillingStore.seedCustomer(id);
        if (seed == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", seed.id());
        m.put("name", seed.name());
        m.put("monthly", seed.monthly());
        return m;
    }

    /** 客户 id → 档案映射（列表页 JOIN customers 的等价物）。 */
    @SuppressWarnings("unchecked")
    private Map<Long, Map<String, Object>> customerMap() {
        Map<Long, Map<String, Object>> map = new LinkedHashMap<>();
        try {
            Map<String, Object> resp = customerClient.listCustomers();
            if (resp != null && Boolean.TRUE.equals(resp.get("ok"))
                    && resp.get("data") instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> c && c.get("id") instanceof Number n) {
                        map.put(n.longValue(), (Map<String, Object>) c);
                    }
                }
                return map;
            }
        } catch (Exception ignored) { /* 落到种子兜底 */ }
        for (BillingStore.Cust c : BillingStore.CUSTOMER_SEED) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("name", c.name());
            m.put("monthly", c.monthly());
            map.put(c.id(), m);
        }
        return map;
    }

    /* ---------------- 行 JSON（列序同原版表定义） ---------------- */

    private Map<String, Object> billJson(BillingStore.Bill b, Map<String, Object> cust, boolean withMonthly) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.id);
        m.put("bill_no", b.billNo);
        m.put("customer_id", b.customerId);
        m.put("period", b.period);
        m.put("amount", BillingStore.num(b.amount));
        m.put("item_count", b.itemCount);
        m.put("status", b.status);
        m.put("created_at", b.createdAt);
        m.put("paid_amount", BillingStore.num(b.paidAmount));
        m.put("receipt_count", b.receiptCount);
        m.put("customer_name", cust == null ? null : cust.get("name"));
        if (withMonthly) m.put("monthly", cust == null ? null : cust.get("monthly"));
        return m;
    }

    private static Map<String, Object> itemJson(BillingStore.BillItem i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", i.id);
        m.put("bill_id", i.billId);
        m.put("waybill_no", i.waybillNo);
        m.put("product", i.product);
        m.put("fee", BillingStore.num(i.fee));
        m.put("signed_at", i.signedAt);
        m.put("frozen", i.frozen);
        return m;
    }

    private static Map<String, Object> receiptJson(BillingStore.Receipt r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("bill_id", r.billId);
        m.put("amount", BillingStore.num(r.amount));
        m.put("paid_at", r.paidAt);
        m.put("voucher", r.voucher);
        m.put("operator", r.operator);
        return m;
    }

    /* ---------------- 账单列表 ---------------- */

    @GetMapping("/billing")
    public ResponseEntity<?> list(HttpServletRequest req) {
        Identity idn = identity(req);
        if (idn == null) return fail("未登录或登录已过期", HttpStatus.UNAUTHORIZED);
        boolean own = "customer".equals(idn.role());
        Map<Long, Map<String, Object>> custs = customerMap();
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (store.lock) {
            List<BillingStore.Bill> copy = new ArrayList<>(store.bills);
            copy.sort((a, b) -> Long.compare(b.id, a.id)); // ORDER BY b.id DESC
            for (BillingStore.Bill b : copy) {
                if (own && (idn.customerId() == null || idn.customerId() != b.customerId)) continue;
                out.add(billJson(b, custs.get(b.customerId), true));
            }
        }
        return ResponseEntity.ok(Map.of("ok", true, "data", out));
    }

    /* ---------------- 月结出账 ---------------- */

    @PostMapping("/billing/generate")
    public ResponseEntity<?> generate(@RequestBody(required = false) Map<String, Object> b,
                                      HttpServletRequest req) {
        Identity idn = identity(req);
        ResponseEntity<Map<String, Object>> denied = requireFinance(idn);
        if (denied != null) return denied;
        Map<String, Object> body = b == null ? Map.of() : b;

        Long customerId = asLong(body.get("customer_id"));
        String period = asString(body.get("period"));
        // 原版：if (!customer_id || !period) —— customer_id=0 同样算缺
        if (customerId == null || customerId == 0 || period == null || period.isEmpty()) {
            return fail("customer_id 与 period 必填", HttpStatus.BAD_REQUEST);
        }
        Map<String, Object> cust = customerOf(customerId);
        if (cust == null) return fail("客户不存在", HttpStatus.NOT_FOUND);
        Object monthly = cust.get("monthly");
        if (!(monthly instanceof Number n) || n.intValue() == 0) {
            return fail("该客户未开通月结协议", HttpStatus.BAD_REQUEST);
        }

        synchronized (store.lock) {
            // 幂等：同客户同账期已有账单直接拒绝（原版靠 dup 查询，语义照搬）
            for (BillingStore.Bill exist : store.bills) {
                if (exist.customerId == customerId && exist.period.equals(period)) {
                    return fail("该账期已生成账单 " + exist.billNo + "，请勿重复出账", HttpStatus.BAD_REQUEST);
                }
            }
            // 该账期内已签收的计费运单（原版 JOIN orders 取 customer_id；快照已冗余 customer_id）
            List<BillingStore.Waybill> rows = new ArrayList<>();
            for (BillingStore.Waybill w : store.waybills) {
                if (w.customerId != null && w.customerId == customerId
                        && w.createdAt != null && w.createdAt.startsWith(period)
                        && "已签收".equals(w.status)) {
                    rows.add(w);
                }
            }
            if (rows.isEmpty()) return fail("该账期内没有已签收的计费运单", HttpStatus.BAD_REQUEST);

            double amount = 0;
            for (BillingStore.Waybill w : rows) amount += w.fee;
            amount = BillingStore.round2(amount);

            List<String> nos = new ArrayList<>();
            for (BillingStore.Bill x : store.bills) nos.add(x.billNo);
            String billNo = store.nextNo("BILL", 4, nos);

            BillingStore.Bill bill = new BillingStore.Bill();
            bill.id = store.nextBillId();
            bill.billNo = billNo;
            bill.customerId = customerId;
            bill.period = period;
            bill.amount = amount;
            bill.itemCount = rows.size();
            bill.status = "待确认";
            bill.createdAt = BillingStore.now();
            bill.paidAmount = 0;
            bill.receiptCount = 0;
            store.bills.add(bill);

            for (BillingStore.Waybill w : rows) {
                BillingStore.BillItem it = new BillingStore.BillItem();
                it.id = store.nextItemId();
                it.billId = bill.id;
                it.waybillNo = w.waybillNo;
                it.product = w.product == null ? "" : w.product;
                it.fee = w.fee;
                it.signedAt = w.signedAt;
                it.frozen = 0;
                store.items.add(it);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("bill_no", billNo);
            data.put("amount", BillingStore.num(amount));
            data.put("item_count", rows.size());
            data.put("bill_id", bill.id);
            return ResponseEntity.ok(Map.of("ok", true, "data", data));
        }
    }

    /* ---------------- 账单详情 ---------------- */

    @GetMapping("/billing/{id}")
    public ResponseEntity<?> detail(@PathVariable long id, HttpServletRequest req) {
        Identity idn = identity(req);
        if (idn == null) return fail("未登录或登录已过期", HttpStatus.UNAUTHORIZED);
        synchronized (store.lock) {
            BillingStore.Bill bill = store.billById(id);
            if (bill == null) return fail("账单不存在", HttpStatus.NOT_FOUND);
            if ("customer".equals(idn.role())
                    && (idn.customerId() == null || idn.customerId() != bill.customerId)) {
                return fail("只能查看自己的账单", HttpStatus.FORBIDDEN);
            }
            Map<String, Object> cust = customerOf(bill.customerId);
            List<Map<String, Object>> itemList = new ArrayList<>();
            double frozenSum = 0;
            for (BillingStore.BillItem i : store.itemsOf(bill.id)) {
                itemList.add(itemJson(i));
                if (i.frozen == 1) frozenSum += i.fee;
            }
            List<Map<String, Object>> receiptList = new ArrayList<>();
            for (BillingStore.Receipt r : store.receiptsOf(bill.id)) receiptList.add(receiptJson(r));

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("bill", billJson(bill, cust, false));
            data.put("items", itemList);
            data.put("receipts", receiptList);
            data.put("frozen_amount", BillingStore.num(BillingStore.round2(frozenSum)));
            return ResponseEntity.ok(Map.of("ok", true, "data", data));
        }
    }

    /* ---------------- 冻结/解冻明细 ---------------- */

    @PostMapping("/billing/items/{id}/freeze")
    public ResponseEntity<?> freeze(@PathVariable long id,
                                    @RequestBody(required = false) Map<String, Object> b,
                                    HttpServletRequest req) {
        Identity idn = identity(req);
        ResponseEntity<Map<String, Object>> denied = requireFinance(idn);
        if (denied != null) return denied;
        synchronized (store.lock) {
            BillingStore.BillItem item = store.itemById(id);
            if (item == null) return fail("明细不存在", HttpStatus.NOT_FOUND);
            BillingStore.Bill bill = store.billById(item.billId);
            if (bill != null && "已结清".equals(bill.status)) {
                return fail("账单已结清，不能再冻结/解冻明细", HttpStatus.BAD_REQUEST);
            }
            // 原版：req.body?.frozen === false 才解冻，其余（含缺省）都冻结
            int frozen = (b != null && Boolean.FALSE.equals(b.get("frozen"))) ? 0 : 1;
            item.frozen = frozen;
            double sum = 0;
            for (BillingStore.BillItem i : store.itemsOf(item.billId)) {
                if (i.frozen == 0) sum += i.fee;
            }
            bill.amount = BillingStore.round2(sum);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("bill_id", bill.id);
            data.put("amount", BillingStore.num(bill.amount));
            data.put("frozen", frozen == 1);
            data.put("waybill_no", item.waybillNo);
            return ResponseEntity.ok(Map.of("ok", true, "data", data));
        }
    }

    /* ---------------- 回款核销 ---------------- */

    @PostMapping("/billing/settle")
    public ResponseEntity<?> settle(@RequestBody(required = false) Map<String, Object> b,
                                    HttpServletRequest req) {
        Identity idn = identity(req);
        ResponseEntity<Map<String, Object>> denied = requireFinance(idn);
        if (denied != null) return denied;
        Map<String, Object> body = b == null ? Map.of() : b;
        synchronized (store.lock) {
            Long billId = asLong(body.get("id"));
            BillingStore.Bill bill = billId == null ? null : store.billById(billId);
            if (bill == null) return fail("账单不存在", HttpStatus.NOT_FOUND);
            if ("已结清".equals(bill.status)) return fail("该账单已结清", HttpStatus.BAD_REQUEST);

            double remain = BillingStore.round2(bill.amount - bill.paidAmount);
            // 原版：amount ?? paid（旧前端字段名）；缺省/空串 → 按待收余额全额核销
            Object raw = body.get("amount") != null ? body.get("amount") : body.get("paid");
            double paid;
            if (raw == null || (raw instanceof String s && s.isEmpty())) {
                paid = remain;
            } else {
                Double p = asDouble(raw);
                if (p == null || !(p > 0)) return fail("核销金额须大于 0", HttpStatus.BAD_REQUEST);
                paid = p;
            }
            if (paid > remain + 1e-6) {
                return fail("核销金额不能超过待收余额 ¥" + BillingStore.fmt(remain), HttpStatus.BAD_REQUEST);
            }

            double nextPaid = BillingStore.round2(bill.paidAmount + paid);
            String status = nextPaid >= bill.amount - 1e-6 ? "已结清" : "部分核销";

            BillingStore.Receipt r = new BillingStore.Receipt();
            r.id = store.nextReceiptId();
            r.billId = bill.id;
            r.amount = paid;
            r.paidAt = BillingStore.now();
            String voucher = asString(body.get("voucher"));
            r.voucher = voucher == null ? "" : voucher;
            String operator = asString(body.get("operator"));
            r.operator = operator == null ? "财务" : operator;
            store.receipts.add(r);

            bill.paidAmount = nextPaid;
            bill.receiptCount += 1;
            bill.status = status;

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", status);
            data.put("paid_amount", BillingStore.num(nextPaid));
            data.put("remaining", BillingStore.num(BillingStore.round2(bill.amount - nextPaid)));
            return ResponseEntity.ok(Map.of("ok", true, "data", data));
        }
    }

    /* ---------------- 核销流水 ---------------- */

    @GetMapping("/billing/{id}/receipts")
    public ResponseEntity<?> receipts(@PathVariable long id, HttpServletRequest req) {
        Identity idn = identity(req);
        if (idn == null) return fail("未登录或登录已过期", HttpStatus.UNAUTHORIZED);
        // 照原版：不校验账单归属（客户也能查任意账单流水），差异已在 PROGRESS 注明
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (store.lock) {
            for (BillingStore.Receipt r : store.receiptsOf(id)) out.add(receiptJson(r));
        }
        return ResponseEntity.ok(Map.of("ok", true, "data", out));
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
}
