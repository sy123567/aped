package com.aped.notify;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 通知服务内存数据（通知记录）。按全量计划：notify-service 为新增模块（原 Node 版无此模块，
 * 按仓库架构文档的横切设计补），独占通知记录；各业务服务在状态变更时经内部接口写入，
 * 写入失败不阻断主流程（计划矩阵口径）。与前批次一致：先内存实现，落库留待后续批次。
 */
@Component
public class NotifyStore {

    static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static String now() {
        return LocalDateTime.now().format(FMT);
    }

    static final class Notice {
        long id;
        String type;       // 事件类型：order_created / waybill_signed / claim_paid 等
        String refNo;      // 关联单号（订单号/运单号/理赔单号）
        String content;    // 通知文案
        String recipient;  // 接收方标识（可空，如客户号/角色）
        String createdAt;
        boolean read;
    }

    final List<Notice> notices = new ArrayList<>();
    private final AtomicLong seq = new AtomicLong(0);
    final Object lock = new Object();

    long nextId() {
        return seq.incrementAndGet();
    }

    Notice byId(long id) {
        for (Notice n : notices) if (n.id == id) return n;
        return null;
    }
}
