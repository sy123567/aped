package com.aped.report;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 绩效统计服务（对应原版 services/performance.js + core/kpi.js）：
 * - GET /performance        ⑨ 快递员揽派绩效排名（KPI 引擎：归一化 + 权重 + 小样本剔除）
 * - GET /reports/summary    ⑩ 运营统计汇总
 * - GET /reports/preview    ⑩ 报表数据集预览（导出共用同一 dataset）
 * - GET /reports/export     ⑩ 导出 CSV / Excel(HTML 表格)，形态与原版逐字一致
 * - GET /board              工作台指标（原版无 allow，登录即可）
 * 角色限制对应原版 allow('ops','finance')（admin 恒通过），按网关注入的 X-User-* 头在服务内执行。
 * 数据按全量计划经 Feign 只读聚合（report → task/billing/exception/claim/customer/order），
 * 聚合口径逐条对应原版 SQL；下游不可用时返回 503 降级，不拼残缺数据。
 */
@RestController
public class ReportController {

    private final TaskClient taskClient;
    private final BillingClient billingClient;
    private final ExceptionClient exceptionClient;
    private final ClaimClient claimClient;
    private final CustomerClient customerClient;
    private final OrderClient orderClient;

    public ReportController(TaskClient taskClient, BillingClient billingClient,
                            ExceptionClient exceptionClient, ClaimClient claimClient,
                            CustomerClient customerClient, OrderClient orderClient) {
        this.taskClient = taskClient;
        this.billingClient = billingClient;
        this.exceptionClient = exceptionClient;
        this.claimClient = claimClient;
        this.customerClient = customerClient;
        this.orderClient = orderClient;
    }

    /* ---------------- 基础响应/身份/降级 ---------------- */

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

    private static final class Down extends RuntimeException {
        final String service;
        Down(String service) { super(service); this.service = service; }
    }

    private static String role(HttpServletRequest req) {
        String role = req.getHeader("X-User-Role");
        return role == null || role.isBlank() ? null : role;
    }

    /** 对应原版 allow('ops','finance')：未登录 401、角色不符 403（文案同原版），通过返回 null。 */
    private static ResponseEntity<Map<String, Object>> allowOpsFinance(HttpServletRequest req) {
        String role = role(req);
        if (role == null) return fail("未登录或登录已过期", HttpStatus.UNAUTHORIZED);
        if ("admin".equals(role) || "ops".equals(role) || "finance".equals(role)) return null;
        return fail("当前角色（" + role + "）无此操作权限", HttpStatus.FORBIDDEN);
    }

    private static String monthOf() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    /** substr(x,1,7) = period 的月份口径。 */
    private static boolean inMonth(Object ts, String period) {
        String s = ReportJson.asString(ts);
        return s != null && s.length() >= 7 && s.substring(0, 7).equals(period);
    }

    /* ---------------- 下游只读取数（失败抛 Down） ---------------- */

    private List<Map<String, Object>> fetchWaybills() {
        try {
            List<Map<String, Object>> l = ReportJson.dataList(billingClient.waybills());
            if (l == null) throw new Down("计费服务");
            return l;
        } catch (Down d) { throw d; } catch (Exception e) { throw new Down("计费服务"); }
    }

    private List<Map<String, Object>> fetchBills() {
        try {
            List<Map<String, Object>> l = ReportJson.dataList(billingClient.bills());
            if (l == null) throw new Down("计费服务");
            return l;
        } catch (Down d) { throw d; } catch (Exception e) { throw new Down("计费服务"); }
    }

    private List<Map<String, Object>> fetchExceptions() {
        try {
            List<Map<String, Object>> l = ReportJson.dataList(exceptionClient.listAll());
            if (l == null) throw new Down("异常服务");
            return l;
        } catch (Down d) { throw d; } catch (Exception e) { throw new Down("异常服务"); }
    }

    private List<Map<String, Object>> fetchClaims() {
        try {
            List<Map<String, Object>> l = ReportJson.dataList(claimClient.listAll());
            if (l == null) throw new Down("理赔服务");
            return l;
        } catch (Down d) { throw d; } catch (Exception e) { throw new Down("理赔服务"); }
    }

    private List<Map<String, Object>> fetchCustomers() {
        try {
            List<Map<String, Object>> l = ReportJson.dataList(customerClient.list());
            if (l == null) throw new Down("客户服务");
            return l;
        } catch (Down d) { throw d; } catch (Exception e) { throw new Down("客户服务"); }
    }

