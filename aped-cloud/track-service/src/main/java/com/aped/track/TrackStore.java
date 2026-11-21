package com.aped.track;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 轨迹服务内存数据（对应原版 events 表，按全量计划归 track-service 独占）。
 * 批次 2–5 一直 deferred 的轨迹写入在本批次收口：下单/取消（order）、揽派全流程（task）、
 * 异常与理赔（exception/claim）产生的事件统一经 POST /internal/events 写到本服务，
 * 与原版 helpers.event() 的单库写入等价（waybill_no 缺省存空串、order_no 可空）。
 * 与前批次口径一致：先内存实现，落库留待后续批次。
 */
@Component
public class TrackStore {

    static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static String now() {
        return LocalDateTime.now().format(FMT);
    }

    /** 对应原版 events 表一行。 */
    static final class EventRow {
        long id;
        String waybillNo;   // 原版 event() 以 waybill_no ?? '' 落库
        String orderNo;     // 可空（运单维度事件无订单号）
        String node;
        String descText;
        String createdAt;
    }

    final List<EventRow> events = new ArrayList<>();
    private final AtomicLong seq = new AtomicLong(0);
    final Object lock = new Object();

    EventRow addEvent(String waybillNo, String orderNo, String node, String descText) {
        EventRow e = new EventRow();
        e.id = seq.incrementAndGet();
        e.waybillNo = waybillNo == null ? "" : waybillNo;
        e.orderNo = orderNo;
        e.node = node;
        e.descText = descText;
        e.createdAt = now();
        events.add(e);
        return e;
    }
}
