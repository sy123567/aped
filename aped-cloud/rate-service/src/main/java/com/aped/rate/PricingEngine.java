package com.aped.rate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计费引擎 —— 原版 core/pricing.js 的逐项翻译，计费口径一字不改：
 * 基础运费（首重 + 阶梯续重）× 时效系数 × 区域系数 + 燃油 8% + 保价 3‰ + 包装/超重/超长
 * − 月结协议折扣（只打运费+燃油），最后兜底最低一票 12 元；每一项单独成行可对账。
 *
 * 数值语义与 JS 对齐：所有中间量用 double；取整走 round2（对 double 的精确二进制值
 * 做四舍五入，等价于 JS 的 +x.toFixed(2)）；JSON 输出时整数值不带小数点（等价于
 * JS Number 的 JSON.stringify 形态），由 num() 统一处理。
 *
 * 运价卡（products）与规则（pricing_rules）为原版 db.js 种子的内存副本，
 * 与原版定版值一致（DB 中 pricing_rules 与 pricing.js 的 DEFAULT_RULES 相同）。
 */
public final class PricingEngine {

    /* ---------------- 基础数值工具（对齐 JS 语义） ---------------- */

    /** 等价于 JS 的 +v.toFixed(2)：对 double 精确值四舍五入到 2 位（ties away from zero）。 */
    static double round2(double v) {
        return new BigDecimal(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /** 等价于 JS 的 v.toFixed(n) 字符串（固定小数位）。 */
    static String fixed(double v, int n) {
        return new BigDecimal(v).setScale(n, RoundingMode.HALF_UP).toPlainString();
    }

    /** JS 模板字符串里的数字形态：整数不带小数，其余用最短表示。 */
    static String fmt(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        return Double.toString(v);
    }

    /** JSON 数值：整数值输出为整数（与 JS JSON.stringify(Number) 一致），其余保持 double。 */
    static Object num(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return (long) v;
        return v;
    }

    /* ---------------- 运价卡（原版 products 种子） ---------------- */

    public record Product(String code, String name, double coef, int promiseHours,
                          double firstKg, double firstPrice, double contPrice,
                          double volDivisor, int sort, int active) {}

    private static final Product FALLBACK =
            new Product(null, "隔日达", 1.0, 48, 1, 12, 4, 6000, 0, 1);

    // 批次3 起可变：PUT /rates/:code（原版 billing.js 的运价维护接口，表归 rate-service）会改它
    private static final List<Product> PRODUCTS = new ArrayList<>(List.of(
            new Product("P_ND", "次日达", 1.2, 24, 1, 12, 4, 6000, 1, 1),
            new Product("P_GD", "隔日达", 1.0, 48, 1, 12, 4, 6000, 2, 1),
            new Product("P_JJ", "经济件", 0.85, 72, 1, 10, 3.5, 6000, 3, 1)));

    /** 对应 helpers.js getRates()：active=1 按 sort 排序（种子即有序）。 */
    public static List<Product> getRates() {
        synchronized (PRODUCTS) {
            return new ArrayList<>(PRODUCTS);
        }
    }

    /** 对应 helpers.js findRate()：按名匹配，未命中回落“隔日达”，再回落 FALLBACK。 */
    public static Product findRate(String product) {
        for (Product p : getRates()) if (p.name().equals(product)) return p;
        for (Product p : getRates()) if ("隔日达".equals(p.name())) return p;
        return FALLBACK;
    }

    /** 按 code 查产品（对应原版 PUT /rates/:code 的 SELECT * FROM products WHERE code=?）。 */
    public static Product productByCode(String code) {
        for (Product p : getRates()) if (p.code() != null && p.code().equals(code)) return p;
        return null;
    }

    /**
     * 对应原版 PUT /rates/:code 的 UPDATE：只改 coef/first_kg/first_price/cont_price/
     * vol_divisor/promise_hours 六列，入参为 null 表示该列保持原值（原版 num(v, 原值) 口径）。
     * 产品不存在返回 null。
     */
    public static Product updateProduct(String code, Double coef, Double firstKg, Double firstPrice,
                                        Double contPrice, Double volDivisor, Integer promiseHours) {
        synchronized (PRODUCTS) {
            for (int i = 0; i < PRODUCTS.size(); i++) {
                Product p = PRODUCTS.get(i);
                if (p.code() == null || !p.code().equals(code)) continue;
                Product u = new Product(p.code(), p.name(),
                        coef == null ? p.coef() : coef,
                        promiseHours == null ? p.promiseHours() : promiseHours,
                        firstKg == null ? p.firstKg() : firstKg,
                        firstPrice == null ? p.firstPrice() : firstPrice,
                        contPrice == null ? p.contPrice() : contPrice,
                        volDivisor == null ? p.volDivisor() : volDivisor,
                        p.sort(), p.active());
                PRODUCTS.set(i, u);
                return u;
            }
            return null;
        }
    }

    /** GET /rates 的整行形态（列序同 products 表定义）。 */
    static Map<String, Object> productJson(Product p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", p.code());
        m.put("name", p.name());
        m.put("coef", num(p.coef()));
        m.put("promise_hours", p.promiseHours());
        m.put("first_kg", num(p.firstKg()));
        m.put("first_price", num(p.firstPrice()));
        m.put("cont_price", num(p.contPrice()));
        m.put("vol_divisor", num(p.volDivisor()));
        m.put("sort", p.sort());
        m.put("active", p.active());
        return m;
    }

    /* ---------------- 计费规则（原版 pricing_rules 种子 = DEFAULT_RULES） ---------------- */

    private static LinkedHashMap<String, Double> seedRules() {
        LinkedHashMap<String, Double> r = new LinkedHashMap<>();
        r.put("min_charge", 12.0);
        r.put("fuel_rate", 0.08);
        r.put("insured_rate", 0.003);
        r.put("pack_fee", 2.0);
        r.put("overweight_kg", 30.0);
        r.put("overweight_fee", 15.0);
        r.put("oversize_cm", 100.0);
        r.put("oversize_fee", 20.0);
        r.put("monthly_discount", 0.95);
        r.put("zone_same_city", 0.9);
        r.put("zone_same_province", 1.0);
        r.put("zone_region", 1.15);
        r.put("zone_cross", 1.35);
        r.put("zone_remote", 1.8);
        return r;
    }

    // 批次3 起可变：PUT /pricing/rules/:key（原版 billing.js 的规则维护接口，表归 rate-service）会改它；
    // computeFee/pricingMeta 每次读当前值，与原版每次查 pricing_rules 表等价。
    private static final LinkedHashMap<String, Double> RULES = seedRules();

    /** 规则备注（原版 pricing_rules.note，PUT /pricing/rules/:key 的响应要回带）。 */
    private static final Map<String, String> RULE_NOTES = Map.ofEntries(
            Map.entry("min_charge", "最低一票收费"),
            Map.entry("fuel_rate", "燃油附加费，按基础运费计提"),
            Map.entry("insured_rate", "保价费率（保价额×费率）"),
            Map.entry("pack_fee", "包装服务费"),
            Map.entry("overweight_kg", "超重阈值"),
            Map.entry("overweight_fee", "超重附加费"),
            Map.entry("oversize_cm", "超长阈值（单边）"),
            Map.entry("oversize_fee", "超长附加费"),
            Map.entry("monthly_discount", "月结协议折扣"),
            Map.entry("zone_same_city", "同城计费系数"),
            Map.entry("zone_same_province", "省内计费系数"),
            Map.entry("zone_region", "同大区（华东）计费系数"),
            Map.entry("zone_cross", "跨大区计费系数"),
            Map.entry("zone_remote", "偏远地区计费系数"));

    private static LinkedHashMap<String, Double> rules() {
        synchronized (RULES) {
            return new LinkedHashMap<>(RULES);
        }
    }

    /** 对应原版 PUT /pricing/rules/:key 的存在性检查（SELECT * FROM pricing_rules WHERE key=?）。 */
    public static boolean hasRule(String key) {
        synchronized (RULES) {
            return RULES.containsKey(key);
        }
    }

    public static String ruleNote(String key) {
        return RULE_NOTES.get(key);
    }

    /** 对应原版 UPDATE pricing_rules SET value=? WHERE key=?。 */
    public static void updateRule(String key, double value) {
        synchronized (RULES) {
            RULES.put(key, value);
        }
    }

    /* ---------------- 区域划分（pricing.js 逐字翻译） ---------------- */

    private static final List<String> PROVINCES = List.of(
            "北京", "天津", "河北", "山西", "内蒙古", "辽宁", "吉林", "黑龙江", "上海", "江苏", "浙江",
            "安徽", "福建", "江西", "山东", "河南", "湖北", "湖南", "广东", "广西", "海南", "重庆", "四川", "贵州",
            "云南", "西藏", "陕西", "甘肃", "青海", "宁夏", "新疆");
    private static final List<String> EAST = List.of("上海", "江苏", "浙江", "安徽", "福建", "江西", "山东");
    private static final List<String> REMOTE = List.of("西藏", "新疆", "青海", "宁夏", "内蒙古", "甘肃");
    private static final String ORIGIN_PROVINCE = "浙江";
    private static final String ORIGIN_CITY = "杭州";

    private record Zone(String zone, String label, String coefKey) {}

    private static String provinceOf(String addr) {
        for (String p : PROVINCES) if (addr.contains(p)) return p;
        return null;
    }

    private static Zone classifyZone(String addr) {
        String p = provinceOf(addr);
        if (p == null) return new Zone("cross", "未识别省份，按跨大区计", "zone_cross");
        if (p.equals(ORIGIN_PROVINCE) && addr.contains(ORIGIN_CITY)) {
            return new Zone("same_city", "同城（" + ORIGIN_CITY + "）", "zone_same_city");
        }
        if (p.equals(ORIGIN_PROVINCE)) return new Zone("same_province", "省内", "zone_same_province");
        if (REMOTE.contains(p)) return new Zone("remote", "偏远（" + p + "）", "zone_remote");
        if (EAST.contains(p)) return new Zone("region", "同大区（华东·" + p + "）", "zone_region");
        return new Zone("cross", "跨大区（" + p + "）", "zone_cross");
    }

    /* ---------------- 阶梯续重（分段累进） ---------------- */

    private static final double[][] CONT_TIERS = {
            {10, 1.0}, {30, 0.9}, {100, 0.75}, {Double.POSITIVE_INFINITY, 0.6}};

    private record Seg(double from, double to, double coef, double amount) {}

    private record ContResult(double cost, List<Seg> segs) {}

    private static ContResult contCharged(double chargeKg, double firstKg, double contPrice) {
        double remain = Math.max(0, chargeKg - firstKg);
        double cursor = firstKg;
        double cost = 0;
        List<Seg> segs = new ArrayList<>();
        for (double[] tier : CONT_TIERS) {
            if (remain <= 0) break;
            double up = tier[0], coef = tier[1];
            double span = Math.min(remain, Math.max(0, up - cursor));
            if (span > 0) {
                double amount = round2(span * contPrice * coef);
                cost += amount;
                segs.add(new Seg(cursor, round2(cursor + span), coef, amount));
                remain -= span;
                cursor += span;
            }
        }
        return new ContResult(round2(cost), segs);
    }

    private static double sumItems(List<Map<String, Object>> items) {
        double s = 0;
        for (Map<String, Object> i : items) s += ((Number) i.get("amount")).doubleValue();
        return round2(s);
    }

    private static Map<String, Object> item(String code, String label, double amount, String rule) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("label", label);
        m.put("amount", num(amount));
        m.put("rule", rule);
        return m;
    }

    /* ---------------- 计费主函数（computeFee 逐项翻译） ---------------- */

    public record QuoteInput(String product, Double weight, Double volumeL, Double insured,
                             String destAddr, Double length, Double width, Double height,
                             boolean pack, Integer monthly) {}

    public static Map<String, Object> computeFee(QuoteInput in) {
        Map<String, Double> r = rules();
        Product rate = findRate(in.product());

        double weight = in.weight() == null ? 0 : in.weight();
        double volumeL = in.volumeL() == null ? 0 : in.volumeL();
        double insured = in.insured() == null ? 0 : in.insured();
        double length = in.length() == null ? 0 : in.length();
        double width = in.width() == null ? 0 : in.width();
        double height = in.height() == null ? 0 : in.height();
        String destAddr = in.destAddr() == null ? "" : in.destAddr();

        // 体积重量：优先长×宽×高(cm) → cm³ ÷ 体积除数；volumeL 为兼容入参（升）先换算 cm³
        double dimsCm3 = (length > 0 && width > 0 && height > 0) ? length * width * height : 0;
        double cm3 = dimsCm3 != 0 ? dimsCm3 : volumeL * 1000;
        double volWeight = (cm3 != 0 && rate.volDivisor() != 0) ? cm3 / rate.volDivisor() : 0;
        double maxSide = Math.max(length, Math.max(width, height));
        double rawKg = Math.max(weight, Math.max(volWeight, 0.01));
        long chargeKg = Math.max(1, (long) Math.ceil(rawKg - 1e-9)); // 进位到整公斤计费

        Zone zone = classifyZone(destAddr);
        double zoneCoef = r.getOrDefault(zone.coefKey(), 1.0);

        List<Map<String, Object>> items = new ArrayList<>();

        // 1) 基础运费 = (首重价 + 阶梯续重) × 时效系数 × 区域系数
        ContResult cont = contCharged(chargeKg, rate.firstKg(), rate.contPrice());
        double base = round2((rate.firstPrice() + cont.cost()) * rate.coef() * zoneCoef);
        String tierNote;
        if (cont.segs().isEmpty()) {
            tierNote = "无续重";
        } else {
            List<String> parts = new ArrayList<>();
            for (Seg s : cont.segs()) {
                parts.add(fmt(s.from()) + "~" + fmt(s.to()) + "kg×¥" + fmt(rate.contPrice()) + "×" + fmt(s.coef()));
            }
            tierNote = String.join(" + ", parts);
        }
        String freightRule = "首重" + fmt(rate.firstKg()) + "kg×¥" + fmt(rate.firstPrice())
                + " + 续重(" + tierNote + ") ×时效" + fmt(rate.coef()) + " ×区域" + fmt(zoneCoef)
                + (volWeight > weight ? "（体积重" + fixed(volWeight, 2) + "kg 取大）" : "");
        items.add(item("freight", "基础运费（" + rate.name() + "）", base, freightRule));

        // 2) 燃油附加费
        double fuel = round2(base * r.get("fuel_rate"));
        if (fuel > 0) {
            items.add(item("fuel", "燃油附加费", fuel,
                    "基础运费 × " + fixed(r.get("fuel_rate") * 100, 1) + "%"));
        }

        // 3) 保价费
        double insFee = insured > 0 ? round2(insured * r.get("insured_rate")) : 0;
        if (insFee > 0) {
            items.add(item("insured", "保价费", insFee,
                    "保价额 ¥" + fmt(insured) + " × " + fixed(r.get("insured_rate") * 1000, 1) + "‰"));
        }

        // 4) 包装服务费
        if (in.pack()) {
            items.add(item("pack", "包装服务费", r.get("pack_fee"), "按票固定收费"));
        }

        // 5) 超重附加
        if (chargeKg > r.get("overweight_kg")) {
            items.add(item("overweight", "超重附加费", r.get("overweight_fee"),
                    "计费重 " + chargeKg + "kg 超过 " + fmt(r.get("overweight_kg")) + "kg"));
        }

        // 6) 超长附加（取三边中最长边判定）
        if (maxSide != 0 && maxSide > r.get("oversize_cm")) {
            items.add(item("oversize", "超长附加费", r.get("oversize_fee"),
                    "最长边 " + fmt(maxSide) + "cm 超过 " + fmt(r.get("oversize_cm")) + "cm"));
        }

        double subtotal = sumItems(items);

        // 7) 月结协议折扣（只对运费类打折，不对保价费打折）
        double total = subtotal;
        int monthly = in.monthly() == null ? 0 : in.monthly();
        if (monthly != 0 && r.get("monthly_discount") < 1) {
            List<Map<String, Object>> freightFuel = new ArrayList<>();
            for (Map<String, Object> i : items) {
                if ("freight".equals(i.get("code")) || "fuel".equals(i.get("code"))) freightFuel.add(i);
            }
            double discountBase = sumItems(freightFuel);
            double cut = round2(discountBase * (1 - r.get("monthly_discount")));
            if (cut > 0) {
                items.add(item("discount", "月结协议折扣", -cut,
                        "运费与燃油 × " + fixed((1 - r.get("monthly_discount")) * 100, 0) + "% 折让"));
                total = sumItems(items);
            }
        }

        // 8) 最低一票
        if (total < r.get("min_charge")) {
            items.add(item("min_charge", "最低一票补差", round2(r.get("min_charge") - total),
                    "最低收费 ¥" + fmt(r.get("min_charge"))));
            total = r.get("min_charge");
        }

        List<String> detailParts = new ArrayList<>();
        for (Map<String, Object> i : items) {
            detailParts.add(i.get("label") + " ¥" + fmt(((Number) i.get("amount")).doubleValue()));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("product", rate.name());
        out.put("chargeKg", chargeKg);
        out.put("volWeight", num(round2(volWeight)));
        if (dimsCm3 != 0) {
            Map<String, Object> dims = new LinkedHashMap<>();
            dims.put("length", num(length));
            dims.put("width", num(width));
            dims.put("height", num(height));
            out.put("dims", dims);
        } else {
            out.put("dims", null);
        }
        out.put("rawKg", num(round2(rawKg)));
        out.put("zone", zone.zone());
        out.put("zone_label", zone.label());
        out.put("zone_coef", num(zoneCoef));
        out.put("items", items);
        out.put("total", num(round2(total)));
        out.put("detail", String.join(" + ", detailParts));
        return out;
    }

    /* ---------------- 阶梯/规则展示（pricingMeta 翻译） ---------------- */

    public static Map<String, Object> pricingMeta() {
        Map<String, Double> r = rules();
        Map<String, Object> rulesJson = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : r.entrySet()) rulesJson.put(e.getKey(), num(e.getValue()));

        List<Map<String, Object>> tiers = new ArrayList<>();
        for (double[] t : CONT_TIERS) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("up_to_kg", Double.isInfinite(t[0]) ? null : num(t[0]));
            m.put("coef", num(t[1]));
            tiers.add(m);
        }

        List<Map<String, Object>> zones = new ArrayList<>();
        zones.add(zoneJson("same_city", "同城", r.get("zone_same_city")));
        zones.add(zoneJson("same_province", "省内", r.get("zone_same_province")));
        zones.add(zoneJson("region", "同大区", r.get("zone_region")));
        zones.add(zoneJson("cross", "跨大区", r.get("zone_cross")));
        zones.add(zoneJson("remote", "偏远", r.get("zone_remote")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rules", rulesJson);
        out.put("tiers", tiers);
        out.put("zones", zones);
        out.put("note", "基础运费 =（首重 + 阶梯续重）× 时效系数 × 区域系数；再叠加燃油、保价、包装、超重超长，最后按协议折扣并兜底最低一票。");
        return out;
    }

    private static Map<String, Object> zoneJson(String key, String label, double coef) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("coef", num(coef));
        return m;
    }
}