    private List<Map<String, Object>> fetchTransitLogs() {
        try {
            List<Map<String, Object>> l = ReportJson.dataList(taskClient.transitLogs());
            if (l == null) throw new Down("任务服务");
            return l;
        } catch (Down d) { throw d; } catch (Exception e) { throw new Down("任务服务"); }
    }

    /* ---------------- ⑨ 绩效统计排名 ---------------- */

    /** 对应 performance.js 的 buildPerformance()：computeKpi 结果映射为对外行形态。 */
    private Map<String, Object> buildPerformance(String period) {
        List<Map<String, Object>> raws;
        try {
            raws = ReportJson.dataList(taskClient.kpiRows());
            if (raws == null) throw new Down("任务服务");
        } catch (Down d) { throw d; } catch (Exception e) { throw new Down("任务服务"); }

        Map<String, Object> k = KpiEngine.computeKpi(raws);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> kRows = (List<Map<String, Object>>) k.get("rows");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : kRows) {
            @SuppressWarnings("unchecked")
            Map<String, Object> metrics = (Map<String, Object>) r.get("metrics");
            @SuppressWarnings("unchecked")
            Map<String, Object> extras = (Map<String, Object>) r.get("extras");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank", r.get("rank"));
            m.put("code", r.get("code"));
            m.put("courier", r.get("courier"));
            m.put("grid_code", r.get("grid_code"));
            m.put("pickups", metrics.get("pickups"));
            m.put("deliveries", metrics.get("deliveries"));
            m.put("exceptions", extras.get("exceptions"));
            m.put("on_time_cnt", extras.get("on_time_cnt"));
            m.put("on_time_rate", metrics.get("on_time"));
            m.put("exception_rate", metrics.get("exception"));
            m.put("onTime", metrics.get("on_time"));          // 兼容旧前端字段名（同原版）
            m.put("exceptionRate", metrics.get("exception"));
            m.put("total", extras.get("total"));
            m.put("score", r.get("score"));
            m.put("grade", r.get("grade"));
            m.put("sample_ok", r.get("sample_ok"));
            m.put("exclude_reason", r.get("exclude_reason"));
            m.put("breakdown", r.get("breakdown"));
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", period == null ? monthOf() : period);
        out.put("rows", rows);
        out.put("weights", k.get("weights"));
        out.put("method", k.get("method"));
        out.put("min_sample", k.get("min_sample"));
        out.put("ranked_count", k.get("ranked_count"));
        return out;
    }

    @GetMapping("/performance")
    public ResponseEntity<?> performance(@RequestParam(name = "period", required = false) String period,
                                         HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> denied = allowOpsFinance(req);
        if (denied != null) return denied;
        try {
            return ResponseEntity.ok(Map.of("ok", true, "data", buildPerformance(period)));
        } catch (Down d) {
            return degraded(d.service);
        }
    }

    /* ---------------- ⑩ 运营统计汇总 ---------------- */

