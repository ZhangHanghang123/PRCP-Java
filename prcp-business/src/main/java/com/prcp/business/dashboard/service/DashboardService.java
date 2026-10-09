package com.prcp.business.dashboard.service;

import com.prcp.business.dashboard.mapper.DashboardMapper;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>首页驾驶舱 + 反算驾驶舱 (PRD 风格) Service</p>
 *
 * <p>核心职责:
 * <ol>
 *   <li>总览 KPI 卡片 (8 项: 方案/节点/报表/指标/评分)</li>
 *   <li>KPI 趋势 (近 N 天)</li>
 *   <li>方案分布 + Top KPIs</li>
 *   <li>反算驾驶舱 (9 KPI 卡片 + 24 月趋势 + 大类分布 + 节点矩阵 + Top 节点 + 风险预警)</li>
 * </ol>
 * </p>
 *
 * <p>关键约定:
 * <ul>
 *   <li>5 监管指标: ROE / CET1 / LCR / NSFR / △EVE (代码 KPI_PNN_*)</li>
 *   <li>5 大类: 资产/负债/权益/表外/其他</li>
 *   <li>△EVE 显示单位"亿" (= /10000), 其他 4 个指标单位 "%"</li>
 *   <li>色彩规范: 主蓝 #5B8FF9, DEVE 用橙色 #F6903D (预警色)</li>
 *   <li>数据日期: 缺省用 balance 表最新日期</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.dashboard.mapper.DashboardMapper
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final DashboardMapper dashboardMapper;

    /**
     * <p>总览 KPI 卡片 (8 项统计 + 最新数据日期)</p>
     *
     * @return R.ok(Map.of("kpi", list, "latest_data_date", "yyyy-MM-dd"))
     */
    public R<Map<String, Object>> overview() {
        Map<String, Object> raw = dashboardMapper.overview();
        List<Map<String, Object>> kpi = new ArrayList<>();
        kpi.add(card("账户册方案", raw.get("schemes"), "个", "#C7000B"));
        kpi.add(card("账户册节点", raw.get("coa_nodes"), "个", "#A31A1F"));
        kpi.add(card("报表模板", raw.get("reports"), "个", "#E84E4E"));
        kpi.add(card("报表表项", raw.get("rpt_items"), "项", "#D71B1B"));
        kpi.add(card("指标方案", raw.get("kpi_schemes"), "个", "#C8102E"));
        kpi.add(card("指标定义", raw.get("kpi_defs"), "个", "#DC2626"));
        kpi.add(card("指标值", raw.get("kpi_values"), "条", "#991B1B"));
        kpi.add(card("评分规则", raw.get("score_rules"), "个", "#7F1D1D"));

        Object latest = raw.get("latest_date");
        String latestStr = null;
        if (latest instanceof java.sql.Date d) latestStr = d.toLocalDate().toString();
        else if (latest instanceof LocalDate d) latestStr = d.toString();

        Map<String, Object> out = new HashMap<>();
        out.put("kpi", kpi);
        out.put("latest_data_date", latestStr);
        return R.ok(out);
    }

    private Map<String, Object> card(String label, Object value, String unit, String color) {
        Map<String, Object> m = new HashMap<>();
        m.put("label", label);
        m.put("value", value == null ? 0 : value);
        m.put("unit", unit);
        m.put("color", color);
        return m;
    }

    /**
     * <p>KPI 趋势 (近 N 天)</p>
     *
     * @param days 趋势天数 (必填)
     * @return R.ok(Map.of("items", list))
     */
    public R<Map<String, Object>> kpiTrend(int days) {
        Map<String, Object> out = new HashMap<>();
        out.put("items", dashboardMapper.kpiTrend(days));
        return R.ok(out);
    }

    /**
     * <p>方案分布 (按方案汇总节点数/记录数)</p>
     *
     * @return R.ok(Map.of("items", list))
     */
    public R<Map<String, Object>> schemeDistribution() {
        Map<String, Object> out = new HashMap<>();
        out.put("items", dashboardMapper.schemeDistribution());
        return R.ok(out);
    }

    /**
     * <p>Top KPIs (按重要性排序的指标列表)</p>
     *
     * @return R.ok(Map.of("items", list))
     */
    public R<Map<String, Object>> topKpis() {
        Map<String, Object> out = new HashMap<>();
        out.put("items", dashboardMapper.topKpis());
        return R.ok(out);
    }

    // ============ 反算驾驶舱（PRD 风格 9 KPI + 24 月趋势 + 大类分布） ============

    /**
     * <p>反算驾驶舱 (PRD 风格: 9 KPI + 24 月趋势 + 大类分布 + 节点矩阵 + Top 节点 + 风险预警)</p>
     *
     * <p>9 KPI 卡片: 账户册节点 / 已结指标 / 资产负债 / 负债余额 + 5 个监管指标 (ROE/CET1/LCR/NSFR/△EVE)</p>
     *
     * @param dataDate 数据日期 yyyy-MM-dd (可选, 空时用 balance 最新日期)
     * @return 复合 Map: kpis, trend (24 月), distribution (环形+柱状), filterOptions, nodeMatrix, topNodes, riskAlerts
     */
    public R<Map<String, Object>> reverseOverview(String dataDate) {
        Map<String, Object> raw = dashboardMapper.reverseKpiSummary();
        Map<String, Map<String, Object>> indi = new LinkedHashMap<>();
        for (Map<String, Object> r : dashboardMapper.keyIndicatorValues()) {
            indi.put(String.valueOf(r.get("code")), r);
        }

        // 数字处理（统一保留 2 位小数）
        long coaNodes    = toLong(raw.get("coa_nodes"));
        long kpiDefs     = toLong(raw.get("kpi_defs"));
        long scored      = toLong(raw.get("scored"));
        double assetAmt  = toDouble(raw.get("asset_amt"));
        double liabAmt   = toDouble(raw.get("liability_amt"));
        double equityAmt = toDouble(raw.get("equity_amt"));
        double offAmt    = toDouble(raw.get("off_balance_amt"));

        // 日期（balance 最新日期）
        String balanceDate = strOf(raw.get("balance_date"));

        // ===== 9 个 KPI 卡片 =====
        List<Map<String, Object>> kpis = new ArrayList<>();

        // 1. 账户册节点
        kpis.add(kpiCard("账户册节点", coaNodes, "个", "up", "#667eea", null, null));
        // 2. 已结指标（KPI_PNN_* 5 个里面已评分数 vs 5）
        kpis.add(kpiCard("已结指标", scored, "", "flat", "#764ba2", 5, "/5"));
        // 3. 资产负债（资产 + 负债 / 10000 转万）
        double totalAandL = assetAmt + liabAmt;
        kpis.add(kpiCard("资产负债", round2(totalAandL / 10000.0), "万", "up", "#1890ff", null, null));
        // 4. 负债余额
        kpis.add(kpiCard("负债余额", round2(liabAmt / 10000.0), "万", "down", "#fa8c16", null, null));
        // 5-9. 5 个监管指标（直接从 kpi_value 取最新值）
        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("KPI_PNN_ROE",   "ROE 净资产收益率");
        cfg.put("KPI_PNN_CET1",  "CET1 核心一级");
        cfg.put("KPI_PNN_LCR",   "LCR 流动性覆盖率");
        cfg.put("KPI_PNN_NSFR",  "NSFR 净稳定资金");
        cfg.put("KPI_PNN_DEVE",  "△EVE 利率风险");

        for (Map.Entry<String, String> e : cfg.entrySet()) {
            Map<String, Object> v = indi.get(e.getKey());
            Object val = v == null ? null : v.get("value");
            double dVal = val == null ? 0.0 : toDouble(val);
            // △EVE 数值大（万元级），统一显示为"亿"=1e8
            boolean isDeve = e.getKey().equals("KPI_PNN_DEVE");
            double display = isDeve ? round2(dVal / 10000.0) : dVal;
            String unit = isDeve ? "亿" : "%";
            kpis.add(kpiCard(e.getValue(), display, unit,
                    isDeve ? "down" : "up",
                    colorOf(e.getKey()), null, null));
        }

        // ===== 5 指标 24 月趋势 =====
        // 取出所有数据点（按 code 分组）
        Map<String, List<Map<String, Object>>> byCode = new LinkedHashMap<>();
        for (Map<String, Object> r : dashboardMapper.keyIndicatorTrend()) {
            byCode.computeIfAbsent(String.valueOf(r.get("code")), k -> new ArrayList<>()).add(r);
        }
        // 取所有出现过的日期，升序，作为 X 轴
        Map<String, Integer> dateIdx = new LinkedHashMap<>();
        for (List<Map<String, Object>> lst : byCode.values()) {
            for (Map<String, Object> r : lst) {
                String d = strOf(r.get("dataDate"));
                if (!dateIdx.containsKey(d)) dateIdx.put(d, dateIdx.size());
            }
        }
        List<String> dates = new ArrayList<>(dateIdx.keySet());
        java.util.Collections.sort(dates);

        List<Map<String, Object>> trendSeries = new ArrayList<>();
        for (Map.Entry<String, String> e : cfg.entrySet()) {
            List<Map<String, Object>> lst = byCode.getOrDefault(e.getKey(), new ArrayList<>());
            // 把每条数据填充到 X 轴位置（缺位 null）
            List<Object> arr = new ArrayList<>();
            for (String d : dates) {
                Object val = null;
                for (Map<String, Object> r : lst) {
                    if (strOf(r.get("dataDate")).equals(d)) { val = r.get("value"); break; }
                }
                arr.add(val);
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("code", e.getKey());
            s.put("name", e.getValue());
            s.put("color", colorOf(e.getKey()));
            s.put("data", arr);
            trendSeries.add(s);
        }

        Map<String, Object> trend = new LinkedHashMap<>();
        trend.put("dates", dates);
        trend.put("series", trendSeries);

        // ===== 大类分布（环形 + 柱状共用） =====
        // 用 balance 最新日期汇总
        String useDate = (dataDate != null && !dataDate.isEmpty()) ? dataDate : balanceDate;
        List<Map<String, Object>> rawCat = useDate != null
                ? dashboardMapper.categoryDistribution(useDate)
                : new ArrayList<>();

        // 重新填满 5 大类（资产/负债/权益/表外/其他）
        Map<String, Map<String, Object>> catMap = new LinkedHashMap<>();
        for (Map<String, Object> r : rawCat) catMap.put(String.valueOf(r.get("category")), r);

        double asset = sumAmt(catMap.get("ASSET"));
        double liab  = sumAmt(catMap.get("LIABILITY"));
        double eq    = sumAmt(catMap.get("EQUITY"));
        double off   = sumAmt(catMap.get("OFF_BALANCE"));
        double oth   = sumAmt(catMap.get("OTHER"));
        double total = asset + liab + eq + off + oth;

        List<Map<String, Object>> donut = new ArrayList<>();
        donut.add(catItem("资产", asset, total, "#1890ff", sumCnt(catMap.get("ASSET"))));
        donut.add(catItem("负债", liab,  total, "#fa8c16", sumCnt(catMap.get("LIABILITY"))));
        donut.add(catItem("权益", eq,    total, "#722ed1", sumCnt(catMap.get("EQUITY"))));
        donut.add(catItem("表外", off,   total, "#13c2c2", sumCnt(catMap.get("OFF_BALANCE"))));
        donut.add(catItem("其他", oth,   total, "#eb2f96", sumCnt(catMap.get("OTHER"))));

        Map<String, Object> distribution = new LinkedHashMap<>();
        distribution.put("donut", donut);
        distribution.put("bar", donut);

        // ===== 顶部筛选选项 =====
        Map<String, Object> filterOptions = new LinkedHashMap<>();
        filterOptions.put("coaSchemes",     dashboardMapper.listCoaSchemes());
        filterOptions.put("reverseSchemes", dashboardMapper.listReverseSchemes());
        filterOptions.put("reverseRuns",    dashboardMapper.listReverseRuns());

        // ===== 节点 × 指标 数据矩阵（按 category 分组）=====
        List<Map<String, Object>> nodeMatrixRaw = dashboardMapper.nodeMetricMatrix();
        List<Map<String, Object>> nodeMatrix = new ArrayList<>();
        for (Map<String, Object> r : nodeMatrixRaw) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("node_id",        r.get("node_id"));
            m.put("node_code",      strOf(r.get("node_code")));
            m.put("node_name",      strOf(r.get("node_name")));
            m.put("level",          toLong(r.get("level")));
            m.put("category",       strOf(r.get("category")));
            m.put("current_balance", toDouble(r.get("current_balance")));
            m.put("weighted_rate",  toDouble(r.get("weighted_rate")));
            m.put("avg_balance",    toDouble(r.get("avg_balance")));
            m.put("interest_amount",toDouble(r.get("interest_amount")));
            nodeMatrix.add(m);
        }

        // ===== Top 10 节点（按余额绝对值）=====
        List<Map<String, Object>> topRaw = dashboardMapper.topNodesByBalance(10);
        List<Map<String, Object>> topNodes = new ArrayList<>();
        for (Map<String, Object> r : topRaw) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("node_id",         r.get("node_id"));
            m.put("node_code",       strOf(r.get("node_code")));
            m.put("node_name",       strOf(r.get("node_name")));
            m.put("level",           toLong(r.get("level")));
            m.put("category",        strOf(r.get("category")));
            m.put("current_balance", toDouble(r.get("current_balance")));
            m.put("weighted_rate",   toDouble(r.get("weighted_rate")));
            topNodes.add(m);
        }

        // ===== 风险预警 =====
        List<Map<String, Object>> alertRaw = dashboardMapper.riskAlerts();
        List<Map<String, Object>> alerts = new ArrayList<>();
        for (Map<String, Object> r : alertRaw) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("node_id",         r.get("node_id"));
            m.put("node_code",       strOf(r.get("node_code")));
            m.put("node_name",       strOf(r.get("node_name")));
            m.put("level",           toLong(r.get("level")));
            m.put("category",        strOf(r.get("category")));
            m.put("current_balance", toDouble(r.get("current_balance")));
            m.put("weighted_rate",   toDouble(r.get("weighted_rate")));
            m.put("alert_type",      strOf(r.get("alert_type")));
            m.put("severity",        strOf(r.get("severity")));
            alerts.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataDate", useDate);
        out.put("kpis", kpis);
        out.put("trend", trend);
        out.put("distribution", distribution);
        out.put("filterOptions", filterOptions);
        out.put("nodeMatrix", nodeMatrix);
        out.put("topNodes", topNodes);
        out.put("riskAlerts", alerts);
        return R.ok(out);
    }

    // ===== helpers =====
    private static Map<String, Object> kpiCard(String label, Object value, String unit, String trend,
                                               String color, Integer total, String suffix) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("value", value);
        m.put("unit", unit);
        m.put("trend", trend);   // up / down / flat
        m.put("color", color);
        if (total != null) {
            m.put("total", total);
            m.put("suffix", suffix == null ? "" : suffix);
        }
        return m;
    }

    private static Map<String, Object> catItem(String name, double amount, double total, String color, long cnt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("amount", round2(amount));
        m.put("cnt", cnt);
        m.put("ratio", total > 0 ? String.format("%.2f%%", amount / total * 100) : "0%");
        m.put("color", color);
        return m;
    }

    private static String colorOf(String code) {
        // 按 2026-09-29 设计规范：
        // 主序列蓝 #5B8FF9；辅助序列 #7D6FFC / #61DDAA / #008685 / #F6903D / #9661BC
        // DEVE（利率风险）作为预警型指标，使用橙色；其他 4 个按蓝/紫/青/绿分散
        return switch (code) {
            case "KPI_PNN_ROE"  -> "#5B8FF9";   // 主蓝
            case "KPI_PNN_CET1" -> "#008685";   // 辅助 1（深青）
            case "KPI_PNN_LCR"  -> "#61DDAA";   // 辅助 2（薄荷绿）
            case "KPI_PNN_NSFR" -> "#7D6FFC";   // 辅助 3（紫蓝）
            case "KPI_PNN_DEVE" -> "#F6903D";   // 辅助 4（橙色 — 预警）
            default -> "#5B8FF9";
        };
    }

    private static double toDouble(Object o) {
        if (o == null) return 0.0;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(o.toString()); } catch (Exception e) { return 0.0; }
    }

    private static long toLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        try { return Long.parseLong(o.toString()); } catch (Exception e) { return 0L; }
    }

    private static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static String strOf(Object o) {
        if (o == null) return null;
        if (o instanceof java.sql.Date d) return d.toLocalDate().toString();
        if (o instanceof LocalDate d)    return d.toString();
        return o.toString();
    }

    private static double sumAmt(Map<String, Object> r) {
        if (r == null) return 0.0;
        Object a = r.get("amount");
        return a == null ? 0.0 : toDouble(a);
    }

    private static long sumCnt(Map<String, Object> r) {
        if (r == null) return 0L;
        Object c = r.get("cnt");
        return c == null ? 0L : toLong(c);
    }
}