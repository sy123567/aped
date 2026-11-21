package com.aped.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 账单服务内存数据（对应原版 bills / bill_items / receipts 三表 + waybills 计费快照表）。
 * 与批次 1/2 口径一致：先内存实现、种子与原版一致，落库留待后续批次。
 * 运单快照由揽收环节经内部接口写入（原版为 billing.js 的 createWaybillForOrder，
 * 由 dispatch 揽收登记调用；微服务版由 task-service 在批次 4 接入，本批次先落内部接口）。
 */
@Component
public class BillingStore {

    static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /* ---------------- JS 数值语义（与 rate-service PricingEngine 同口径） ---------------- */

    /** 等价于 JS 的 +v.toFixed(2)。 */
    static double round2(double v) {
        return new BigDecimal(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /** JS 模板字符串里的数字形态：整数不带小数，其余最短表示。 */
    static String fmt(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        return Double.toString(v);
    }

    /** JSON 数值：整数值输出为整数（与 JS JSON.stringify(Number) 一致）。 */
    static Object num(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return (long) v;
        return v;
    }

    static String now() {
        return LocalDateTime.now().format(FMT);
    }

    /* ---------------- 客户种子（与原版 db.js 一致；Feign 不可用时的兜底 + 运单计费查月结标记） ---------------- */

    public record Cust(long id, String name, String phone, String addr, int monthly) {}

    static final List<Cust> CUSTOMER_SEED = List.of(
            new Cust(1, "张三", "13800001111", "杭州市滨江区江陵路88号", 0),
            new Cust(2, "李四", "13900002222", "杭州市西湖区文三路100号", 1),
            new Cust(3, "王五", "13700003333", "上海市浦东新区张江路500号", 1));

    static Cust seedCustomer(Long id) {
        if (id == null) return null;
        for (Cust c : CUSTOMER_SEED) if (c.id() == id) return c;
        return null;
    }

    /* ---------------- 运单计费快照（原版 waybills 表中归 billing 的部分） ---------------- */

    static final class Waybill {
        long id;
        String waybillNo;
        Long orderId;
        String orderNo;
        Long customerId;
        String sender;
        String recvName;
        String recvAddr;
        String product;
        double firstKg;
        double firstPrice;
        double contPrice;
        double volDivisor;
        double insured;
        double fee;
        String feeDetail;
        String status;
        String createdAt;
        String promiseTime;
        String signedAt;
        String feeSnapshot;
        String destZone;
    }

    /* ---------------- 账单 / 明细 / 核销流水 ---------------- */

    static final class Bill {
        long id;
        String billNo;
        long customerId;
        String period;
        double amount;
        int itemCount;
        String status;
        String createdAt;
        double paidAmount;
        int receiptCount;
    }

    static final class BillItem {
        long id;
        long billId;
        String waybillNo;
        String product;
        double fee;
        String signedAt;
        int frozen;
    }

    static final class Receipt {
        long id;
        long billId;
        double amount;
        String paidAt;
        String voucher;
        String operator;
    }

    final List<Waybill> waybills = new ArrayList<>();
    final List<Bill> bills = new ArrayList<>();
    final List<BillItem> items = new ArrayList<>();
    final List<Receipt> receipts = new ArrayList<>();

    private final AtomicLong waybillSeq = new AtomicLong(0);
    private final AtomicLong billSeq = new AtomicLong(0);
    private final AtomicLong itemSeq = new AtomicLong(0);
    private final AtomicLong receiptSeq = new AtomicLong(0);

    /** 全店单一锁：出账/冻结/核销都是多行写，按原版 tx() 的串行语义处理。 */
    final Object lock = new Object();

    long nextWaybillId() { return waybillSeq.incrementAndGet(); }
    long nextBillId() { return billSeq.incrementAndGet(); }
    long nextItemId() { return itemSeq.incrementAndGet(); }
    long nextReceiptId() { return receiptSeq.incrementAndGet(); }

    /**
     * 对应 helpers.js nextNo(prefix, table, col, width)：
     * 前缀 + yymmdd + 定长流水，流水 = 当日同前缀已有单量 + 1，撞号则继续 +1。
     */
    String nextNo(String prefix, int width, List<String> existingNos) {
        String base = prefix + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMdd"));
        long count = existingNos.stream().filter(n -> n != null && n.startsWith(base)).count();
        long n = count + 1;
        String no;
        while (true) {
            no = base + String.format("%0" + width + "d", n);
            if (!existingNos.contains(no)) return no;
            n++;
        }
    }

    Waybill waybillByNo(String no) {
        for (Waybill w : waybills) if (w.waybillNo.equals(no)) return w;
        return null;
    }

    Bill billById(long id) {
        for (Bill b : bills) if (b.id == id) return b;
        return null;
    }

    BillItem itemById(long id) {
        for (BillItem i : items) if (i.id == id) return i;
        return null;
    }

    List<BillItem> itemsOf(long billId) {
        List<BillItem> out = new ArrayList<>();
        for (BillItem i : items) if (i.billId == billId) out.add(i);
        return out;
    }

    List<Receipt> receiptsOf(long billId) {
        List<Receipt> out = new ArrayList<>();
        for (Receipt r : receipts) if (r.billId == billId) out.add(r);
        return out;
    }
}