    @GetMapping("/reports/summary")
    public ResponseEntity<?> summary(@RequestParam(name = "period", required = false) String period,
                                     HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> denied = allowOpsFinance(req);
        if (denied != null) return denied;
        String p = period == null ? monthOf() : period;
        try {
            List<Map<String, Object>> waybills = fetchWaybills();
            List<Map<String, Object>> exceptions = fetchExceptions();
            List<Map<String, Object>> claims = fetchClaims();
            List<Map<String, Object>> bills = fetchBills();

            long wbCnt = 0, signed = 0, onTime = 0, late = 0;
            double revenue = 0;
            Map<String, long[]> byProduct = new LinkedHashMap<>();   // product -> [cnt]
            Map<String, Double> byProductFee = new LinkedHashMap<>();
            Map<String, Long> byStatus = new LinkedHashMap<>();
            for (Map<String, Object> w : waybills) {
                String status = ReportJson.asString(w.get("status"));
                byStatus.merge(status, 1L, Long::sum); // by_status 无月份过滤（同原版）
                if (!inMonth(w.get("created_at"), p)) continue;
                wbCnt++;
                revenue += ReportJson.jsNumber(w.get("fee"));
                String product = ReportJson.asString(w.get("product"));
                byProduct.computeIfAbsent(product, k -> new long[1])[0]++;
                byProductFee.merge(product, ReportJson.jsNumber(w.get("fee")), Double::sum);
                if ("已签收".equals(status)) {
                    signed++;
                    String promise = ReportJson.asString(w.get("promise_time"));
                    String signedAt = ReportJson.asString(w.get("signed_at"));
                    if (promise != null && signedAt != null) {
                        if (signedAt.compareTo(promise) <= 0) onTime++; else late++;
                    }
                }
            }

            long exCnt = 0;
            Map<String, Long> byException = new LinkedHashMap<>();
            for (Map<String, Object> e : exceptions) {
                if (!inMonth(e.get("created_at"), p)) continue;
                exCnt++;
                byException.merge(ReportJson.asString(e.get("type")), 1L, Long::sum);
            }

            long claimCnt = 0;
            double claimAmount = 0;
            for (Map<String, Object> c : claims) {
                if (!"已赔付".equals(ReportJson.asString(c.get("status")))) continue;
                if (!inMonth(c.get("paid_at"), p)) continue;
                claimCnt++;
                claimAmount += ReportJson.jsNumber(c.get("amount"));
            }

            long billCnt = 0;
            double billAmount = 0, billPaid = 0;
            for (Map<String, Object> b : bills) {
                if (!p.equals(ReportJson.asString(b.get("period")))) continue;
                billCnt++;
                billAmount += ReportJson.jsNumber(b.get("amount"));
                billPaid += ReportJson.jsNumber(b.get("paid_amount"));
            }

            List<Map<String, Object>> byProductOut = new ArrayList<>();
            for (Map.Entry<String, long[]> e : byProduct.entrySet()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("product", e.getKey());
                m.put("cnt", e.getValue()[0]);
                m.put("fee", ReportJson.num(ReportJson.round2(byProductFee.get(e.getKey()))));
                byProductOut.add(m);
            }
            // ORDER BY cnt DESC；并列时 SQLite sorter 实测按分组键字节序倒序出序（SQL 未定义序，照实测复刻；
            // BMP 字符下 String.compareTo 序与 UTF-8 字节序一致）
            byProductOut.sort((a, b) -> {
                int c = Long.compare(ReportJson.asLong(b.get("cnt")), ReportJson.asLong(a.get("cnt")));
                return c != 0 ? c : ReportJson.asString(b.get("product")).compareTo(ReportJson.asString(a.get("product")));
            });

            List<Map<String, Object>> byStatusOut = new ArrayList<>();
            for (Map.Entry<String, Long> e : byStatus.entrySet()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("status", e.getKey());
                m.put("cnt", e.getValue());
                byStatusOut.add(m);
            }
            byStatusOut.sort((a, b) -> {
                int c = Long.compare(ReportJson.asLong(b.get("cnt")), ReportJson.asLong(a.get("cnt")));
                return c != 0 ? c : ReportJson.asString(b.get("status")).compareTo(ReportJson.asString(a.get("status")));
            });

            List<Map<String, Object>> byExceptionOut = new ArrayList<>();
            for (Map.Entry<String, Long> e : byException.entrySet()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", e.getKey());
                m.put("cnt", e.getValue());
                byExceptionOut.add(m);
            }
            byExceptionOut.sort((a, b) -> {
                int c = Long.compare(ReportJson.asLong(b.get("cnt")), ReportJson.asLong(a.get("cnt")));
                return c != 0 ? c : ReportJson.asString(b.get("type")).compareTo(ReportJson.asString(a.get("type")));
            });

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("period", p);
            data.put("waybills", wbCnt);
            data.put("revenue", ReportJson.num(ReportJson.round2(revenue)));
            data.put("signed", signed);
            data.put("on_time", onTime);
            data.put("late", late);
            data.put("on_time_rate", signed > 0
                    ? ReportJson.num(ReportJson.round1((onTime / (double) signed) * 100)) : null);
            data.put("exceptions", exCnt);
            data.put("claims_paid", claimCnt);
            data.put("claims_amount", ReportJson.num(ReportJson.round2(claimAmount)));
            data.put("bills", billCnt);
            data.put("bill_amount", ReportJson.num(ReportJson.round2(billAmount)));
            data.put("bill_paid", ReportJson.num(ReportJson.round2(billPaid)));
            data.put("by_product", byProductOut);
            data.put("by_status", byStatusOut);
            data.put("by_exception", byExceptionOut);
            return ResponseEntity.ok(Map.of("ok", true, "data", data));
        } catch (Down d) {
            return degraded(d.service);
        }
    }

    /* ---------------- ⑩ 报表数据集（导出与预览共用） ---------------- */

    private record Dataset(String title, List<String> cols, List<List<Object>> rows) {}

    private static Object nz(Object v, Object dflt) {
        return v == null ? dflt : v;
    }

