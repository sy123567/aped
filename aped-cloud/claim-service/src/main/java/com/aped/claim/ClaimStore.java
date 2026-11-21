package com.aped.claim;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 理赔单内存数据（对应原版 claims 表：id/exception_id/amount/rule/status/approved_by/paid_at
 * + 迁移追加的 evidence 列）。数据落库不在本批次范围，与既有批次口径一致（重启即重置）。
 */
@Component
public class ClaimStore {

    static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static String now() {
        return LocalDateTime.now().format(FMT);
    }

    /** JSON 数字输出：整数不带小数点（等价 JS 数字序列化），其余原样。 */
    static Object num(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return (long) v;
        return v;
    }

    static final class ClaimRow {
        long id;
        long exceptionId;
        double amount;
        String rule;
        String status;     // 待审批/已驳回/已赔付
        String approvedBy;
        String paidAt;
        String evidence;
    }

    final List<ClaimRow> claims = new ArrayList<>();
    private final AtomicLong seq = new AtomicLong(0);
    /** 全店单一锁：多行写按原版 tx() 的串行语义处理（跨服务写链无分布式事务，同批次 3/4 口径）。 */
    final Object lock = new Object();

    long nextId() {
        return seq.incrementAndGet();
    }

    ClaimRow byId(long id) {
        for (ClaimRow c : claims) if (c.id == id) return c;
        return null;
    }

    List<ClaimRow> byException(long exceptionId) {
        List<ClaimRow> out = new ArrayList<>();
        for (ClaimRow c : claims) if (c.exceptionId == exceptionId) out.add(c);
        return out;
    }
}
