package com.aped.exception;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定责与赔付核定规则引擎：原版 core/claim-engine.js 的逐项翻译，口径一字不改。
 * 先判「谁的责任」，再按「折损程度分档 × 保价额」算钱，未保价有运费倍数 + 硬顶上限。
 * 数值语义对齐 JS：中间量用 double，取整走 round2（等价 +x.toFixed(2)），
 * 模板串里的金额用 jsNum（JS 数字最小化表示：整数不带小数点）。
 */
public final class ClaimEngine {

    private ClaimEngine() {}

    static final class Sev {
        final double ratio;
        final String label;
        Sev(double ratio, String label) { this.ratio = ratio; this.label = label; }
    }

    /** 折损分档（原版 SEVERITY，键序即前端选项序）。 */
    static final Map<String, Sev> SEVERITY = new LinkedHashMap<>();
    static {
        SEVERITY.put("轻微", new Sev(0.2, "轻微（外观损伤、可修复）"));
        SEVERITY.put("中度", new Sev(0.4, "中度（部分功能受损）"));
        SEVERITY.put("严重", new Sev(0.7, "严重（基本不可用）"));
        SEVERITY.put("全损", new Sev(1.0, "全损（完全灭失）"));
    }

    /** 未保价赔偿上限：运费倍数与硬顶（原版常量）。 */
    static final int UNINSURED_FEE_MULTIPLE = 3;
    static final double UNINSURED_HARD_CAP = 300;
    /** 超过该金额需要二级审批。 */
    static final double SECOND_APPROVAL_THRESHOLD = 500;

    /** 等价 JS 的 +v.toFixed(2)：对 double 精确值四舍五入到 2 位。 */
    static double round2(double v) {
        return new BigDecimal(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /** 等价 JS 的 v.toFixed(2) 字符串。 */
    static String fixed2(double v) {
        return new BigDecimal(v).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** JS 模板串里 ${number} 的表示：整数不带小数点，其余用最短往返表示（Double.toString 近似）。 */
    static String jsNum(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        return Double.toString(v);
    }

    /**
     * 定责 + 核定（对应 assessClaim）。
     * severity 由调用方完成默认值链（severity ?? 异常单.severity ?? '中度'）后传入；
     * 未知 severity 键时系数回落中度，但返回的 severity 字段保持入参原样（同原版）。
     */
    public static Map<String, Object> assessClaim(String type, String severity, double insured,
                                                  double fee, String photos, boolean securityPassed) {
        List<String> rules = new ArrayList<>();
        Sev sev = SEVERITY.getOrDefault(severity, SEVERITY.get("中度"));
        boolean hasEvidence = photos != null && !photos.trim().isEmpty();

        // ---------- 1. 定责 ----------
        String liable;
        String liableLabel;

        if ("拒收".equals(type)) {
            liable = "customer";
            liableLabel = "非承运责任";
            rules.add("拒收属收件人意愿，不构成承运方赔偿事由");
        } else if ("丢失".equals(type)) {
            liable = "carrier";
            liableLabel = "承运方责任（全程灭失）";
            rules.add("快件已揽收但未签收，保管期内灭失，承运方承担全责");
        } else if ("破损".equals(type)) {
            if (securityPassed) {
                liable = "carrier";
                liableLabel = "承运方责任（已过安检，破损发生在承运环节）";
                rules.add("快件已通过安检，破损可排除航空运输前置原因，由承运方承担");
            } else {
                liable = "carrier";
                liableLabel = "承运方主责（未过安检，按操作不当认定）";
                rules.add("未登记安检，无法排除收运环节问题，按承运方主责认定");
            }
        } else {
            rules.add("未知异常类型「" + type + "」，转人工核定");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("liable", "unknown");
            out.put("liable_label", "待人工定责");
            out.put("ratio", 0);
            out.put("amount", 0);
            out.put("cap", 0);
            out.put("severity", severity);
            out.put("severity_label", sev.label);
            out.put("rules", rules);
            out.put("need_evidence", true);
            out.put("need_second_approval", false);
            return out;
        }

        // ---------- 2. 折损系数 ----------
        rules.add("折损程度「" + severity + "」→ 赔偿系数 " + jsNum(sev.ratio) + "（" + sev.label + "）");

        // ---------- 3. 计算金额 ----------
        double amount = 0;
        double cap = 0;

        if ("customer".equals(liable)) {
            amount = 0;
            cap = 0;
            rules.add("非承运责任，赔付金额为 0");
        } else if (insured > 0) {
            cap = insured;
            amount = round2(insured * sev.ratio);
            rules.add("已保价 ¥" + jsNum(insured) + "：赔付 = 保价额 × 折损系数 = ¥" + jsNum(amount)
                    + "（上限 ¥" + jsNum(cap) + "）");
        } else {
            double byFee = round2(fee * UNINSURED_FEE_MULTIPLE);
            cap = Math.min(byFee, UNINSURED_HARD_CAP);
            amount = round2(Math.min(cap, fee * UNINSURED_FEE_MULTIPLE * sev.ratio));
            rules.add("未保价：按运费 " + UNINSURED_FEE_MULTIPLE + " 倍且不超 ¥" + jsNum(UNINSURED_HARD_CAP)
                    + " 封顶 → 上限 ¥" + jsNum(cap));
            rules.add("未保价赔付 = min(上限, 运费×" + UNINSURED_FEE_MULTIPLE + "×折损系数) = ¥" + jsNum(amount));
        }

        // ---------- 4. 证据与审批门槛 ----------
        boolean needEvidence = false;
        if (!hasEvidence && amount > 0) {
            needEvidence = true;
            rules.add("缺少照片/证明材料：需补齐后才能赔付");
        }
        boolean needSecondApproval = amount > SECOND_APPROVAL_THRESHOLD;
        if (needSecondApproval) {
            rules.add("金额 ¥" + jsNum(amount) + " 超过 ¥" + jsNum(SECOND_APPROVAL_THRESHOLD) + "，需二级审批");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("liable", liable);
        out.put("liable_label", liableLabel);
        out.put("severity", severity);
        out.put("severity_label", sev.label);
        out.put("ratio", ExceptionStore.num(sev.ratio));
        out.put("insured", ExceptionStore.num(insured));
        out.put("cap", ExceptionStore.num(round2(cap)));
        out.put("amount", ExceptionStore.num(round2(amount)));
        out.put("need_evidence", needEvidence);
        out.put("need_second_approval", needSecondApproval);
        out.put("rules", rules);
        return out;
    }
}