    private Dataset dataset(String type, String period) {
        switch (type) {
            case "performance": {
                Map<String, Object> perf = buildPerformance(period);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rows = (List<Map<String, Object>>) perf.get("rows");
                List<List<Object>> out = new ArrayList<>();
                for (Map<String, Object> r : rows) {
                    List<Object> row = new ArrayList<>();
                    row.add(r.get("rank"));
                    row.add(r.get("code"));
                    row.add(r.get("courier"));
                    row.add(nz(r.get("grid_code"), ""));
                    row.add(r.get("pickups"));
                    row.add(r.get("deliveries"));
                    row.add(r.get("exceptions"));
                    row.add(nz(r.get("on_time_rate"), ""));
                    row.add(r.get("score"));
                    out.add(row);
                }
                return new Dataset("快递员揽派绩效排名",
                        List.of("排名", "工号", "快递员", "片区", "揽收数", "签收数", "异常数", "准时率(%)", "综合得分"), out);
            }
            case "waybills": {
                List<Map<String, Object>> all = new ArrayList<>(fetchWaybills());
                all.sort((a, b) -> Long.compare( // ORDER BY w.id DESC
                        ReportJson.asLong(b.get("id")), ReportJson.asLong(a.get("id"))));
                List<List<Object>> out = new ArrayList<>();
                for (Map<String, Object> w : all) {
                    if (period != null && !period.isEmpty() && !inMonth(w.get("created_at"), period)) continue;
                    String signedAt = ReportJson.asString(w.get("signed_at"));
                    String promise = ReportJson.asString(w.get("promise_time"));
                    List<Object> row = new ArrayList<>();
                    row.add(w.get("waybill_no"));
                    row.add(nz(w.get("order_no"), ""));
                    row.add(nz(w.get("product"), ""));
                    row.add(w.get("status"));
                    row.add(nz(w.get("recv_name"), ""));
                    row.add(nz(w.get("recv_addr"), ""));
                    row.add(nz(w.get("fee"), 0));
                    row.add(nz(w.get("promise_time"), ""));
                    row.add(nz(w.get("signed_at"), ""));
                    row.add(signedAt != null && promise != null
                            ? (signedAt.compareTo(promise) <= 0 ? "准时" : "超时") : "—");
                    out.add(row);
                }
                return new Dataset("运单明细",
                        List.of("运单号", "订单号", "产品", "状态", "收件人", "收件地址", "运费", "承诺送达", "签收时间", "是否准时"), out);
            }
            case "bills": {
                Map<Long, String> custNames = new LinkedHashMap<>();
                for (Map<String, Object> c : fetchCustomers()) {
                    Long id = ReportJson.asLong(c.get("id"));
                    if (id != null) custNames.put(id, ReportJson.asString(c.get("name")));
                }
                List<Map<String, Object>> all = new ArrayList<>(fetchBills());
                all.sort((a, b) -> Long.compare(
                        ReportJson.asLong(b.get("id")), ReportJson.asLong(a.get("id"))));
                List<List<Object>> out = new ArrayList<>();
                for (Map<String, Object> b : all) {
                    if (period != null && !period.isEmpty() && !period.equals(ReportJson.asString(b.get("period")))) continue;
                    double amount = ReportJson.jsNumber(b.get("amount"));
                    double paid = ReportJson.jsNumber(b.get("paid_amount"));
                    Long cid = ReportJson.asLong(b.get("customer_id"));
                    List<Object> row = new ArrayList<>();
                    row.add(b.get("bill_no"));
                    row.add(cid == null ? null : custNames.get(cid));
                    row.add(b.get("period"));
                    row.add(b.get("amount"));
                    row.add(b.get("paid_amount"));
                    row.add(ReportJson.num(ReportJson.round2(amount - paid)));
                    row.add(b.get("status"));
                    out.add(row);
                }
                return new Dataset("月结账单",
                        List.of("账单号", "客户", "账期", "金额", "已收", "待收", "状态"), out);
            }
            case "exceptions": {
                // LEFT JOIN claims：理赔按 id 升序（原版表扫描序）逐行展开
                Map<Long, List<Map<String, Object>>> claimsByEx = new LinkedHashMap<>();
                List<Map<String, Object>> claims = new ArrayList<>(fetchClaims());
                claims.sort((a, b) -> Long.compare(
                        ReportJson.asLong(a.get("id")), ReportJson.asLong(b.get("id"))));
                for (Map<String, Object> c : claims) {
                    Long eid = ReportJson.asLong(c.get("exception_id"));
                    if (eid != null) claimsByEx.computeIfAbsent(eid, k -> new ArrayList<>()).add(c);
                }
                List<Map<String, Object>> all = new ArrayList<>(fetchExceptions());
                all.sort((a, b) -> Long.compare(
                        ReportJson.asLong(b.get("id")), ReportJson.asLong(a.get("id"))));
                List<List<Object>> out = new ArrayList<>();
                for (Map<String, Object> e : all) {
                    if (period != null && !period.isEmpty() && !inMonth(e.get("created_at"), period)) continue;
                    Long eid = ReportJson.asLong(e.get("id"));
                    List<Map<String, Object>> cs = eid == null ? List.of() : claimsByEx.getOrDefault(eid, List.of());
                    if (cs.isEmpty()) {
                        out.add(exRow(e, null));
                    } else {
                        for (Map<String, Object> c : cs) out.add(exRow(e, c));
                    }
                }
                return new Dataset("异常件与理赔",
                        List.of("异常单号", "运单号", "类型", "异常状态", "描述", "理赔金额", "理赔状态", "登记时间"), out);
            }
            case "transit": {
                List<Map<String, Object>> all = new ArrayList<>(fetchTransitLogs());
                all.sort((a, b) -> Long.compare(
                        ReportJson.asLong(b.get("id")), ReportJson.asLong(a.get("id"))));
                List<List<Object>> out = new ArrayList<>();
                for (Map<String, Object> t : all) {
                    if (period != null && !period.isEmpty() && !inMonth(t.get("created_at"), period)) continue;
                    List<Object> row = new ArrayList<>();
                    row.add(t.get("waybill_no"));
                    row.add(t.get("node"));
                    row.add(nz(t.get("next_hop"), ""));
                    row.add(nz(t.get("flight"), ""));
                    row.add(nz(t.get("operator"), ""));
                    row.add(t.get("created_at"));
                    out.add(row);
                }
                return new Dataset("分拣中转登记",
                        List.of("运单号", "节点", "下一跳", "航班", "操作员", "登记时间"), out);
            }
            default:
                return null;
        }
    }

