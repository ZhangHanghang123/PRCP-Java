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
     * <p>KPI 编码 → 指标类型 (目标达成计算用) — 对位 Python REG_TO_METRIC</p>
     */
    private static final Map<String, String> REG_TO_METRIC = new LinkedHashMap<>();
    static {
        REG_TO_METRIC.put("REG_ROE", "ROE");
        REG_TO_METRIC.put("REG_CET1", "CET1");
        REG_TO_METRIC.put("REG_LCR", "LCR");
        REG_TO_METRIC.put("REG_NSFR", "NSFR");
        REG_TO_METRIC.put("REG_EVE", "DELTA_EVE");
        REG_TO_METRIC.put("REG_DELTA_EVE", "DELTA_EVE");
    }

    /**
     * <p>大类清单 (分布 / 余额走势用)</p>
     */
    private static final List<String> CATEGORIES =
            List.of("ASSET", "LIABILITY", "EQUITY", "OFF_BALANCE", "OTHER");

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

        // 4a. 基础数据（基准日余额 + is_leaf 末级标记）
        //  对位 Python: base_balance 取 prcp_data_basic 基准日 current_balance；
        //  is_leaf 优先用 prcp_data_basic 的标记, 缺失时回退 path 前缀推断
        Map<String, Map<String, Object>> basicMap = new LinkedHashMap<>();
        if (!nodes.isEmpty() && baseDate != null) {
            String ph = String.join(",", Collections.nCopies(nodes.size(), "?"));
            Object[] params = new Object[nodes.size() + 1];
            params[0] = baseDate;
            int i = 1;
            for (Map<String, Object> n : nodes) { params[i++] = n.get("node_code"); }
            List<Map<String, Object>> brows = jdbc.queryForList(
                    "SELECT node_code, current_balance, is_leaf FROM prcp_data_basic"
                  + " WHERE is_deleted = 0 AND data_date = ? AND node_code IN (" + ph + ")",
                    params);
            for (Map<String, Object> r : brows) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("base_balance", toBD(r.get("current_balance")));
                m.put("is_leaf", r.get("is_leaf"));
                basicMap.put((String) r.get("node_code"), m);
            }
        }
        // path 前缀法推断的父节点集合（basic 缺 is_leaf 时的回退）
        Set<String> parentSet = computeParentSet(nodes);


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
            // 关键修复 v2: prcp_metric_coefficient.scheme_code 存的是反算方案 code (如 REV_DNN_REG)
            // 而不是账户册 code (ZX_COA), 之前用 coaCode 导致查询返回空
            // v2 新增 fallback: 新方案 (如 scheme_code='2026') 还没有数据时, 自动回退到任意已有 scheme_code
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
            // Fallback 1: 同 data_date 任意 scheme_code (解决"新方案没跑过引擎"问题)
            if (mrows.isEmpty()) {
                Object[] paramsFb1 = new Object[nodes.size() + DEFAULT_METRIC_TYPES.size() + 1];
                paramsFb1[0] = dataDateStr;
                int i2 = 1;
                for (Map<String, Object> n : nodes) { paramsFb1[i2++] = n.get("node_code"); }
                for (String m : DEFAULT_METRIC_TYPES) { paramsFb1[i2++] = m; }
                String sqlFb1 = "SELECT node_code, metric_type, current_value"
                              + " FROM prcp_metric_coefficient WHERE is_deleted = 0"
                              + " AND data_date = ? AND node_code IN (" + ph + ")"
                              + " AND metric_type IN (" + ph2 + ")";
                mrows = jdbc.queryForList(sqlFb1, paramsFb1);
                if (!mrows.isEmpty()) {
                    log.warn("[ReverseDashboardService] metric 走 fallback1: scheme_code={} 无数据, 复用 data_date={} 任意 scheme 的数据 ({} 条)",
                            schemeCode, dataDateStr, mrows.size());
                }
            }
            // Fallback 2: 任意 data_date 任意 scheme_code (兜底, 一定有数据)
            if (mrows.isEmpty()) {
                Object[] paramsFb2 = new Object[nodes.size() + DEFAULT_METRIC_TYPES.size()];
                int i3 = 0;
                for (Map<String, Object> n : nodes) { paramsFb2[i3++] = n.get("node_code"); }
                for (String m : DEFAULT_METRIC_TYPES) { paramsFb2[i3++] = m; }
                String sqlFb2 = "SELECT node_code, metric_type, current_value"
                              + " FROM prcp_metric_coefficient WHERE is_deleted = 0"
                              + " AND node_code IN (" + ph + ")"
                              + " AND metric_type IN (" + ph2 + ")";
                mrows = jdbc.queryForList(sqlFb2, paramsFb2);
                if (!mrows.isEmpty()) {
                    log.warn("[ReverseDashboardService] metric 走 fallback2: scheme_code={} + data_date={} 全无, 复用任意历史数据 ({} 条)",
                            schemeCode, dataDateStr, mrows.size());
                }
            }
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
            // 关键修复 v2: 3 级 fallback
            //  1) scheme_code = 当前反算方案 code (新方案可能没数据, 空)
            //  2) scheme_code = coa_code (历史数据, prcp_metric_coefficient 早期用 coa_code 当 scheme_code)
            //  3) 任意 scheme_code (兜底, 一定有数据)
            String coaCode = (String) rev.get("coaCode");
            Object[] params = new Object[nodes.size() + DEFAULT_METRIC_TYPES.size() + 1];
            params[0] = schemeCode; int idx = 1;
            for (Map<String, Object> n : nodes) { params[idx++] = n.get("node_code"); }
            for (String m : DEFAULT_METRIC_TYPES) { params[idx++] = m; }
            String sql = "SELECT data_date, metric_type, current_value"
                       + " FROM prcp_metric_coefficient WHERE is_deleted = 0 AND scheme_code = ?"
                       + " AND node_code IN (" + ph + ") AND metric_type IN (" + ph2 + ")";
            List<Map<String, Object>> allMetric = jdbc.queryForList(sql, params);
            // Fallback 1: coa_code (历史数据兼容)
            if (allMetric.isEmpty() && coaCode != null && !coaCode.equals(schemeCode)) {
                Object[] params2 = new Object[nodes.size() + DEFAULT_METRIC_TYPES.size() + 1];
                params2[0] = coaCode; int i2 = 1;
                for (Map<String, Object> n : nodes) { params2[i2++] = n.get("node_code"); }
                for (String m : DEFAULT_METRIC_TYPES) { params2[i2++] = m; }
                allMetric = jdbc.queryForList(sql, params2);
                if (!allMetric.isEmpty()) {
                    log.warn("[ReverseDashboardService] trend 走 fallback1: scheme_code={} 无数据, 复用 coa_code={} ({} 条)",
                            schemeCode, coaCode, allMetric.size());
                }
            }
            // Fallback 2: 任意 scheme_code (兜底)
            if (allMetric.isEmpty()) {
                Object[] params2 = new Object[nodes.size() + DEFAULT_METRIC_TYPES.size()];
                int i2 = 0;
                for (Map<String, Object> n : nodes) { params2[i2++] = n.get("node_code"); }
                for (String m : DEFAULT_METRIC_TYPES) { params2[i2++] = m; }
                String sql2 = "SELECT data_date, metric_type, current_value"
                            + " FROM prcp_metric_coefficient WHERE is_deleted = 0"
                            + " AND node_code IN (" + ph + ") AND metric_type IN (" + ph2 + ")";
                allMetric = jdbc.queryForList(sql2, params2);
                if (!allMetric.isEmpty()) {
                    log.warn("[ReverseDashboardService] trend 走 fallback2: scheme_code={} + coa_code={} 全无, 复用任意历史数据 ({} 条)",
                            schemeCode, coaCode, allMetric.size());
                }
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
            categoryDistribution.put("balance_date", baseDate);
            categoryDistribution.put("balance_source", "prcp_data_basic");

            // 9a. 当月预测大类分布 (prcp_data_reverse, 仅末级节点) — 余额口径切换用
            Map<String, BigDecimal> catDistForecast = new LinkedHashMap<>();
            for (String c : CATEGORIES) catDistForecast.put(c, BigDecimal.ZERO);
            for (Map<String, Object> n : nodes) {
                String nc = (String) n.get("node_code");
                if (!isLeafFlag(nc, (String) n.get("path"), parentSet, basicMap)) continue;
                String cat = classify(nc, (String) n.get("path"));
                BigDecimal bal = revMap.containsKey(nc)
                        ? (BigDecimal) revMap.get(nc).getOrDefault("current_balance", BigDecimal.ZERO)
                        : BigDecimal.ZERO;
                catDistForecast.merge(cat, bal, BigDecimal::add);
            }
            Map<String, Object> byBalForecast = new LinkedHashMap<>();
            for (Map.Entry<String, BigDecimal> e : catDistForecast.entrySet()) {
                byBalForecast.put(e.getKey(), e.getValue().setScale(2, RoundingMode.HALF_UP));
            }
            categoryDistribution.put("by_balance_forecast", byBalForecast);
            categoryDistribution.put("forecast_date", dataDateStr);
            categoryDistribution.put("forecast_source", "prcp_data_reverse");

            // 9b. 基期大类分布 (prcp_data_basic 基准日, 仅末级节点)
            Map<String, BigDecimal> catDistBase = new LinkedHashMap<>();
            for (String c : CATEGORIES) catDistBase.put(c, BigDecimal.ZERO);
            for (Map<String, Object> n : nodes) {
                String nc = (String) n.get("node_code");
                if (!isLeafFlag(nc, (String) n.get("path"), parentSet, basicMap)) continue;
                Map<String, Object> b = basicMap.get(nc);
                if (b == null) continue;
                String cat = classify(nc, (String) n.get("path"));
                catDistBase.merge(cat, (BigDecimal) b.getOrDefault("base_balance", BigDecimal.ZERO), BigDecimal::add);
            }
            Map<String, Object> byBalBase = new LinkedHashMap<>();
            for (Map.Entry<String, BigDecimal> e : catDistBase.entrySet()) {
                byBalBase.put(e.getKey(), e.getValue().setScale(2, RoundingMode.HALF_UP));
            }
            categoryDistribution.put("by_balance_base", byBalBase);

            // 10. 节点详情矩阵
            List<Map<String, Object>> nodeMatrix = new ArrayList<>();
            for (Map<String, Object> n : nodes) {
                String nc = (String) n.get("node_code");
                String cat = classify(nc, (String) n.get("path"));
                Map<String, BigDecimal> mv = metricMap.getOrDefault(nc, Collections.emptyMap());
                Map<String, Object> rd = revMap.getOrDefault(nc, Collections.emptyMap());
                Map<String, Object> bs = basicMap.getOrDefault(nc, Collections.emptyMap());
                Map<String, Object> nm = new LinkedHashMap<>();
                nm.put("coa_node_id", n.get("coa_node_id"));
                nm.put("node_code", nc);
                nm.put("node_name", n.get("node_name"));
                nm.put("node_level", n.get("node_level"));
                nm.put("category", cat);
                nm.put("is_leaf", isLeafFlag(nc, (String) n.get("path"), parentSet, basicMap));
                nm.put("current_balance", rd.getOrDefault("current_balance", BigDecimal.ZERO));
                nm.put("base_balance", bs.getOrDefault("base_balance", BigDecimal.ZERO));
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

            // 12b. 目标达成（目标 vs 真实指标 vs 完成率）— 对位 Python reverse_dashboard.py:830-900
            //  目标来自 prcp_reverse_target（组合反算方案的目标约束）
            //  完成率按 constraint_type 计算；另给出 M12（12 个月后）的完成率
            String m12Date = null;
            List<Map<String, Object>> m12Rows = jdbc.queryForList(
                    "SELECT data_date AS dataDate FROM prcp_data_reverse"
                  + " WHERE scheme_code = ? AND run_id = ? AND is_deleted = 0"
                  + " AND offset_unit = 'M' AND date_offset = 12 ORDER BY data_date DESC LIMIT 1",
                    schemeCode, runId);
            if (!m12Rows.isEmpty()) {
                Object d = m12Rows.get(0).get("dataDate");
                m12Date = d == null ? null : d.toString();
            }
            List<Map<String, Object>> targets = buildTargets(
                    revId, schemeCode, runId, nodes, metricMap, m12Date);

            // 12c. 基期 KPI（date_offset=1）— 用于 KPI 卡的基期对比
            Map<String, Object> kpiBase = buildKpiBase(schemeCode, runId, nodes);

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
            resp.put("m12_date", m12Date);
            resp.put("kpi", kpi);
            resp.put("kpi_base", kpiBase);
            resp.put("trend", trendObj);
            resp.put("category_distribution", categoryDistribution);
            resp.put("node_matrix", nodeMatrix);
            resp.put("top_nodes", topNodes);
            resp.put("targets", targets);
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
     * <p>末级账户册余额走势 — 对位 Python reverse_dashboard.py:584-681 balance-trend</p>
     *
     * <p>返回:
     * <ul>
     *   <li>{@code months}: [{offset, date}] 逐月</li>
     *   <li>{@code categories}: [{key, values}] 5 大类逐月合计 (仅末级节点)</li>
     *   <li>{@code nodes}: [{code, name, category, values, total}] 末级节点逐月余额 (按 total 降序)</li>
     * </ul>
     *
     * @param schemeCode 反算方案编码 (默认 "2026")
     * @param runId      Run ID (缺省取最新 SUCCESS)
     * @return R.ok({scheme_code, run_id, months, categories, nodes})
     */
    public R<Map<String, Object>> balanceTrend(String schemeCode, Long runId) {
        if (schemeCode == null || schemeCode.isEmpty()) schemeCode = "2026";

        // 1. 方案
        Map<String, Object> rev = jdbc.queryForMap(
                "SELECT id AS revId, scheme_code AS schemeCode, coa_scheme_id AS coaSchemeId,"
              + "       data_date AS dataDate, horizon_months AS horizonMonths"
              + "  FROM prcp_reverse_scheme WHERE scheme_code = ? AND is_deleted = 0", schemeCode);
        Long revId = ((Number) rev.get("revId")).longValue();
        Long coaSchemeId = rev.get("coaSchemeId") == null ? null
                : ((Number) rev.get("coaSchemeId")).longValue();

        // 2. Run（缺省取最新 SUCCESS）
        if (runId == null) {
            List<Map<String, Object>> lr = jdbc.queryForList(
                    "SELECT id FROM prcp_reverse_run WHERE scheme_id = ? AND status = 'SUCCESS' AND is_deleted = 0"
                  + " ORDER BY id DESC LIMIT 1", revId);
            if (lr.isEmpty()) throw BizException.notFound("该方案无 SUCCESS 运行记录");
            runId = ((Number) lr.get(0).get("id")).longValue();
        }

        // 3. 节点元数据 + 末级标记
        List<Map<String, Object>> nodes = new ArrayList<>();
        if (coaSchemeId != null) {
            nodes = jdbc.queryForList(
                    "SELECT id, node_code, node_name, node_level, path"
                  + " FROM prcp_coa_node WHERE scheme_id = ? AND is_deleted = 0 ORDER BY path, node_level",
                    coaSchemeId);
            for (Map<String, Object> n : nodes) n.put("coa_node_id", n.get("id"));
        }
        Set<String> parentSet = computeParentSet(nodes);

        // 4. 逐月数据
        List<Map<String, Object>> monthRows = jdbc.queryForList(
                "SELECT date_offset, data_date, coa_node_id, node_code, current_balance"
              + "  FROM prcp_data_reverse WHERE scheme_code = ? AND run_id = ? AND is_deleted = 0"
              + " AND offset_unit = 'M' AND date_offset >= 1 ORDER BY date_offset ASC, coa_node_id",
                schemeCode, runId);

        Map<Integer, String> monthDate = new LinkedHashMap<>();
        Map<Integer, Map<String, BigDecimal>> monthByNode = new LinkedHashMap<>();
        for (Map<String, Object> r : monthRows) {
            int off = ((Number) r.get("date_offset")).intValue();
            monthDate.putIfAbsent(off, r.get("data_date") == null ? null : r.get("data_date").toString());
            monthByNode.computeIfAbsent(off, k -> new LinkedHashMap<>())
                    .put((String) r.get("node_code"), toBD(r.get("current_balance")));
        }

        // 5. 组装
        List<Map<String, Object>> months = new ArrayList<>();
        for (Map.Entry<Integer, String> e : monthDate.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("offset", e.getKey());
            m.put("date", e.getValue());
            months.add(m);
        }

        // 5a. 末级节点集合（按 prcp_data_basic.is_leaf 优先, 回退 path 推断）
        Map<String, Boolean> leafFlag = new LinkedHashMap<>();
        if (!nodes.isEmpty()) {
            String ph = String.join(",", Collections.nCopies(nodes.size(), "?"));
            Object[] params = new Object[nodes.size() + 1];
            params[0] = rev.get("dataDate");
            int i = 1;
            for (Map<String, Object> n : nodes) { params[i++] = n.get("node_code"); }
            List<Map<String, Object>> brows = jdbc.queryForList(
                    "SELECT node_code, is_leaf FROM prcp_data_basic WHERE is_deleted = 0"
                  + " AND data_date = ? AND node_code IN (" + ph + ")", params);
            for (Map<String, Object> r : brows) {
                Object v = r.get("is_leaf");
                boolean leaf = v instanceof Boolean ? (Boolean) v
                        : (v instanceof Number ? ((Number) v).intValue() == 1
                        : isLeaf((String) r.get("node_code"), null, parentSet));
                leafFlag.put((String) r.get("node_code"), leaf);
            }
        }

        // 5b. 大类逐月汇总
        Map<String, List<BigDecimal>> catVals = new LinkedHashMap<>();
        for (String c : CATEGORIES) catVals.put(c, new ArrayList<>());
        // 5c. 节点逐月
        Map<String, List<BigDecimal>> nodeVals = new LinkedHashMap<>();
        Map<String, Map<String, Object>> nodeMeta = new LinkedHashMap<>();

        // 月份顺序 (offset 列表, 与各 values 数组的索引一一对应)
        List<Integer> offsets = new ArrayList<>(monthDate.keySet());

        for (Map<String, Object> n : nodes) {
            String nc = (String) n.get("node_code");
            boolean leaf = leafFlag.containsKey(nc) ? leafFlag.get(nc)
                    : isLeaf(nc, (String) n.get("path"), parentSet);
            if (!leaf) continue;
            String cat = classify(nc, (String) n.get("path"));
            List<BigDecimal> vals = new ArrayList<>();
            BigDecimal total = BigDecimal.ZERO;
            for (Integer off : offsets) {
                BigDecimal v = monthByNode.getOrDefault(off, Collections.emptyMap())
                        .getOrDefault(nc, BigDecimal.ZERO);
                vals.add(v);
                total = total.add(v.abs());
            }
            nodeVals.put(nc, vals);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("code", nc);
            meta.put("name", n.get("node_name"));
            meta.put("category", cat);
            meta.put("total", total.setScale(2, RoundingMode.HALF_UP));
            nodeMeta.put(nc, meta);
        }
        for (int mi = 0; mi < offsets.size(); mi++) {
            Integer off = offsets.get(mi);
            for (String c : CATEGORIES) {
                BigDecimal sum = BigDecimal.ZERO;
                for (Map.Entry<String, List<BigDecimal>> nv : nodeVals.entrySet()) {
                    String cat = (String) nodeMeta.get(nv.getKey()).get("category");
                    if (!c.equals(cat)) continue;
                    sum = sum.add(nv.getValue().get(mi));
                }
                catVals.get(c).add(sum.setScale(2, RoundingMode.HALF_UP));
            }
        }

        List<Map<String, Object>> categories = new ArrayList<>();
        for (Map.Entry<String, List<BigDecimal>> e : catVals.entrySet()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("key", e.getKey());
            c.put("values", e.getValue());
            categories.add(c);
        }

        List<Map<String, Object>> nodeList = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : nodeMeta.entrySet()) {
            Map<String, Object> nm = new LinkedHashMap<>(e.getValue());
            nm.put("values", nodeVals.get(e.getKey()));
            nodeList.add(nm);
        }
        nodeList.sort((a, b) -> {
            BigDecimal ta = (BigDecimal) a.get("total");
            BigDecimal tb = (BigDecimal) b.get("total");
            return tb.compareTo(ta);
        });

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("scheme_code", schemeCode);
        resp.put("run_id", runId);
        resp.put("months", months);
        resp.put("categories", categories);
        resp.put("nodes", nodeList);
        return R.ok(resp);
    }

    /**
     * <p>目标达成计算 — 对位 Python reverse_dashboard.py:830-900</p>
     *
     * <p>目标来自 {@code prcp_reverse_target}（组合反算方案的目标约束）；
     * 完成率按 {@code constraint_type} 计算；另给出 M12（12 个月后）的完成率。</p>
     *
     * @param revId     反算方案 ID
     * @param schemeCode 反算方案编码
     * @param runId     Run ID
     * @param nodes     节点列表
     * @param metricMap 当前月份的节点级指标
     * @return 目标达成列表
     */
    private List<Map<String, Object>> buildTargets(Long revId, String schemeCode, Long runId,
                                                   List<Map<String, Object>> nodes,
                                                   Map<String, Map<String, BigDecimal>> metricMap,
                                                   String m12Date) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (revId == null) return out;

        Map<String, Map<String, BigDecimal>> metricMapM12 =
                m12Date == null ? Collections.emptyMap()
                        : loadMetricMap(schemeCode, null, nodes, m12Date);

        List<Map<String, Object>> targetRows = jdbc.queryForList(
                "SELECT kpi_code AS kpiCode, target_name AS targetName,"
              + "       target_value AS targetValue, constraint_type AS constraintType,"
              + "       weight, horizon_month AS horizonMonth"
              + "  FROM prcp_reverse_target WHERE scheme_id = ? AND is_deleted = 0"
              + " ORDER BY sort_order", revId);

        for (Map<String, Object> t : targetRows) {
            String kpiCode = (String) t.get("kpiCode");
            String mtype = REG_TO_METRIC.get(kpiCode == null ? "" : kpiCode);
            BigDecimal cur = mtype == null ? null : avgMetric(nodes, metricMap, mtype);
            BigDecimal m12 = (mtype == null || metricMapM12.isEmpty())
                    ? null : avgMetric(nodes, metricMapM12, mtype);
            BigDecimal tv = toBD(t.get("targetValue"));
            String ct = t.get("constraintType") == null ? "GE" : t.get("constraintType").toString();

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kpi_code", kpiCode);
            row.put("metric_type", mtype);
            row.put("target_name", t.get("targetName"));
            row.put("target_value", tv);
            row.put("constraint_type", ct);
            row.put("weight", toBD(t.get("weight")));
            row.put("horizon_month", t.get("horizonMonth"));
            row.put("current_value", cur);
            row.put("current_rate", completion(cur, tv, ct));
            row.put("m12_value", m12);
            row.put("m12_rate", completion(m12, tv, ct));
            row.put("achieved", cur == null ? null
                    : ("GE".equals(ct) ? cur.compareTo(tv) >= 0
                      : ("LE".equals(ct) ? cur.compareTo(tv) <= 0
                      : cur.subtract(tv).abs().compareTo(new BigDecimal("0.000000001")) < 0)));
            out.add(row);
        }
        return out;
    }

    /**
     * <p>末级节点判定 — 优先用 prcp_data_basic.is_leaf, 缺失时回退 path 前缀推断</p>
     *
     * @param nodeCode  节点编码
     * @param path      节点 path
     * @param parentSet path 前缀法推断的父节点集合
     * @param basicMap  基础数据 (含 is_leaf)
     * @return 是否末级节点
     */
    private static boolean isLeafFlag(String nodeCode, String path, Set<String> parentSet,
                                      Map<String, Map<String, Object>> basicMap) {
        Map<String, Object> b = basicMap.get(nodeCode);
        if (b != null && b.get("is_leaf") != null) {
            Object v = b.get("is_leaf");
            if (v instanceof Boolean) return (Boolean) v;
            if (v instanceof Number) return ((Number) v).intValue() == 1;
        }
        return isLeaf(nodeCode, path, parentSet);
    }

    /**
     * <p>完成率计算 (%) — 对位 Python {@code _completion}</p>
     *
     * <p>公式:
     * <ul>
     *   <li>GE: {@code actual / target * 100}</li>
     *   <li>LE: {@code target / actual * 100}</li>
     *   <li>EQ: {@code (1 - |actual - target| / |target|) * 100}</li>
     * </ul>
     *
     * <p><b>量纲保护</b>: 目标与实际相差 3 个数量级以上 (|actual/target| &lt; 1e-3 或 &gt; 1e3)
     * 视为不可比 → 返回 null (例如目标「-5 亿」vs 实际「8.05%」)</p>
     *
     * @param actual 实际值
     * @param target 目标值
     * @param ctype  约束类型 GE/LE/EQ
     * @return 完成率 (%) 或 null
     */
    private static BigDecimal completion(BigDecimal actual, BigDecimal target, String ctype) {
        if (actual == null || target == null || target.compareTo(BigDecimal.ZERO) == 0) return null;
        BigDecimal ratio = actual.divide(target.abs(), 10, RoundingMode.HALF_UP).abs();
        if (ratio.compareTo(new BigDecimal("0.001")) < 0 || ratio.compareTo(new BigDecimal("1000")) > 0) {
            return null;   // 量纲不可比
        }
        String ct = ctype == null ? "GE" : ctype;
        if ("GE".equals(ct)) {
            return actual.multiply(new BigDecimal("100")).divide(target, 2, RoundingMode.HALF_UP);
        }
        if ("LE".equals(ct)) {
            if (actual.compareTo(BigDecimal.ZERO) == 0) return null;
            return target.multiply(new BigDecimal("100")).divide(actual, 2, RoundingMode.HALF_UP);
        }
        if ("EQ".equals(ct)) {
            BigDecimal diff = actual.subtract(target).abs();
            BigDecimal r = BigDecimal.ONE.subtract(diff.divide(target.abs(), 10, RoundingMode.HALF_UP));
            return r.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP);
        }
        return null;
    }

    /**
     * <p>节点级指标集合 → 某指标的算术平均 — 对位 Python {@code _avg_metric}</p>
     *
     * @param nodes     节点列表
     * @param metricMap 节点 → 指标 → 值
     * @param mtype     指标类型
     * @return 平均值 (保留 4 位) 或 null
     */
    private static BigDecimal avgMetric(List<Map<String, Object>> nodes,
                                        Map<String, Map<String, BigDecimal>> metricMap, String mtype) {
        if (mtype == null) return null;
        BigDecimal sum = BigDecimal.ZERO;
        int cnt = 0;
        for (Map<String, Object> n : nodes) {
            Map<String, BigDecimal> mv = metricMap.get(n.get("node_code"));
            if (mv == null) continue;
            BigDecimal v = mv.get(mtype);
            if (v == null) continue;
            sum = sum.add(v);
            cnt++;
        }
        if (cnt == 0) return null;
        return sum.divide(BigDecimal.valueOf(cnt), 4, RoundingMode.HALF_UP);
    }

    /**
     * <p>取某日的节点级指标集合 (带 3 级 fallback) — 对位 Python {@code _load_metric_map}</p>
     *
     * @param schemeCode 反算方案编码
     * @param coaCode    账户册方案编码 (历史兼容)
     * @param nodes      节点列表
     * @param dataDate   数据日期 (yyyy-MM-dd)
     * @return 节点 → 指标 → 值
     */
    private Map<String, Map<String, BigDecimal>> loadMetricMap(
            String schemeCode, String coaCode, List<Map<String, Object>> nodes, String dataDate) {
        Map<String, Map<String, BigDecimal>> metricMap = new LinkedHashMap<>();
        if (nodes.isEmpty() || dataDate == null) return metricMap;
        String ph = String.join(",", Collections.nCopies(nodes.size(), "?"));
        String ph2 = String.join(",", Collections.nCopies(DEFAULT_METRIC_TYPES.size(), "?"));

        // ① scheme_code = 反算方案 + data_date
        Object[] params = new Object[nodes.size() + DEFAULT_METRIC_TYPES.size() + 2];
        params[0] = schemeCode; params[1] = dataDate;
        int idx = 2;
        for (Map<String, Object> n : nodes) { params[idx++] = n.get("node_code"); }
        for (String m : DEFAULT_METRIC_TYPES) { params[idx++] = m; }
        String sql = "SELECT node_code, metric_type, current_value"
                   + " FROM prcp_metric_coefficient WHERE is_deleted = 0 AND scheme_code = ?"
                   + " AND data_date = ? AND node_code IN (" + ph + ")"
                   + " AND metric_type IN (" + ph2 + ")";
        List<Map<String, Object>> rows = jdbc.queryForList(sql, params);

        // ② 同 data_date 任意 scheme_code
        if (rows.isEmpty()) {
            Object[] p2 = new Object[nodes.size() + DEFAULT_METRIC_TYPES.size() + 1];
            p2[0] = dataDate;
            int i = 1;
            for (Map<String, Object> n : nodes) { p2[i++] = n.get("node_code"); }
            for (String m : DEFAULT_METRIC_TYPES) { p2[i++] = m; }
            String s2 = "SELECT node_code, metric_type, current_value"
                      + " FROM prcp_metric_coefficient WHERE is_deleted = 0"
                      + " AND data_date = ? AND node_code IN (" + ph + ")"
                      + " AND metric_type IN (" + ph2 + ")";
            rows = jdbc.queryForList(s2, p2);
        }
        // ③ 任意 scheme_code + 任意 data_date (兜底)
        if (rows.isEmpty()) {
            Object[] p3 = new Object[nodes.size() + DEFAULT_METRIC_TYPES.size()];
            int i = 0;
            for (Map<String, Object> n : nodes) { p3[i++] = n.get("node_code"); }
            for (String m : DEFAULT_METRIC_TYPES) { p3[i++] = m; }
            String s3 = "SELECT node_code, metric_type, current_value"
                      + " FROM prcp_metric_coefficient WHERE is_deleted = 0"
                      + " AND node_code IN (" + ph + ")"
                      + " AND metric_type IN (" + ph2 + ")";
            rows = jdbc.queryForList(s3, p3);
        }
        for (Map<String, Object> r : rows) {
            String nc = (String) r.get("node_code");
            String mt = (String) r.get("metric_type");
            metricMap.computeIfAbsent(nc, k -> new LinkedHashMap<>()).put(mt, toBD(r.get("current_value")));
        }
        return metricMap;
    }

    /**
     * <p>基期 KPI (date_offset = 1 的月份) — 对位 Python {@code kpi_base}</p>
     *
     * <p>用于 KPI 卡的「基期 vs 当前期」对比标签。</p>
     *
     * @param schemeCode 反算方案编码
     * @param runId      Run ID
     * @param nodes      节点列表
     * @return {period, asset_total, liability_total, equity_total, loan_weighted_rate, metric_avg}
     */
    private Map<String, Object> buildKpiBase(String schemeCode, Long runId,
                                             List<Map<String, Object>> nodes) {
        Map<String, Object> base = new LinkedHashMap<>();
        // 基期 data_date
        List<Map<String, Object>> ddRows = jdbc.queryForList(
                "SELECT data_date AS dataDate FROM prcp_data_reverse"
              + " WHERE scheme_code = ? AND run_id = ? AND date_offset = 1"
              + " AND offset_unit = 'M' AND is_deleted = 0 ORDER BY data_date DESC LIMIT 1",
                schemeCode, runId);
        if (ddRows.isEmpty()) return base;
        Object dd = ddRows.get(0).get("dataDate");
        String baseDate = dd == null ? null : dd.toString();
        base.put("period", baseDate);
        if (baseDate == null) return base;

        // 基期余额
        List<Map<String, Object>> revRows = jdbc.queryForList(
                "SELECT node_code, current_balance, weighted_rate"
              + " FROM prcp_data_reverse WHERE scheme_code = ? AND run_id = ? AND date_offset = 1"
              + " AND offset_unit = 'M' AND is_deleted = 0",
                schemeCode, runId);
        Map<String, Map<String, Object>> revMap = new LinkedHashMap<>();
        for (Map<String, Object> r : revRows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("current_balance", toBD(r.get("current_balance")));
            m.put("weighted_rate", toBD(r.get("weighted_rate")));
            revMap.put((String) r.get("node_code"), m);
        }
        base.put("asset_total", sumByCat(nodes, revMap, "ASSET"));
        base.put("liability_total", sumByCat(nodes, revMap, "LIABILITY"));
        base.put("equity_total", sumByCat(nodes, revMap, "EQUITY"));

        // 基期贷款利率 (同 7a 口径)
        BigDecimal loanNum = BigDecimal.ZERO, loanDen = BigDecimal.ZERO;
        for (Map<String, Object> n : nodes) {
            String nc = (String) n.get("node_code");
            if (nc == null || !(nc.startsWith("S01010101") || nc.startsWith("S01010102")
                    || nc.startsWith("S01010103") || nc.startsWith("S01010104")
                    || nc.startsWith("S01010105"))) continue;
            Map<String, Object> r = revMap.get(nc);
            if (r == null) continue;
            BigDecimal bal = (BigDecimal) r.getOrDefault("current_balance", BigDecimal.ZERO);
            BigDecimal rate = (BigDecimal) r.getOrDefault("weighted_rate", BigDecimal.ZERO);
            if (bal == null || bal.abs().compareTo(new BigDecimal("0.01")) < 0) continue;
            loanNum = loanNum.add(bal.multiply(rate));
            loanDen = loanDen.add(bal);
        }
        base.put("loan_weighted_rate", loanDen.compareTo(BigDecimal.ZERO) > 0
                ? loanNum.divide(loanDen, 6, RoundingMode.HALF_UP) : null);

        // 基期指标均值
        Map<String, Map<String, BigDecimal>> metricMap = loadMetricMap(schemeCode, null, nodes, baseDate);
        Map<String, Object> mAvg = new LinkedHashMap<>();
        for (String m : DEFAULT_METRIC_TYPES) mAvg.put(m, avgMetric(nodes, metricMap, m));
        base.put("metric_avg", mAvg);
        return base;
    }

    /**
     * <p>仅算叶子节点（节点 N 是 leaf ⇔ 没有其他节点 M 使得 M.path 以 N.path + "/" 开头）
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