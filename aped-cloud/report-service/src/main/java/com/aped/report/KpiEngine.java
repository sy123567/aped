package com.aped.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 绩效评分引擎：原版 core/kpi.js 的 computeKpi 逐项翻译（rawRows 的 SQL 聚合已按计划
 * 拆到 task-service 的 /internal/kpi-rows，本类消费其原始行完成归一化/加权/排名）。
 * 口径一字不改：① 各指标 min-max 归一化到 0~100（同值时 60 分）② 逆向指标取 100−归一化
 * ③ 加权得分 = Σ(得分×权重) ÷ Σ权重，缺失指标不计入分母 ④ 作业量低于 3 单不参与排名。
 * 数值语义对齐 JS：比率先乘 100 再 toFixed(1)，得分/贡献同理（见 ReportJson.round）。
 */
final class KpiEngine {

    private KpiEngine() {}

    static final int MIN_SAMPLE = 3;

    /**
     * @param raws task-service 返回的原始行：{id, code, courier, grid_code, pickups,
     *             deliveries, exceptions, measured, on_time_cnt}（员工表序）
     * @return 与 computeKpi() 返回同构的 Map：{weights, rows, ranked_count, min_sample, method}
     */
    static Map<String, Object> computeKpi(List<Map<String, Object>> raws) {
        List<ReportStore.Weight> weights = ReportStore.getWeights();

        // ---- 行组装：metrics / extras / sample_ok ----
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : raws) {
            long pickups = ReportJson.asLong(r.get("pickups")) == null ? 0 : ReportJson.asLong(r.get("pickups"));
            long deliveries = ReportJson.asLong(r.get("deliveries")) == null ? 0 : ReportJson.asLong(r.get("deliveries"));
            long exceptions = ReportJson.asLong(r.get("exceptions")) == null ? 0 : ReportJson.asLong(r.get("exceptions"));
            long measured = ReportJson.asLong(r.get("measured")) == null ? 0 : ReportJson.asLong(r.get("measured"));
            long onTimeCnt = ReportJson.asLong(r.get("on_time_cnt")) == null ? 0 : ReportJson.asLong(r.get("on_time_cnt"));
            long total = pickups + deliveries;

            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("pickups", pickups);
            metrics.put("deliveries", deliveries);
            metrics.put("on_time", measured != 0 ? ReportJson.num(ReportJson.round1((onTimeCnt / (double) measured) * 100)) : null);
            metrics.put("exception", total != 0 ? ReportJson.num(ReportJson.round1((exceptions / (double) total) * 100)) : null);

            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("exceptions", exceptions);
            extras.put("measured", measured);
            extras.put("on_time_cnt", onTimeCnt);
            extras.put("total", total);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.get("id"));
            row.put("code", r.get("code"));
            row.put("courier", r.get("courier"));
            row.put("grid_code", r.get("grid_code"));
            row.put("metrics", metrics);
            row.put("extras", extras);
            row.put("scores", new LinkedHashMap<String, Object>());
            row.put("sample_ok", total >= MIN_SAMPLE);
            rows.add(row);
        }

        // ---- 归一化（全体行参与，含不参加排名者，同原版） ----
        for (ReportStore.Weight w : weights) normalize(rows, w.metric(), w.direction());

        // ---- 加权得分 / 等级 / breakdown ----
        for (Map<String, Object> row : rows) {
            @SuppressWarnings("unchecked")
            Map<String, Object> metrics = (Map<String, Object>) row.get("metrics");
            @SuppressWarnings("unchecked")
            Map<String, Object> scores = (Map<String, Object>) row.get("scores");
            double sumW = 0, sumS = 0;
            for (ReportStore.Weight w : weights) {
                Object s = scores.get(w.metric());
                if (s == null) continue;
                double sv = ReportJson.jsNumber(s);
                sumW += w.weight();
                sumS += sv * w.weight();
            }
            Double score = sumW > 0 ? ReportJson.round1(sumS / sumW) : null;
            row.put("score", score == null ? null : ReportJson.num(score));
            row.put("grade", grade(score));

            List<Map<String, Object>> breakdown = new ArrayList<>();
            for (ReportStore.Weight w : weights) {
                Object s = scores.get(w.metric());
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("metric", w.metric());
                b.put("label", w.label());
                b.put("raw", metrics.get(w.metric()));
                b.put("score", s);
                b.put("weight", ReportJson.num(ReportJson.round3(w.weight())));
                b.put("contrib", s == null ? null
                        : ReportJson.num(ReportJson.round1(ReportJson.jsNumber(s) * w.weight())));
                breakdown.add(b);
            }
            row.put("breakdown", breakdown);
        }

        // ---- 排名与剔除（JS sort 稳定序 = Java List.sort 稳定序，同分保持员工表序） ----
        List<Map<String, Object>> ranked = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (Boolean.TRUE.equals(row.get("sample_ok")) && row.get("score") != null) ranked.add(row);
        }
        ranked.sort((a, b) -> Double.compare(
                ReportJson.jsNumber(b.get("score")), ReportJson.jsNumber(a.get("score"))));
        List<Map<String, Object>> rankedOut = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank", i + 1);
            m.putAll(ranked.get(i));
            rankedOut.add(m);
        }
        List<Map<String, Object>> excludedOut = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (Boolean.TRUE.equals(row.get("sample_ok")) && row.get("score") != null) continue;
            Map<String, Object> m = new LinkedHashMap<>(row);
            m.put("rank", null);
            @SuppressWarnings("unchecked")
            Map<String, Object> extras = (Map<String, Object>) row.get("extras");
            m.put("exclude_reason",
                    "作业样本 " + extras.get("total") + " 单，低于 " + MIN_SAMPLE + " 单不参与排名");
            excludedOut.add(m);
        }

        List<Map<String, Object>> all = new ArrayList<>(rankedOut);
        all.addAll(excludedOut);

        List<Map<String, Object>> weightsOut = new ArrayList<>();
        for (ReportStore.Weight w : weights) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("metric", w.metric());
            m.put("label", w.label());
            m.put("weight", ReportJson.num(ReportJson.round3(w.weight())));
            m.put("direction", w.direction());
            weightsOut.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("weights", weightsOut);
        out.put("rows", all);
        out.put("ranked_count", rankedOut.size());
        out.put("min_sample", MIN_SAMPLE);
        out.put("method", List.of(
                "各指标先做 min-max 归一化到 0~100，消除量纲差异与业务量季节波动",
                "逆向指标（异常率）取 100 − 归一化值后参与计算",
                "加权得分 = Σ(指标得分 × 权重) ÷ Σ权重，缺失指标不计入分母",
                "作业量低于 " + MIN_SAMPLE + " 单的快递员不参与排名，避免小样本失真"));
        return out;
    }

    /** min-max 归一化；direction=-1 为逆向指标。同原版 normalize()。 */
    private static void normalize(List<Map<String, Object>> rows, String key, int direction) {
        List<Double> vals = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            @SuppressWarnings("unchecked")
            Map<String, Object> metrics = (Map<String, Object>) row.get("metrics");
            Object v = metrics.get(key);
            if (v != null && !Double.isNaN(ReportJson.jsNumber(v))) vals.add(ReportJson.jsNumber(v));
        }
        if (vals.isEmpty()) return;
        double min = vals.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = vals.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        for (Map<String, Object> row : rows) {
            @SuppressWarnings("unchecked")
            Map<String, Object> metrics = (Map<String, Object>) row.get("metrics");
            @SuppressWarnings("unchecked")
            Map<String, Object> scores = (Map<String, Object>) row.get("scores");
            Object v = metrics.get(key);
            if (v == null || Double.isNaN(ReportJson.jsNumber(v))) {
                scores.put(key, null);
                continue;
            }
            double s = max == min ? 60 : ((ReportJson.jsNumber(v) - min) / (max - min)) * 100;
            if (direction == -1) s = 100 - s;
            scores.put(key, ReportJson.num(ReportJson.round1(s)));
        }
    }

    private static String grade(Double s) {
        if (s == null) return "—";
        return s >= 85 ? "A" : s >= 70 ? "B" : s >= 55 ? "C" : "D";
    }
}