    private static List<Object> exRow(Map<String, Object> e, Map<String, Object> c) {
        List<Object> row = new ArrayList<>();
        row.add(e.get("id"));
        row.add(e.get("waybill_no"));
        row.add(e.get("type"));
        row.add(e.get("status"));
        row.add(nz(e.get("desc_text"), ""));
        row.add(c == null ? "" : nz(c.get("amount"), ""));
        row.add(c == null ? "" : nz(c.get("status"), ""));
        row.add(e.get("created_at"));
        return row;
    }

    @GetMapping("/reports/preview")
    public ResponseEntity<?> preview(@RequestParam(name = "type", required = false) String type,
                                     @RequestParam(name = "period", required = false) String period,
                                     HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> denied = allowOpsFinance(req);
        if (denied != null) return denied;
        try {
            Dataset d = dataset(type == null ? "performance" : type, period);
            if (d == null) return fail("未知报表类型", HttpStatus.BAD_REQUEST);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("title", d.title());
            data.put("cols", d.cols());
            data.put("rows", d.rows());
            data.put("total", d.rows().size());
            return ResponseEntity.ok(Map.of("ok", true, "data", data));
        } catch (Down d) {
            return degraded(d.service);
        }
    }

    /* ---------------- ⑩ 导出 CSV / Excel ---------------- */

