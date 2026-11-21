package com.aped.task;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * 任务服务内存数据（对应原版 employees / pickup_tasks / delivery_tasks / transit_logs /
 * routing_hubs 五表）。按全量计划：employees 归 task-service 运力侧（负载视图的数据源），
 * 种子与 db.js 全新库一致（含分拣员 S6001 先行插入的 id 序：S6001=1, K1001=2, K1002=3,
 * D2001=4, C3001=5, F4001=6, O5001=7，与 auth-service 账号种子的 employee_id 口径一致）。
 * 与前批次口径一致：先内存实现，落库留待后续批次。
 */
@Component
public class TaskStore {

    static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    static String now() {
        return LocalDateTime.now().format(FMT);
    }

    /** 等价于 JS 的 +v.toFixed(2)。 */
    static double round2(double v) {
        return new BigDecimal(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /** JSON 数值：整数值输出为整数（与 JS JSON.stringify(Number) 一致）。 */
    static Object num(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return (long) v;
        return v;
    }

    /* ---------------- 员工（employees） ---------------- */

    public record Employee(long id, String code, String name, String role, String gridCode) {}

    static final List<Employee> EMPLOYEES = List.of(
            new Employee(1, "S6001", "分拣员小吴", "sorter", null),
            new Employee(2, "K1001", "快递员小李", "courier", "G-HZ-BJ"),
            new Employee(3, "K1002", "快递员小王", "courier", "G-SH-PD"),
            new Employee(4, "D2001", "调度员老周", "dispatcher", null),
            new Employee(5, "C3001", "理赔专员小陈", "claims", null),
            new Employee(6, "F4001", "财务小赵", "finance", null),
            new Employee(7, "O5001", "运营小孙", "ops", null));

    static Employee employeeById(Long id) {
        if (id == null) return null;
        for (Employee e : EMPLOYEES) if (e.id() == id) return e;
        return null;
    }

    /* ---------------- 分拨枢纽（routing_hubs，按 code 序 = 原版 getHubs 的 ORDER BY code） ---------------- */

    public record Hub(String code, String name, String type, String province, String city, String coverage) {}

    static final List<Hub> HUBS = List.of(
            new Hub("CAN", "广州白云航空枢纽", "air", "广东省", "广州市", "广东,广西,海南,福建,湖南"),
            new Hub("CTU", "成都双流航空枢纽", "air", "四川省", "成都市", "四川,重庆,贵州,云南,西藏"),
            new Hub("HGH", "杭州萧山航空枢纽", "air", "浙江省", "杭州市", "浙江,上海,江苏,安徽,江西,福建"),
            new Hub("PEK", "北京首都航空枢纽", "air", "北京市", "北京市", "北京,天津,河北,山西,内蒙古,辽宁,吉林,黑龙江"),
            new Hub("SHA", "上海虹桥航空枢纽", "air", "上海市", "上海市", "上海,江苏,浙江,安徽,山东"),
            new Hub("XIY", "西安咸阳陆运枢纽", "land", "陕西省", "西安市", "陕西,甘肃,宁夏,青海,新疆"));

    /* ---------------- 揽收任务（pickup_tasks） ---------------- */

    static final class PickupTask {
        long id;
        long orderId;
        Long courierId;
        String status = "待接单";   // 待接单/已接单/已揽收/已回滚
        String assignedAt;
        String acceptedAt;
        String rollbackReason;
    }

    /* ---------------- 派件任务（delivery_tasks） ---------------- */

    static final class DeliveryTask {
        long id;
        String waybillNo;
        Long courierId;
        String receiver;
        String addr;
        String status = "待派送";   // 待派送/派送中/已签收/派送异常（原版无接口把任务置派送中，照原版）
        String signer;
        String signWay;
        String signedAt;
    }

    /* ---------------- 中转登记（transit_logs） ---------------- */

    static final class TransitLog {
        long id;
        String waybillNo;
        String node;
        String nextHop;
        String flight;
        Long operatorId;
        int security;
        String createdAt;
    }

    final List<PickupTask> pickupTasks = new ArrayList<>();
    final List<DeliveryTask> deliveryTasks = new ArrayList<>();
    final List<TransitLog> transitLogs = new ArrayList<>();

    private final AtomicLong pickupSeq = new AtomicLong(0);
    private final AtomicLong deliverySeq = new AtomicLong(0);
    private final AtomicLong transitSeq = new AtomicLong(0);

    /** 全店单一锁：多行写按原版 tx() 的串行语义处理（跨服务写链无分布式事务，同批次 3 口径）。 */
    final Object lock = new Object();

    long nextPickupId() { return pickupSeq.incrementAndGet(); }
    long nextDeliveryId() { return deliverySeq.incrementAndGet(); }
    long nextTransitId() { return transitSeq.incrementAndGet(); }

    PickupTask pickupById(long id) {
        for (PickupTask t : pickupTasks) if (t.id == id) return t;
        return null;
    }

    DeliveryTask deliveryById(long id) {
        for (DeliveryTask t : deliveryTasks) if (t.id == id) return t;
        return null;
    }

    /** 对应 v_courier_load 的 load：该快递员 status IN ('待接单','已接单') 的揽收任务数（派件任务不计，同原版视图）。 */
    long courierLoad(long employeeId) {
        long n = 0;
        for (PickupTask t : pickupTasks) {
            if (t.courierId != null && t.courierId == employeeId
                    && ("待接单".equals(t.status) || "已接单".equals(t.status))) {
                n++;
            }
        }
        return n;
    }
}
