package com.aped.report;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 报表服务独占数据：kpi_weights（按全量计划归 report-service）。
 * 种子照原版 db.js 全新库：四指标权重合计恰为 1；getWeights() 对应原版 kpi.js——
 * 按 weight 降序取行，权重和不为 1 时按比例归一到 1（此处种子和为 1，归一后不变）。
 * 与前批次口径一致：先内存实现，落库留待后续批次。
 */
@Component
public class ReportStore {

    public record Weight(String metric, String label, double weight, int direction, String note) {}

    static final List<Weight> SEED = List.of(
            new Weight("pickups", "揽收量", 0.20, 1, "统计期内已揽收任务数"),
            new Weight("deliveries", "签收量", 0.30, 1, "统计期内已签收任务数"),
            new Weight("on_time", "准时率", 0.35, 1, "承诺时效内签收 ÷ 签收总数"),
            new Weight("exception", "异常率", 0.15, -1, "派送异常 ÷ 作业总量（逆向指标）"));

    /** 对应 kpi.js getWeights()：ORDER BY weight DESC + 按比例归一。 */
    static List<Weight> getWeights() {
        List<Weight> rows = new ArrayList<>(SEED);
        rows.sort((a, b) -> Double.compare(b.weight(), a.weight()));
        double sum = rows.stream().mapToDouble(Weight::weight).sum();
        List<Weight> out = new ArrayList<>();
        for (Weight w : rows) {
            double nw = sum > 0 ? w.weight() / sum : 1.0 / rows.size();
            out.add(new Weight(w.metric(), w.label(), nw, w.direction(), w.note()));
        }
        return out;
    }
}