    private static String csvCell(Object v) {
        String s = ReportJson.jsStr(v);
        return s.contains(",") || s.contains("\"") || s.contains("\n")
                ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    private static String esc(Object v) {
        return ReportJson.jsStr(v).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @GetMapping("/reports/export")
    public ResponseEntity<?> export(@RequestParam(name = "type", required = false) String type,
                                    @RequestParam(name = "period", required = false) String period,
                                    @RequestParam(name = "format", required = false) String format,
                                    HttpServletRequest req) {
        ResponseEntity<Map<String, Object>> denied = allowOpsFinance(req);
        if (denied != null) return denied;
        String t = type == null ? "performance" : type;
        String fmt = (format == null ? "csv" : format).toLowerCase();
        Dataset d;
        try {
            d = dataset(t, period);
        } catch (Down down) {
            return degraded(down.service);
        }
        if (d == null) return fail("未知报表类型", HttpStatus.BAD_REQUEST);

        String stamp = LocalDate.now(ZoneOffset.UTC).toString(); // 对应 new Date().toISOString().slice(0,10)
        String fname = t + "-" + stamp;
        HttpHeaders headers = new HttpHeaders();

        if ("xls".equals(fmt) || "excel".equals(fmt)) {
            // 不引入依赖：输出 Excel 可直接打开的 HTML 表格（与原版模板逐字一致）
            headers.set(HttpHeaders.CONTENT_TYPE, "application/vnd.ms-excel; charset=utf-8");
            headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fname + ".xls\"");
            StringBuilder thead = new StringBuilder();
            for (String c : d.cols()) thead.append("<th>").append(esc(c)).append("</th>");
            StringBuilder tbody = new StringBuilder();
            for (List<Object> r : d.rows()) {
                tbody.append("<tr>");
                for (Object c : r) tbody.append("<td>").append(esc(c)).append("</td>");
                tbody.append("</tr>");
            }
            String body = "\uFEFF<html><head><meta charset=\"utf-8\"><style>table{border-collapse:collapse}\n"
                    + "td,th{border:1px solid #999;padding:4px 8px;font-family:sans-serif;font-size:12px}\n"
                    + "th{background:#eef2f7}</style></head><body><h3>" + esc(d.title()) + "</h3>\n"
                    + "<table><thead><tr>" + thead + "</tr></thead>"
                    + "<tbody>" + tbody + "</tbody></table></body></html>";
            return new ResponseEntity<>(body, headers, HttpStatus.OK);
        }

        headers.set(HttpHeaders.CONTENT_TYPE, "text/csv; charset=utf-8");
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fname + ".csv\"");
        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append(joinCsv(d.cols())).append("\n");
        for (List<Object> r : d.rows()) sb.append(joinCsv(r)).append("\n");
        return new ResponseEntity<>(sb.toString(), headers, HttpStatus.OK);
    }

    private static String joinCsv(List<?> cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(csvCell(cells.get(i)));
        }
        return sb.toString();
    }

    /* ---------------- 工作台 ---------------- */

    @GetMapping("/board")
    public ResponseEntity<?> board(HttpServletRequest req) {
        if (role(req) == null) return fail("未登录或登录已过期", HttpStatus.UNAUTHORIZED);
        try {
            Map<String, Object> stats;
            try {
                stats = ReportJson.dataMap(taskClient.boardStats());
                if (stats == null) throw new Down("任务服务");
            } catch (Down d) { throw d; } catch (Exception e) { throw new Down("任务服务"); }

            long openExceptions = 0;
            for (Map<String, Object> e : fetchExceptions()) {
                String s = ReportJson.asString(e.get("status"));
                if ("待处理".equals(s) || "待审批".equals(s)) openExceptions++;
            }

            long inTransit = 0, signedMeasured = 0, onTimeCnt = 0;
            double revenue = 0;
            for (Map<String, Object> w : fetchWaybills()) {
                revenue += ReportJson.jsNumber(w.get("fee"));
                String s = ReportJson.asString(w.get("status"));
                if ("转运中".equals(s) || "派送中".equals(s)) inTransit++;
                String promise = ReportJson.asString(w.get("promise_time"));
                String signedAt = ReportJson.asString(w.get("signed_at"));
                if ("已签收".equals(s) && promise != null && signedAt != null) {
                    signedMeasured++;
                    if (signedAt.compareTo(promise) <= 0) onTimeCnt++;
                }
            }

            long unpaidBills = 0;
            for (Map<String, Object> b : fetchBills()) {
                if (!"已结清".equals(ReportJson.asString(b.get("status")))) unpaidBills++;
            }

            Map<String, Object> orderStats;
            try {
                orderStats = ReportJson.dataMap(orderClient.stats());
                if (orderStats == null) throw new Down("订单服务");
            } catch (Down d) { throw d; } catch (Exception e) { throw new Down("订单服务"); }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("todayPickups", stats.get("today_pickups"));
            data.put("todaySigns", stats.get("today_signs"));
            data.put("openExceptions", openExceptions);
            data.put("inTransit", inTransit);
            data.put("pendingOrders", orderStats.get("pending_orders"));
            data.put("unpaidBills", unpaidBills);
            data.put("revenue", ReportJson.num(ReportJson.round2(revenue)));
            data.put("onTimeRate", signedMeasured > 0
                    ? ReportJson.num(ReportJson.round1((onTimeCnt / (double) signedMeasured) * 100)) : null);
            return ResponseEntity.ok(Map.of("ok", true, "data", data));
        } catch (Down d) {
            return degraded(d.service);
        }
    }
}
