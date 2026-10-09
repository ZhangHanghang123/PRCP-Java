package com.prcp.business.reverse.dashboard;

import com.prcp.common.exception.BizException;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * <p>反算 Dashboard Service — 对位 Python app/routers/reverse_dashboard.py</p>
 *
 * <p>核心职责:
 * <ol>
 *   <li>方案下拉选项 (含默认方案 = scheme_code='2026' 或首个)</li>
 *   <li>方案下的 Run 列表</li>
 *   <li>Run 下的月份列表 (M 单位)</li>
 *   <li>Snapshot 快照 (KPI + 趋势 + 大类分布 + 节点矩阵 + Top 节点 + 风险预警)</li>
 * </ol>
 * </p>
 *
 * <p>关键约定:
 * <ul>
 *   <li>5 监管指标: ROE / CET1 / LCR / NSFR / DELTA_EVE</li>
 *   <li>5 大类: ASSET / LIABILITY / EQUITY / OFF_BALANCE / OTHER</li>
 *   <li>风险阈值: CET1≥8.5, LCR≥100, NSFR≥100, ROE≥11, |ΔEVE|≤5</li>
 *   <li>数据来源: prcp_reverse_scheme + prcp_data_reverse + prcp_metric_coefficient + prcp_coa_node + prcp_kpi_value</li>
 *   <li>metric_coefficient.scheme_code 存反算方案 code (如 REV_DNN_REG), 不是账户册 code</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReverseDashboardService {

    private final JdbcTemplate jdbc;

    private static final Map<String, Map<String, Object>> THRESHOLDS = new LinkedHashMap<>();
    static {
        THRESHOLDS.put("CET1", Map.of("min", 8.5, "direction", "down"));
        THRESHOLDS.put("LCR",  Map.of("min", 100.0, "direction", "down"));
        THRESHOLDS.put("NSFR", Map.of("min", 100.0, "direction", "down"));
        THRESHOLDS.put("ROE",  Map.of("min", 11.0, "direction", "down"));
        THRESHOLDS.put("DELTA_EVE", Map.of("max_abs", 5.0, "direction", "abs"));
    }
    private static final List<String> DEFAULT_METRIC_TYPES = List.of("ROE", "CET1", "LCR", "NSFR", "DELTA_EVE");

    /**
     * <p>方案下拉选项 (含默认方案 + 默认 Run + 默认 date_offset)</p>
     *
     * @return R.ok(Map.of("schemes"/"default", ...)); 默认 scheme_code='2026' 或首个
     */
    public R<Map<String, Object>> options() {
        List<Map<String, Object>> schemes = jdbc.queryForList(
                "SELECT s.id AS rev_id, s.scheme_code, s.scheme_name, s.coa_scheme_id, s.data_date, s.horizon_months,"
              + " c.scheme_code AS coa_code, c.scheme_name AS coa_name"
              + " FROM prcp_reverse_scheme s"
              + " LEFT JOIN prcp_coa_scheme c ON c.id = s.coa_scheme_id"
              + " WHERE s.is_deleted = 0 ORDER BY s.id DESC");
        List<Map<String, Object>> schemeItems = new ArrayList<>();
        for (Map<String, Object> r : schemes) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("rev_id", r.get("rev_id"));
            s.put("scheme_code", r.get("scheme_code"));
            s.put("scheme_name", r.get("scheme_name"));
            s.put("coa_scheme_id", r.get("coa_scheme_id"));
            s.put("base_data_date", r.get("data_date") == null ? null : r.get("data_date").toString());
            s.put("coa_scheme_code", r.get("coa_code"));
            s.put("coa_scheme_name", r.get("coa_name"));
            s.put("horizon_months", r.get("horizon_months") == null ? 24 : ((Number) r.get("horizon_months")).intValue());
            schemeItems.add(s);
        }
        // 默认方案
        Map<String, Object> def = schemeItems.stream()
                .filter(s -> "2026".equals(s.get("scheme_code")))
                .findFirst().orElse(schemeItems.isEmpty() ? null : schemeItems.get(0));
        Map<String, Object> defaultRun = null;
        Integer defaultDateOffset = 1;
        if (def != null) {
            List<Map<String, Object>> runs = jdbc.queryForList(
                    "SELECT id, status, start_at, end_at, duration_sec FROM prcp_reverse_run"
                  + " WHERE scheme_id = ? AND status = 'SUCCESS' AND is_deleted = 0"
                  + " ORDER BY id DESC LIMIT 1", def.get("rev_id"));
            if (!runs.isEmpty()) {
                Map<String, Object> r = runs.get(0);
                defaultRun = new LinkedHashMap<>();
                defaultRun.put("run_id", r.get("id"));
                defaultRun.put("status", r.get("status"));
                defaultRun.put("start_at", r.get("start_at") == null ? null : r.get("start_at").toString());
                defaultRun.put("end_at", r.get("end_at") == null ? null : r.get("end_at").toString());
                defaultRun.put("duration_sec", r.get("duration_sec"));
                // max offset from data_reverse
                Integer maxOff = jdbc.queryForObject(
                        "SELECT MAX(date_offset) FROM prcp_data_reverse"
                      + " WHERE scheme_code = ? AND run_id = ? AND is_deleted = 0",
                        Integer.class, def.get("scheme_code"), r.get("id"));
                defaultDateOffset = maxOff == null ? 1 : maxOff;
            }
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("schemes", schemeItems);
        Map<String, Object> defaultResp = new LinkedHashMap<>();
        defaultResp.put("scheme_code", def == null ? null : def.get("scheme_code"));
        defaultResp.put("run_id", defaultRun == null ? null : defaultRun.get("run_id"));
        defaultResp.put("date_offset", defaultDateOffset);
        resp.put("default", defaultResp);
        return R.ok(resp);
    }

    /**
     * <p>方案下的 Run 列表</p>
     *
     * @param schemeCode 反算方案编码 (必填)
     * @return R.ok(Map.of("scheme_code"/"items", ...)); 不存在时抛 notFound
     */
    public R<Map<String, Object>> runs(String schemeCode) {
        if (schemeCode == null || schemeCode.isEmpty())
            throw BizException.badRequest("scheme_code 必填");
        Integer revId;
        try {
            revId = jdbc.queryForObject(
                    "SELECT id FROM prcp_reverse_scheme WHERE scheme_code = ? AND is_deleted = 0",
                    Integer.class, schemeCode);
        } catch (Exception e) { revId = null; }
        if (revId == null) throw BizException.notFound("方案 " + schemeCode + " 不存在");
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT id AS runId, status, run_code AS runCode, start_at AS startAt,"
              + " end_at AS endAt, duration_sec AS durationSec, optimal_value AS optimalValue"
              + " FROM prcp_reverse_run WHERE scheme_id = ? AND is_deleted = 0"
              + " ORDER BY id DESC", revId);
        // 把 key 改回 Python 风格（run_id 等）
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("run_id", r.get("runId"));
            m.put("status", r.get("status"));
            m.put("run_code", r.get("runCode"));
            m.put("start_at", r.get("startAt") == null ? null : r.get("startAt").toString());
            m.put("end_at", r.get("endAt") == null ? null : r.get("endAt").toString());
            m.put("duration_sec", r.get("durationSec"));
            m.put("optimal_value", r.get("optimalValue"));
            out.add(m);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("scheme_code", schemeCode);
        resp.put("items", out);
        return R.ok(resp);
    }

    /**
     * <p>Run 下的月份列表 (M 单位, 按 date_offset ASC)</p>
     *
     * @param schemeCode 反算方案编码 (必填)
     * @param runId      Run ID (必填)
     * @return R.ok(Map.of("scheme_code"/"run_id"/"items", ...))
     */
    public R<Map<String, Object>> dates(String schemeCode, Long runId) {
        if (schemeCode == null || schemeCode.isEmpty() || runId == null)
            throw BizException.badRequest("scheme_code 和 run_id 必填");
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT DISTINCT date_offset AS dateOffset, data_date AS dataDate"
              + " FROM prcp_data_reverse WHERE scheme_code = ? AND run_id = ?"
              + " AND offset_unit = 'M' AND is_deleted = 0 ORDER BY date_offset ASC",
                schemeCode, runId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date_offset", r.get("dateOffset"));
            m.put("data_date", r.get("dataDate") == null ? null : r.get("dataDate").toString());
            out.add(m);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("scheme_code", schemeCode);
        resp.put("run_id", runId);
        resp.put("items", out);
        return R.ok(resp);
    }

    /**
     * <p>Snapshot 快照 (KPI + 趋势 + 大类分布 + 节点矩阵 + Top 节点 + 风险预警 + KPI 评分)</p>
     *
     * @param schemeCode 反算方案编码 (默认 "2026")
     * @param runId      Run ID (可选, 默认最新 SUCCESS)
     * @param dateOffset 日期偏移月数 (默认 1)
     * @return R.ok(Map); 包含 kpi/trend/category_distribution/node_matrix/top_nodes/risk_alerts/kpi_scores
     */
    public R<Map<String, Object>> snapshot(String schemeCode, Long runId, Integer dateOffset) {
        if (schemeCode == null || schemeCode.isEmpty()) schemeCode = "2026";
        if (dateOffset == null) dateOffset = 1;

        // 1. 方案
        List<Map<String, Object>> revRows = jdbc.queryForList(
                "SELECT s.id AS revId, s.coa_scheme_id AS coaSchemeId, s.scheme_name AS schemeName,"
              + " s.data_date AS dataDate, s.horizon_months AS horizonMonths,"
              + " c.scheme_code AS coaCode, c.scheme_name AS coaName"
              + " FROM prcp_reverse_scheme s LEFT JOIN prcp_coa_scheme c ON c.id = s.coa_scheme_id"
              + " WHERE s.scheme_code = ? AND s.is_deleted = 0", schemeCode);
        if (revRows.isEmpty()) throw BizException.notFound("方案 " + schemeCode + " 不存在");
        Map<String, Object> rev = revRows.get(0);
        String baseDate = rev.get("dataDate") == null ? null : rev.get("dataDate").toString();
        Long revId = ((Number) rev.get("revId")).longValue();
        Long coaSchemeId = rev.get("coaSchemeId") == null ? null : ((Number) rev.get("coaSchemeId")).longValue();

        // 2. Run（缺省取最新 SUCCESS）
        if (runId == null) {
            List<Map<String, Object>> lr = jdbc.queryForList(
                    "SELECT id FROM prcp_reverse_run WHERE scheme_id = ? AND status = 'SUCCESS' AND is_deleted = 0"
                  + " ORDER BY id DESC LIMIT 1", revId);
            if (lr.isEmpty()) throw BizException.notFound("该方案无 SUCCESS 运行记录");
            runId = ((Number) lr.get(0).get("id")).longValue();
        }

        // 3. 当前月份 data_date
        List<Map<String, Object>> ddRows = jdbc.queryForList(
                "SELECT data_date AS dataDate FROM prcp_data_reverse"
              + " WHERE scheme_code = ? AND run_id = ? AND date_offset = ?"
              + " AND offset_unit = 'M' AND is_deleted = 0 ORDER BY data_date DESC LIMIT 1",
                schemeCode, runId, dateOffset);
        if (ddRows.isEmpty()) throw BizException.notFound("未找到 M" + dateOffset);
        Object dataDate = ddRows.get(0).get("dataDate");
        String dataDateStr = dataDate == null ? null : dataDate.toString();

        // 4. 节点元数据
        List<Map<String, Object>> nodes = new ArrayList<>();
        if (coaSchemeId != null) {
            nodes = jdbc.queryForList(
                    "SELECT id, node_code, node_name, node_level, path"
                  + " FROM prcp_coa_node WHERE scheme_id = ? AND is_deleted = 0"
                  + " ORDER BY path, node_level", coaSchemeId);
            // 改回 Python key 名（snake_case 一致）
            for (Map<String, Object> n : nodes) {
                n.put("coa_node_id", n.get("id"));
            }
        }

        // 5. 当前月份的余额/利率
        List<Map<String, Object>> revRowsData = jdbc.queryForList(
                "SELECT coa_node_id, node_code, current_balance, avg_balance, weighted_rate, interest_amount, risk_weight"
              + " FROM prcp_data_reverse"
              + " WHERE scheme_code = ? AND run_id = ? AND date_offset = ?"
              + " AND offset_unit = 'M' AND is_deleted = 0",
                schemeCode, runId, dateOffset);
        Map<String, Map<String, Object>> revMap = new LinkedHashMap<>();
        for (Map<String, Object> r : revRowsData) {
            String nc = (String) r.get("node_code");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("coa_node_id", r.get("coa_node_id"));
            m.put("current_balance", toBD(r.get("current_balance")));
            m.put("avg_balance", toBD(r.get("avg_balance")));
            m.put("weighted_rate", toBD(r.get("weighted_rate")));
            m.put("interest_amount", toBD(r.get("interest_amount")));
            m.put("risk_weight", toBD(r.get("risk_weight")));
            revMap.put(nc, m);
        }

        // 6. 当前月份的 5 个指标
        Map<String, Map<String, BigDecimal>> metricMap = new LinkedHashMap<>();
        if (!nodes.isEmpty()) {
            String ph = String.join(",", Collections.nCopies(nodes.size(), "?"));
            String ph2 = String.join(",", Collections.nCopies(DEFAULT_METRIC_TYPES.size(), "?"));
            // 关键修复: prcp_metric_coefficient.scheme_code 存的是反算方案 code (如 REV_DNN_REG)
            // 而不是账户册 code (ZX_COA), 之前用 coaCode 导致查询返回空
            Object[] params = new Object[nodes.size() + DEFAULT_METRIC_TYPES.size() + 2];
            params[0] = schemeCode; params[1] = dataDateStr;
            int idx = 2;
            for (Map<String, Object> n : nodes) { params[idx++] = n.get("node_code"); }
            for (String m : DEFAULT_METRIC_TYPES) { params[idx++] = m; }
            String sql = "SELECT node_code, metric_type, current_value"
                       + " FROM prcp_metric_coefficient WHERE is_deleted = 0 AND scheme_code = ?"
                       + " AND data_date = ? AND node_code IN (" + ph + ")"
                       + " AND metric_type IN (" + ph2 + ")";
            List<Map<String, Object>> mrows = jdbc.queryForList(sql, params);
            for (Map<String, Object> r : mrows) {
                String nc = (String) r.get("node_code");
                String mt = (String) r.get("metric_type");
                metricMap.computeIfAbsent(nc, k -> new LinkedHashMap<>()).put(mt, toBD(r.get("current_value")));
            }
        }

        // 7. KPI 聚合
        int totalNodes = nodes.size();
        long withMetrics = nodes.stream().filter(n -> metricMap.containsKey(n.get("node_code"))).count();
        BigDecimal assetTotal = sumByCat(nodes, revMap, "ASSET");
        BigDecimal liabilityTotal = sumByCat(nodes, revMap, "LIABILITY");
        BigDecimal equityTotal = sumByCat(nodes, revMap, "EQUITY");
        BigDecimal offBalance = sumByCat(nodes, revMap, "OFF_BALANCE");

        Map<String, Object> metricAvg = new LinkedHashMap<>();
        Map<String, Object> metricMax = new LinkedHashMap<>();
        Map<String, Object> metricMin = new LinkedHashMap<>();
        for (String m : DEFAULT_METRIC_TYPES) {
            List<BigDecimal> vals = new ArrayList<>();
            for (Map<String, Object> n : nodes) {
                Map<String, BigDecimal> mv = metricMap.get(n.get("node_code"));
                if (mv != null && mv.get(m) != null) vals.add(mv.get(m));
            }
            if (!vals.isEmpty()) {
                BigDecimal sum = BigDecimal.ZERO;
                for (BigDecimal v : vals) sum = sum.add(v);
                metricAvg.put(m, sum.divide(BigDecimal.valueOf(vals.size()), 4, RoundingMode.HALF_UP));
                metricMax.put(m, vals.stream().max(Comparator.naturalOrder()).get());
                metricMin.put(m, vals.stream().min(Comparator.naturalOrder()).get());
            } else {
                metricAvg.put(m, null); metricMax.put(m, null); metricMin.put(m, null);
            }
        }

        // 7a. 贷款加权平均利率：境内各项人民币贷款（S010101*）下属 L4 子节点按余额加权平均
        // 对齐 Python 源系统：S01010101/02/03/04/05 开头（不含 L3 父节点 S010101000000）
        BigDecimal loanNum = BigDecimal.ZERO;
        BigDecimal loanDen = BigDecimal.ZERO;
        BigDecimal loanWeightedRate = null;
        for (Map<String, Object> n : nodes) {
            String nc = (String) n.get("node_code");
            if (!(nc != null && (nc.startsWith("S01010101") || nc.startsWith("S01010102")
                    || nc.startsWith("S01010103") || nc.startsWith("S01010104")
                    || nc.startsWith("S01010105")))) continue;
            Map<String, Object> r = revMap.get(nc);
            if (r == null) continue;
            BigDecimal bal = (BigDecimal) r.getOrDefault("current_balance", BigDecimal.ZERO);
            BigDecimal rate = (BigDecimal) r.getOrDefault("weighted_rate", BigDecimal.ZERO);
            if (bal == null || bal.abs().compareTo(new BigDecimal("0.01")) < 0) continue;
            loanNum = loanNum.add(bal.multiply(rate));
            loanDen = loanDen.add(bal);
        }
        if (loanDen.compareTo(BigDecimal.ZERO) > 0) {
            loanWeightedRate = loanNum.divide(loanDen, 6, RoundingMode.HALF_UP);
        }

        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("total_nodes", totalNodes);
        kpi.put("with_metrics", withMetrics);
        kpi.put("asset_total", assetTotal);
        kpi.put("liability_total", liabilityTotal);
        kpi.put("equity_total", equityTotal);
        kpi.put("off_balance", offBalance);
        kpi.put("loan_weighted_rate", loanWeightedRate);
        kpi.put("metric_avg", metricAvg);
        kpi.put("metric_max", metricMax);
        kpi.put("metric_min", metricMin);

        // 7b. 5 指标评分：直接查 prcp_kpi_value.score（基期 base_date），对齐 Python 版
        List<Map<String, Object>> kpiScores = new ArrayList<>();
        if (baseDate != null) {
            List<Map<String, Object>> kpiScoreRows = jdbc.queryForList(
                    "SELECT d.kpi_code AS kpiCode, d.kpi_name AS kpiName,"
                  + " v.current_value AS value, v.score AS score,"
                  + " s.min_value AS segMin, s.max_value AS segMax, s.segment_desc AS segDesc"
                  + " FROM prcp_kpi_definition d"
                  + " LEFT JOIN prcp_kpi_value v ON v.kpi_id = d.id AND v.data_date = ? AND v.is_deleted = 0"
                  + " LEFT JOIN prcp_kpi_score_rule r ON r.kpi_id = d.id AND r.is_deleted = 0"
                  + " LEFT JOIN prcp_kpi_score_segment s ON s.rule_id = r.id AND s.is_deleted = 0"
                  + "    AND (s.min_value IS NULL OR v.current_value >= s.min_value)"
                  + "    AND (s.max_value IS NULL OR v.current_value < s.max_value)"
                  + " WHERE d.is_deleted = 0 AND d.kpi_code LIKE 'REG_%' ORDER BY d.id", baseDate);
            for (Map<String, Object> r : kpiScoreRows) {
                Map<String, Object> ks = new LinkedHashMap<>();
                ks.put("kpi_code", r.get("kpiCode"));
                ks.put("kpi_name", r.get("kpiName"));
                Object v = r.get("value");
                Object s = r.get("score");
                ks.put("value", v == null ? null : new BigDecimal(v.toString()));
                ks.put("score", s == null ? null : ((Number) s).doubleValue());
                ks.put("segment_min", r.get("segMin"));
                ks.put("segment_max", r.get("segMax"));
                ks.put("segment_desc", r.get("segDesc"));
                kpiScores.add(ks);
            }
        }

        // 8. 24 月趋势
        List<Map<String, Object>> monthRows = jdbc.queryForList(
                "SELECT date_offset, data_date FROM prcp_data_reverse"
              + " WHERE scheme_code = ? AND run_id = ? AND offset_unit = 'M' AND is_deleted = 0"
              + " GROUP BY date_offset, data_date ORDER BY date_offset ASC",
                schemeCode, runId);
        List<Map<String, Object>> monthDates = new ArrayList<>();
        for (Map<String, Object> mr : monthRows) {
            Map<String, Object> md = new LinkedHashMap<>();
            md.put("date_offset", ((Number) mr.get("date_offset")).intValue());
            md.put("data_date", mr.get("data_date") == null ? null : mr.get("data_date").toString());
            monthDates.add(md);
        }

        Map<String, List<BigDecimal>> monthMetricVals = new LinkedHashMap<>();
        if (!nodes.isEmpty() && !monthDates.isEmpty()) {
            String ph = String.join(",", Collections.nCopies(nodes.size(), "?"));
            String ph2 = String.join(",", Collections.nCopies(DEFAULT_METRIC_TYPES.size(), "?"));
            // 关键修复: prcp_metric_coefficient.scheme_code 存的是反算方案 code (REV_DNN_REG)
            // 对齐 Python: 先按 coa_code 查, 没数据 fallback 到任意 scheme_code
            String coaCode = (String) rev.get("coaCode");
            Object[] params = new Object[nodes.size() + DEFAULT_METRIC_TYPES.size() + 1];
            params[0] = coaCode; int idx = 1;
            for (Map<String, Object> n : nodes) { params[idx++] = n.get("node_code"); }
            for (String m : DEFAULT_METRIC_TYPES) { params[idx++] = m; }
            String sql = "SELECT data_date, metric_type, current_value"
                       + " FROM prcp_metric_coefficient WHERE is_deleted = 0 AND scheme_code = ?"
                       + " AND node_code IN (" + ph + ") AND metric_type IN (" + ph2 + ")";
            List<Map<String, Object>> allMetric = jdbc.queryForList(sql, params);
            // Fallback: 如果当前 coa_code 没数据, 拿全部 scheme_code 的数据（对齐 Python 行为）
            if (allMetric.isEmpty()) {
                Object[] params2 = new Object[nodes.size() + DEFAULT_METRIC_TYPES.size()];
                int i2 = 0;
                for (Map<String, Object> n : nodes) { params2[i2++] = n.get("node_code"); }
                for (String m : DEFAULT_METRIC_TYPES) { params2[i2++] = m; }
                String sql2 = "SELECT data_date, metric_type, current_value"
                            + " FROM prcp_metric_coefficient WHERE is_deleted = 0"
                            + " AND node_code IN (" + ph + ") AND metric_type IN (" + ph2 + ")";
                allMetric = jdbc.queryForList(sql2, params2);
            }
            // 修正：按 metric_type + data_date 分组
            Map<String, Map<String, List<BigDecimal>>> byMetric = new LinkedHashMap<>();
            for (Map<String, Object> r : allMetric) {
                String dd = r.get("data_date") == null ? "" : r.get("data_date").toString();
                String mt = (String) r.get("metric_type");
                byMetric.computeIfAbsent(mt, k -> new LinkedHashMap<>())
                        .computeIfAbsent(dd, k -> new ArrayList<>()).add(toBD(r.get("current_value")));
            }
            Map<String, List<BigDecimal>> trend = new LinkedHashMap<>();
            List<String> monthsLabel = new ArrayList<>();
            for (Map<String, Object> md : monthDates) {
                String dd = (String) md.get("data_date");
                String lbl = dd == null ? "" : dd.replace("-", "").substring(2); // YYMMDD
                monthsLabel.add(lbl);
                for (String m : DEFAULT_METRIC_TYPES) {
                    List<BigDecimal> vals = byMetric.getOrDefault(m, Collections.emptyMap()).getOrDefault(dd, Collections.emptyList());
                    BigDecimal avg = vals.isEmpty() ? null
                            : vals.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                                    .divide(BigDecimal.valueOf(vals.size()), 4, RoundingMode.HALF_UP);
                    trend.computeIfAbsent(m, k -> new ArrayList<>()).add(avg);
                }
            }
            Map<String, Object> trendObj = new LinkedHashMap<>();
            trendObj.put("months", monthsLabel);
            for (String m : DEFAULT_METRIC_TYPES) trendObj.put(m, trend.getOrDefault(m, new ArrayList<>()));
            // 9. 大类分布
            Map<String, BigDecimal> catDist = new LinkedHashMap<>();
            Map<String, Integer> catCount = new LinkedHashMap<>();
            for (String c : List.of("ASSET", "LIABILITY", "EQUITY", "OFF_BALANCE", "OTHER")) {
                catDist.put(c, BigDecimal.ZERO); catCount.put(c, 0);
            }
            for (Map<String, Object> n : nodes) {
                String cat = classify((String) n.get("node_code"), (String) n.get("path"));
                catCount.merge(cat, 1, Integer::sum);
                BigDecimal bal = revMap.containsKey(n.get("node_code"))
                        ? (BigDecimal) revMap.get(n.get("node_code")).getOrDefault("current_balance", BigDecimal.ZERO)
                        : BigDecimal.ZERO;
                catDist.merge(cat, bal, BigDecimal::add);
            }
            Map<String, Object> categoryDistribution = new LinkedHashMap<>();
            categoryDistribution.put("by_count", catCount);
            Map<String, Object> byBal = new LinkedHashMap<>();
            for (Map.Entry<String, BigDecimal> e : catDist.entrySet()) byBal.put(e.getKey(), e.getValue().setScale(2, RoundingMode.HALF_UP));
            categoryDistribution.put("by_balance", byBal);

            // 10. 节点详情矩阵
            List<Map<String, Object>> nodeMatrix = new ArrayList<>();
            for (Map<String, Object> n : nodes) {
                String nc = (String) n.get("node_code");
                String cat = classify(nc, (String) n.get("path"));
                Map<String, BigDecimal> mv = metricMap.getOrDefault(nc, Collections.emptyMap());
                Map<String, Object> rd = revMap.getOrDefault(nc, Collections.emptyMap());
                Map<String, Object> nm = new LinkedHashMap<>();
                nm.put("coa_node_id", n.get("coa_node_id"));
                nm.put("node_code", nc);
                nm.put("node_name", n.get("node_name"));
                nm.put("node_level", n.get("node_level"));
                nm.put("category", cat);
                nm.put("current_balance", rd.getOrDefault("current_balance", BigDecimal.ZERO));
                nm.put("avg_balance", rd.getOrDefault("avg_balance", BigDecimal.ZERO));
                nm.put("weighted_rate", rd.getOrDefault("weighted_rate", BigDecimal.ZERO));
                nm.put("interest_amount", rd.getOrDefault("interest_amount", BigDecimal.ZERO));
                Map<String, Object> mvs = new LinkedHashMap<>();
                for (String m : DEFAULT_METRIC_TYPES) mvs.put(m, mv.get(m));
                nm.put("metric_values", mvs);
                nodeMatrix.add(nm);
            }

            // 11. Top 10
            List<Map<String, Object>> topNodes = new ArrayList<>(nodeMatrix);
            topNodes.sort((a, b) -> {
                BigDecimal ab = (BigDecimal) a.get("current_balance");
                BigDecimal bb = (BigDecimal) b.get("current_balance");
                return bb.abs().compareTo(ab.abs());
            });
            if (topNodes.size() > 10) topNodes = topNodes.subList(0, 10);

            // 12. 风险预警
            List<Map<String, Object>> alerts = new ArrayList<>();
            for (Map<String, Object> nm : nodeMatrix) {
                @SuppressWarnings("unchecked")
                Map<String, Object> mv = (Map<String, Object>) nm.get("metric_values");
                List<Map<String, Object>> nodeAlerts = new ArrayList<>();
                for (String mk : List.of("CET1", "LCR", "NSFR", "ROE")) {
                    BigDecimal v = toBD(mv.get(mk));
                    if (v == null) continue;
                    BigDecimal thr = new BigDecimal(THRESHOLDS.get(mk).get("min").toString());
                    if (v.compareTo(thr) < 0) {
                        Map<String, Object> a = new LinkedHashMap<>();
                        a.put("metric", mk);
                        a.put("value", v);
                        a.put("threshold", thr);
                        a.put("severity", v.compareTo(thr.multiply(BigDecimal.valueOf(0.9))) < 0 ? "critical" : "warn");
                        a.put("msg", mk + "=" + v + " 低于阈值 " + thr);
                        nodeAlerts.add(a);
                    }
                }
                BigDecimal de = toBD(mv.get("DELTA_EVE"));
                if (de != null) {
                    BigDecimal thrAbs = new BigDecimal(THRESHOLDS.get("DELTA_EVE").get("max_abs").toString());
                    if (de.abs().compareTo(thrAbs) > 0) {
                        Map<String, Object> a = new LinkedHashMap<>();
                        a.put("metric", "DELTA_EVE");
                        a.put("value", de);
                        a.put("threshold", thrAbs);
                        a.put("severity", de.abs().compareTo(thrAbs.multiply(BigDecimal.valueOf(1.2))) > 0 ? "critical" : "warn");
                        a.put("msg", "|ΔEVE|=" + de.abs() + " 超过阈值 " + thrAbs);
                        nodeAlerts.add(a);
                    }
                }
                if (!nodeAlerts.isEmpty()) {
                    Map<String, Object> alert = new LinkedHashMap<>();
                    alert.put("node_code", nm.get("node_code"));
                    alert.put("node_name", nm.get("node_name"));
                    alert.put("category", nm.get("category"));
                    alert.put("current_balance", nm.get("current_balance"));
                    alert.put("alerts", nodeAlerts);
                    alert.put("alert_count", nodeAlerts.size());
                    alerts.add(alert);
                }
            }
            alerts.sort((a, b) -> {
                int cnt = ((Number) b.get("alert_count")).intValue() - ((Number) a.get("alert_count")).intValue();
                if (cnt != 0) return cnt;
                BigDecimal ab = (BigDecimal) a.get("current_balance");
                BigDecimal bb = (BigDecimal) b.get("current_balance");
                return bb.abs().compareTo(ab.abs());
            });

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("scheme_code", schemeCode);
            resp.put("scheme_name", rev.get("schemeName"));
            resp.put("coa_scheme_code", rev.get("coaCode"));
            resp.put("coa_scheme_name", rev.get("coaName"));
            resp.put("base_data_date", rev.get("dataDate") == null ? null : rev.get("dataDate").toString());
            resp.put("horizon_months", rev.get("horizonMonths"));
            resp.put("run_id", runId);
            resp.put("date_offset", dateOffset);
            resp.put("data_date", dataDateStr);
            resp.put("kpi", kpi);
            resp.put("trend", trendObj);
            resp.put("category_distribution", categoryDistribution);
            resp.put("node_matrix", nodeMatrix);
            resp.put("top_nodes", topNodes);
            resp.put("risk_alerts", alerts);
            resp.put("risk_alert_total", alerts.size());
            resp.put("thresholds", THRESHOLDS);
            resp.put("kpi_scores", kpiScores);
            return R.ok(resp);
        }
        return R.ok(Collections.emptyMap());
    }

    // ============== 辅助 ==============
    /**
     * 仅算叶子节点（节点 N 是 leaf ⇔ 没有其他节点 M 使得 M.path 以 N.path + "/" 开头）
     * 对齐 Python _is_leaf；避免父子重复计算（如 S010100000000 父节点 + S010101/S010102/S010103 子节点）
     */
    private static boolean isLeaf(String nodeCode, String path, Set<String> parentSet) {
        if (path == null || path.isEmpty()) return true;
        return !parentSet.contains(nodeCode);
    }

    /**
     * 计算所有父节点集合（有子节点的节点）
     * 算法: 节点 N 有子节点 ⇔ 存在节点 M 使 M.path 以 N.path + "/" 开头
     */
    private static Set<String> computeParentSet(List<Map<String, Object>> nodes) {
        Set<String> parentSet = new HashSet<>();
        for (Map<String, Object> a : nodes) {
            String ncA = (String) a.get("node_code");
            String pA = (String) a.get("path");
            if (pA == null || pA.isEmpty()) continue;
            String prefix = pA.endsWith("/") ? pA : pA + "/";
            for (Map<String, Object> b : nodes) {
                if (a == b) continue;
                String ncB = (String) b.get("node_code");
                if (ncA.equals(ncB)) continue;
                String pB = (String) b.get("path");
                if (pB == null || pB.isEmpty()) continue;
                if (pB.startsWith(prefix)) { parentSet.add(ncA); break; }
            }
        }
        return parentSet;
    }

    private BigDecimal sumByCat(List<Map<String, Object>> nodes, Map<String, Map<String, Object>> revMap, String cat) {
        BigDecimal sum = BigDecimal.ZERO;
        Set<String> parents = computeParentSet(nodes);
        for (Map<String, Object> n : nodes) {
            String nc = (String) n.get("node_code");
            if (!cat.equals(classify(nc, (String) n.get("path")))) continue;
            if (!isLeaf(nc, (String) n.get("path"), parents)) continue;  // 仅叶子节点
            Map<String, Object> r = revMap.get(nc);
            if (r == null) continue;
            BigDecimal bal = (BigDecimal) r.getOrDefault("current_balance", BigDecimal.ZERO);
            sum = sum.add(bal);
        }
        return sum.setScale(2, RoundingMode.HALF_UP);
    }

    private static String classify(String nodeCode, String path) {
        String code = nodeCode == null ? "" : nodeCode;
        String p = path == null ? "" : path;
        // 1) 顶层 node_code 前缀识别（S01=资产 / S02=负债 / S03=权益）
        if (code.startsWith("S01") || code.startsWith("ZX_A") || p.startsWith("/L1_资产") || p.equals("/L1_ASSET/")) return "ASSET";
        if (code.startsWith("S02") || code.startsWith("ZX_L") || p.startsWith("/L1_负债") || p.equals("/L1_LIABILITY/")) return "LIABILITY";
        if (code.startsWith("S03") || code.startsWith("ZX_E") || p.startsWith("/L1_权益") || p.equals("/L1_EQUITY/")) return "EQUITY";
        if (p.startsWith("/L1_表外") || p.equals("/L1_OFF_BALANCE/")) return "OFF_BALANCE";
        return "OTHER";
    }

    private static BigDecimal toBD(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal) return (BigDecimal) o;
        if (o instanceof Number) return new BigDecimal(o.toString());
        try { return new BigDecimal(o.toString()); } catch (Exception e) { return null; }
    }
}