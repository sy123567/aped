package com.aped.exception;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 异常件内存数据（对应原版 exceptions 表）+ 轨迹事件日志（对应原版 events 表中
 * 由异常/理赔流程写出的部分：异常登记/异常关闭/理赔受理/理赔驳回/理赔赔付）。
 * 批次 4 已落内部创建接口（派送异常转异常件，写入方在 task-service）；
 * 批次 5 补齐异常查询/定责试算/关闭与理赔联动所需的事件留痕。
 * 全量轨迹（下单/揽收/中转/签收等由其他服务写出的事件）归批次 6 track-service 统一，
 * 本日志只承载 claim.js 口径内写出的事件，与批次 4 口径一致。
 */
@Component
public class ExceptionStore {

    static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static String now() {
        return LocalDateTime.now().format(FMT);
    }

    /** JSON 数字输出：整数不带小数点（等价 JS 数字序列化），其余原样。 */
    static Object num(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return (long) v;
        return v;
    }

    static final class ExceptionRow {
        long id;
        String waybillNo;
        String type;      // 拒收/破损/丢失
        String photos;
        String descText;
        String status;    // 待处理/待审批/已理赔/已关闭
        String createdAt;
        String closedAt;
        String severity;  // 原版列默认 '中度'
    }

    /** 对应原版 events 表一行（waybill_no/order_no/node/desc_text/created_at）。 */
    static final class EventRow {
        long id;
        String waybillNo;
        String orderNo;
        String node;
        String descText;
        String createdAt;
    }

    final List<ExceptionRow> exceptions = new ArrayList<>();
    final List<EventRow> events = new ArrayList<>();
    private final AtomicLong seq = new AtomicLong(0);
    private final AtomicLong eventSeq = new AtomicLong(0);
    final Object lock = new Object();

    long nextId() {
        return seq.incrementAndGet();
    }

    ExceptionRow byId(long id) {
        for (ExceptionRow r : exceptions) if (r.id == id) return r;
        return null;
    }

    /** 对应 helpers.event()：写一条轨迹事件（须在 lock 内调用）。 */
    EventRow addEvent(String waybillNo, String node, String descText) {
        EventRow e = new EventRow();
        e.id = eventSeq.incrementAndGet();
        e.waybillNo = waybillNo == null ? "" : waybillNo;
        e.orderNo = null;
        e.node = node;
        e.descText = descText;
        e.createdAt = now();
        events.add(e);
        return e;
    }

    /** 某运单的全部事件（ORDER BY id，同原版详情查询）。 */
    List<EventRow> eventsOf(String waybillNo) {
        List<EventRow> out = new ArrayList<>();
        for (EventRow e : events) if (waybillNo != null && waybillNo.equals(e.waybillNo)) out.add(e);
        return out;
    }
}
