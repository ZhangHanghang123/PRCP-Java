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
     * @return R.ok(Map); 包含 kpi/trend/category_distribution/node_matrix/top_nodes(末级节点余额排行)/risk_alerts/kpi_scores
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

        // 2. Run（缺省自动选取, 含 data_reverse 关联断裂回退）
        runId = resolveRunId(revId, schemeCode, runId);

        // 3. 当前月份 data_date
        List<Map<String, Object>> ddRows = jdbc.queryForList(
                "SELECT data_date AS dataDate FROM prcp_data_reverse"
              + " WHERE scheme_code = ? AND run_id = ? AND date_offset = ?"
              + " AND offset_unit = 'M' AND is_deleted = 0 ORDER BY data_date DESC LIMIT 1",
                schemeCode, runId, dateOffset);
        if (ddRows.isEmpty()) throw BizException.notFound("未找到 M" + dateOffset);
        Object dataDate = ddRows.get(0).get("dataDate");
        String dataDateStr = dataDate == null ? null : dataDate.toString();

        // 4. 节点元数据 —— 覆盖【所有 COA 账户册方案】的节点
        //  (不再限定当前反算方案关联的 coa_scheme_id: 多套账户册并存时, Top 10 节点
        //   与节点矩阵需跨方案统一排名; 且方案若未正确关联 COA 也不会导致整页空白)
        List<Map<String, Object>> nodes = jdbc.queryForList(
                "SELECT n.id, n.node_code, n.node_name, n.node_level, n.path,"
              + "       n.scheme_id AS coa_scheme_id, c.scheme_code AS coa_scheme_code,"
              + "       c.scheme_name AS coa_scheme_name"
              + "  FROM prcp_coa_node n"
              + "  LEFT JOIN prcp_coa_scheme c ON c.id = n.scheme_id"
              + " WHERE n.is_deleted = 0"
              + " ORDER BY n.scheme_id, n.path, n.node_level");
        // 改回 Python key 名（snake_case 一致）
        for (Map<String, Object> n : nodes) {
            n.put("coa_node_id", n.get("id"));
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

        // 5a. 末级节点余额回退（数据源缺失兜底）
        //  ⚠ 部分引擎（如 Java 版独立引擎 8009）产出的 Run 只写了父节点且余额全 0，
        //    prcp_data_reverse 里根本没有末级节点 → 节点矩阵 / KPI 卡资产余额 /
        //    大类预测分布 全部为 0。此处把末级节点用基准日余额补进 revMap，
        //    保证页面各处口径一致（与 balance-trend / 导出的回退口径一致）。
        boolean leafBalanceFallback = false;
        int revLeafWithData = 0;
        for (Map.Entry<String, Map<String, Object>> e : revMap.entrySet()) {
            if (!isLeafFlag(e.getKey(), null, parentSet, basicMap)) continue;
            BigDecimal cb = (BigDecimal) e.getValue().getOrDefault("current_balance", BigDecimal.ZERO);
            if (cb != null && cb.abs().compareTo(BigDecimal.ZERO) > 0) revLeafWithData++;
        }
        if (revLeafWithData == 0 && !basicMap.isEmpty()) {
            for (Map<String, Object> n : nodes) {
                String nc = (String) n.get("node_code");
                if (!isLeafFlag(nc, (String) n.get("path"), parentSet, basicMap)) continue;
                Map<String, Object> b = basicMap.get(nc);
                if (b == null) continue;
                BigDecimal bb = (BigDecimal) b.getOrDefault("base_balance", BigDecimal.ZERO);
                if (bb == null || bb.signum() == 0) continue;
                Map<String, Object> m = revMap.get(nc);
                if (m == null) {
                    // 该末级节点在预测数据里完全没有 → 新建一条
                    m = new LinkedHashMap<>();
                    m.put("coa_node_id", n.get("id"));
                    m.put("avg_balance", bb);
                    // 回退节点: 引擎未产出利率 → 置 null (表示无数据), 不填 0 冒充真实利率
                    m.put("weighted_rate", null);
                    m.put("interest_amount", BigDecimal.ZERO);
                    m.put("risk_weight", BigDecimal.ZERO);
                    revMap.put(nc, m);
                }
                // ⚠ 已在 revMap 但余额为 0（如权益类唯一节点 S030000000000，
                //   引擎写了父节点行但余额为 0）→ 同样要用基准日回退，否则恒为 0
                BigDecimal cur = (BigDecimal) m.getOrDefault("current_balance", BigDecimal.ZERO);
                if (cur == null || cur.signum() == 0) {
                    m.put("current_balance", bb);
                    m.put("balance_fallback", Boolean.TRUE);
                    // 该节点余额由基准日回退而来, 引擎未产出对应利率 → 置 null, 不填 0
                    BigDecimal mRate = (BigDecimal) m.getOrDefault("weighted_rate", BigDecimal.ZERO);
                    if (mRate == null || mRate.signum() == 0) m.put("weighted_rate", null);
                    leafBalanceFallback = true;
                }
            }
            if (leafBalanceFallback) {
                log.warn("[ReverseDashboardService] Run {} 的预测数据无末级节点余额, 已用基准日 {} 余额回退补齐 ({} 个节点)",
                        runId, baseDate, revMap.size());
            }
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
        BigDecimal assetTotal = sumByCat(nodes, revMap, "ASSET", basicMap);
        BigDecimal liabilityTotal = sumByCat(nodes, revMap, "LIABILITY", basicMap);
        BigDecimal equityTotal = sumByCat(nodes, revMap, "EQUITY", basicMap);
        BigDecimal offBalance = sumByCat(nodes, revMap, "OFF_BALANCE", basicMap);

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
        int loanRateNodes = 0;          // 参与计算且有有效利率的节点数
        int loanBalNodes = 0;           // 参与计算的节点数（余额有效）
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
            loanBalNodes++;
            // ⚠ 利率为 0 且该节点是基准日回退来的（引擎未产出利率）→ 不参与加权，
            //    否则会算出 0.00% 冒充真实利率。
            if (rate == null || rate.signum() == 0) continue;
            loanRateNodes++;
            loanNum = loanNum.add(bal.multiply(rate));
            loanDen = loanDen.add(bal);
        }
        // 只有在「有余额的节点」与「有利率的节点」数量一致时才输出加权平均；
        // 否则说明引擎未产出利率数据 → 返回 null，前端显示「无数据」而非 0.00%
        if (loanDen.compareTo(BigDecimal.ZERO) > 0 && loanBalNodes > 0 && loanRateNodes == loanBalNodes) {
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
        // 只取 date_offset >= 1: offset=0 是基准日(与 offset=1 同为 data_date),
        // 若一并取会导致趋势图 X 轴出现两个相同的 YYMMDD 标签 (如 251201 重复两次)
        List<Map<String, Object>> monthRows = jdbc.queryForList(
                "SELECT date_offset, data_date FROM prcp_data_reverse"
              + " WHERE scheme_code = ? AND run_id = ? AND offset_unit = 'M' AND date_offset >= 1 AND is_deleted = 0"
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
            //  ⚠ 数据源回退: 部分引擎 (如 Java 版独立引擎) 产出的 Run 只写父节点且余额为 0,
            //    末级节点在 prcp_data_reverse 中不存在 → 预测口径分布会全为 0。
            //    此时末级节点余额回退到 prcp_data_basic 基准日 (与 balance-trend / 导出口径一致)。
            Map<String, BigDecimal> catDistForecast = new LinkedHashMap<>();
            for (String c : CATEGORIES) catDistForecast.put(c, BigDecimal.ZERO);
            for (Map<String, Object> n : nodes) {
                String nc = (String) n.get("node_code");
                if (!isLeafFlag(nc, (String) n.get("path"), parentSet, basicMap)) continue;
                String cat = classify(nc, (String) n.get("path"));
                BigDecimal bal = revMap.containsKey(nc)
                        ? (BigDecimal) revMap.get(nc).getOrDefault("current_balance", BigDecimal.ZERO)
                        : BigDecimal.ZERO;
                if (bal == null || bal.signum() == 0) {
                    // 预测余额缺失 → 回退基准日末级余额
                    Map<String, Object> b = basicMap.get(nc);
                    if (b != null) {
                        BigDecimal bb = (BigDecimal) b.getOrDefault("base_balance", BigDecimal.ZERO);
                        if (bb != null && bb.signum() != 0) bal = bb;
                    }
                }
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
                nm.put("coa_scheme_id", n.get("coa_scheme_id"));
                nm.put("coa_scheme_code", n.get("coa_scheme_code"));
                nm.put("coa_scheme_name", n.get("coa_scheme_name"));
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

            // 11. 末级节点余额排行 (全部末级节点, 按 |当前余额| 降序)
            //  口径: 只取末级节点 (is_leaf), 避免父子节点重复计入同一笔余额;
            //        覆盖全部账户册方案 (COA), 不做 Top N 截断。
            List<Map<String, Object>> topNodes = new ArrayList<>();
            for (Map<String, Object> nm : nodeMatrix) {
                if (Boolean.TRUE.equals(nm.get("is_leaf"))) topNodes.add(nm);
            }
            topNodes.sort((a, b) -> {
                BigDecimal ab = (BigDecimal) a.get("current_balance");
                BigDecimal bb = (BigDecimal) b.get("current_balance");
                return bb.abs().compareTo(ab.abs());
            });

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
            Map<String, Object> kpiBase = buildKpiBase(schemeCode, runId, nodes, baseDate);

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


    /**
     * <p>解析运行 ID (缺省自动选取)</p>
     *
     * <p><b>为什么要回退</b>: {@code prcp_reverse_run.id} 与 {@code prcp_data_reverse.run_id}
     * 在历史数据里存在<b>关联断裂</b> —— 引擎执行时写入的 run_id 与事后登记的 run 记录 id 不一致。
     * 例: REV_DNN_REG 方案 prcp_reverse_run 最新 SUCCESS 是 run 12,
     * 但 prcp_data_reverse 里的数据实际挂在 run 8 上。若只取「最新 SUCCESS」,
     * 后续按 (scheme_code, run_id) 查月度数据会一条都查不到 → 整个快照报「未找到 M1」→ 页面全空。</p>
     *
     * <p>策略: 先用最新 SUCCESS run; 若该 run 在 prcp_data_reverse 里没有数据,
     * 回退到该 scheme_code 下实际存在数据的最大 run_id。</p>
     *
     * @param revId      反算方案 ID
     * @param schemeCode 方案编码
     * @param runId      显式指定的 run (非空则直接使用, 不做回退)
     * @return 有效的 run_id
     */
    private Long resolveRunId(Long revId, String schemeCode, Long runId) {
        if (runId != null) return runId;
        List<Map<String, Object>> lr = jdbc.queryForList(
                "SELECT id FROM prcp_reverse_run WHERE scheme_id = ? AND status = 'SUCCESS' AND is_deleted = 0"
              + " ORDER BY id DESC LIMIT 1", revId);
        if (!lr.isEmpty()) {
            Long candidate = ((Number) lr.get(0).get("id")).longValue();
            Integer cnt = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM prcp_data_reverse"
                  + " WHERE scheme_code = ? AND run_id = ? AND is_deleted = 0",
                    Integer.class, schemeCode, candidate);
            if (cnt != null && cnt > 0) return candidate;
        }
        // 回退: 该方案下实际有数据的最大 run_id
        List<Map<String, Object>> fr = jdbc.queryForList(
                "SELECT MAX(run_id) AS rid FROM prcp_data_reverse"
              + " WHERE scheme_code = ? AND is_deleted = 0", schemeCode);
        if (!fr.isEmpty() && fr.get(0).get("rid") != null) {
            return ((Number) fr.get(0).get("rid")).longValue();
        }
        throw BizException.notFound("该方案无 SUCCESS 运行记录");
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

        // 2. Run（缺省自动选取, 含 data_reverse 关联断裂回退）
        runId = resolveRunId(revId, schemeCode, runId);

        // 3. 节点元数据 + 末级标记（覆盖所有 COA 账户册方案）
        List<Map<String, Object>> nodes = jdbc.queryForList(
                "SELECT n.id, n.node_code, n.node_name, n.node_level, n.path,"
              + "       n.scheme_id AS coa_scheme_id, c.scheme_code AS coa_scheme_code"
              + "  FROM prcp_coa_node n"
              + "  LEFT JOIN prcp_coa_scheme c ON c.id = n.scheme_id"
              + " WHERE n.is_deleted = 0 ORDER BY n.scheme_id, n.path, n.node_level");
        for (Map<String, Object> n : nodes) n.put("coa_node_id", n.get("id"));
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

        // 4a. 该 Run 完全无月度数据（如引擎只写了少量节点或未产出预测）→
        //     按方案预测期生成 M1..Mn 月份序列, 余额走基准日回退,
        //     否则走势图为空、X 轴无刻度, 用户看到的是一张空图。
        if (monthDate.isEmpty()) {
            int horizon = rev.get("horizonMonths") == null ? 24
                    : ((Number) rev.get("horizonMonths")).intValue();
            if (horizon <= 0) horizon = 24;
            java.time.LocalDate base = rev.get("dataDate") == null ? java.time.LocalDate.now()
                    : java.time.LocalDate.parse(String.valueOf(rev.get("dataDate")).substring(0, 10));
            for (int off = 1; off <= horizon; off++) {
                monthDate.put(off, base.plusMonths(off).toString());
            }
            log.warn("[ReverseDashboardService] Run {} 无月度预测数据, 生成 {} 个月份序列并回退基准日余额",
                    runId, horizon);
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

        // 5d. 数据源回退：部分引擎（如 Java 版独立引擎）产出的 Run 只写了父节点且余额为 0，
        //     没有末级节点预测值 → 走势图会是一条贴底的 0 线。
        //     此时回退到基准日（prcp_data_basic）的末级节点余额，各月取同一基准值。
        //     响应会带 dataSource=BASE_DATE 供前端提示，不静默替换。
        //     与导出（ReverseDashboardExportService）的回退口径保持一致。
        Map<String, BigDecimal> baseLeafBalance = new LinkedHashMap<>();
        boolean balanceFallback = false;
        if (!offsets.isEmpty()) {
            // 统计「有非零余额的末级节点」出现过的月份数
            int monthsWithLeafData = 0;
            for (Map.Entry<Integer, Map<String, BigDecimal>> me : monthByNode.entrySet()) {
                for (Map.Entry<String, BigDecimal> ne : me.getValue().entrySet()) {
                    if (!isLeaf(ne.getKey(), null, parentSet)) continue;
                    if (ne.getValue() != null && ne.getValue().abs().compareTo(BigDecimal.ZERO) > 0) {
                        monthsWithLeafData++;
                        break;
                    }
                }
            }
            if (monthsWithLeafData == 0) {
                baseLeafBalance = loadBaseLeafBalances(rev.get("dataDate"), nodes);
                if (!baseLeafBalance.isEmpty()) {
                    balanceFallback = true;
                    log.warn("[ReverseDashboardService] Run {} 无末级节点预测余额 (有值月份数=0), "
                            + "回退基准日 {} 的末级节点余额 ({} 个)",
                            runId, rev.get("dataDate"), baseLeafBalance.size());
                }
            }
        }

        for (Map<String, Object> n : nodes) {
            String nc = (String) n.get("node_code");
            boolean leaf = leafFlag.containsKey(nc) ? leafFlag.get(nc)
                    : isLeaf(nc, (String) n.get("path"), parentSet);
            if (!leaf) continue;
            String cat = classify(nc, (String) n.get("path"));
            List<BigDecimal> vals = new ArrayList<>();
            BigDecimal total = BigDecimal.ZERO;
            BigDecimal baseVal = baseLeafBalance.get(nc);
            for (Integer off : offsets) {
                BigDecimal v = monthByNode.getOrDefault(off, Collections.emptyMap()).get(nc);
                if (v == null || (v.signum() == 0 && baseVal != null)) {
                    // 该月无末级节点数据 → 用基准日余额回退
                    v = baseVal != null ? baseVal : BigDecimal.ZERO;
                }
                vals.add(v);
                total = total.add(v.abs());
            }
            nodeVals.put(nc, vals);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("code", nc);
            meta.put("name", n.get("node_name"));
            meta.put("category", cat);
            meta.put("coa_scheme_code", n.get("coa_scheme_code"));
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
        // 数据来源标记: RUN_PREDICT = 引擎预测值; BASE_DATE = 该 Run 无末级节点预测值, 已回退基准日
        resp.put("data_source", balanceFallback ? "BASE_DATE" : "RUN_PREDICT");
        resp.put("data_source_note", balanceFallback
                ? "该 Run 未产出末级节点预测余额（仅有父节点且为 0），余额已回退为基准日 "
                  + rev.get("dataDate") + " 的末级节点余额（各月同值），非反算预测结果"
                : null);
        resp.put("has_month_data", !monthRows.isEmpty());
        return R.ok(resp);
    }

    /**
     * <p>加载基准日末级节点余额（预测数据缺失时的回退源）</p>
     *
     * <p>数据源: {@code prcp_data_basic} 基准日的 {@code current_balance}，只取末级节点。
     * 末级判定优先用 {@code prcp_data_basic.is_leaf}，缺失时回退 path 前缀推断。</p>
     *
     * @param baseDate 基准日
     * @param nodes    全部节点元数据（用于 path 推断末级）
     * @return node_code → 余额
     */
    private Map<String, BigDecimal> loadBaseLeafBalances(Object baseDate, List<Map<String, Object>> nodes) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        if (baseDate == null || nodes == null || nodes.isEmpty()) return out;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT node_code, current_balance, is_leaf FROM prcp_data_basic"
              + " WHERE is_deleted = 0 AND data_date = ?", baseDate);
        Set<String> parentSet = computeParentSet(nodes);
        for (Map<String, Object> r : rows) {
            String nc = (String) r.get("node_code");
            if (nc == null) continue;
            Object v = r.get("current_balance");
            if (v == null) continue;
            Object lf = r.get("is_leaf");
            boolean leaf = lf instanceof Boolean ? (Boolean) lf
                    : (lf instanceof Number ? ((Number) lf).intValue() == 1
                    : isLeaf(nc, null, parentSet));
            if (!leaf) continue;
            out.put(nc, toBD(v));
        }
        return out;
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
                                             List<Map<String, Object>> nodes, String schemeBaseDate) {
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
        // 基期末级标记 + 基准日余额
        //  ⚠ 查询两个日期: date_offset=1 的 data_date (如 2025-12-01) 与方案基准日 (如 2025-12-31)
        //    可能不同 —— prcp_data_basic 的基准日余额存在「方案基准日」那一份，
        //    只按 date_offset=1 的日期查会查不到，导致回退不生效、基期恒为 0。
        Map<String, Map<String, Object>> baseBasicMap = new LinkedHashMap<>();
        if (!nodes.isEmpty()) {
            String ph = String.join(",", Collections.nCopies(nodes.size(), "?"));
            Object[] params = new Object[nodes.size() + 1];
            params[0] = baseDate;
            int i = 1;
            for (Map<String, Object> n : nodes) { params[i++] = n.get("node_code"); }
            List<Map<String, Object>> brows = jdbc.queryForList(
                    "SELECT node_code, current_balance, is_leaf FROM prcp_data_basic"
                  + " WHERE is_deleted = 0 AND data_date = ? AND node_code IN (" + ph + ")", params);
            for (Map<String, Object> r : brows) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("base_balance", toBD(r.get("current_balance")));
                m.put("is_leaf", r.get("is_leaf"));
                baseBasicMap.put((String) r.get("node_code"), m);
            }
            // 方案基准日兜底 (date_offset=1 日期查不到时用)
            if (baseBasicMap.isEmpty() && schemeBaseDate != null && !schemeBaseDate.equals(baseDate)) {
                Object[] p2 = new Object[nodes.size() + 1];
                p2[0] = schemeBaseDate;
                int j = 1;
                for (Map<String, Object> n : nodes) { p2[j++] = n.get("node_code"); }
                List<Map<String, Object>> brows2 = jdbc.queryForList(
                        "SELECT node_code, current_balance, is_leaf FROM prcp_data_basic"
                      + " WHERE is_deleted = 0 AND data_date = ? AND node_code IN (" + ph + ")", p2);
                for (Map<String, Object> r : brows2) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("base_balance", toBD(r.get("current_balance")));
                    m.put("is_leaf", r.get("is_leaf"));
                    baseBasicMap.put((String) r.get("node_code"), m);
                }
            }
        }
        // 基期余额回退：与当前期同一口径 —— 该 Run 若未产出末级节点预测余额，
        // 用基准日 (prcp_data_basic) 的末级节点余额补齐, 否则基期三张卡恒为 0。
        if (!baseBasicMap.isEmpty()) {
            Set<String> bParents = computeParentSet(nodes);
            for (Map<String, Object> n : nodes) {
                String nc = (String) n.get("node_code");
                if (!isLeafFlag(nc, (String) n.get("path"), bParents, baseBasicMap)) continue;
                Map<String, Object> b = baseBasicMap.get(nc);
                if (b == null) continue;
                BigDecimal bb = (BigDecimal) b.getOrDefault("base_balance", BigDecimal.ZERO);
                if (bb == null || bb.signum() == 0) continue;
                Map<String, Object> m = revMap.get(nc);
                if (m == null) {
                    m = new LinkedHashMap<>();
                    // 回退节点: 引擎未产出利率 → 置 null (表示无数据), 不填 0 冒充真实利率
                    m.put("weighted_rate", null);
                    revMap.put(nc, m);
                }
                BigDecimal cur = (BigDecimal) m.getOrDefault("current_balance", BigDecimal.ZERO);
                if (cur == null || cur.signum() == 0) {
                    m.put("current_balance", bb);
                    // 余额由基准日回退 → 对应利率同样无数据
                    BigDecimal mRate = (BigDecimal) m.getOrDefault("weighted_rate", BigDecimal.ZERO);
                    if (mRate == null || mRate.signum() == 0) m.put("weighted_rate", null);
                }
            }
        }
        base.put("asset_total", sumByCat(nodes, revMap, "ASSET", baseBasicMap));
        base.put("liability_total", sumByCat(nodes, revMap, "LIABILITY", baseBasicMap));
        base.put("equity_total", sumByCat(nodes, revMap, "EQUITY", baseBasicMap));

        // 基期贷款利率 (同 7a 口径: 引擎未产出利率时返回 null, 不冒充 0.00%)
        BigDecimal loanNum = BigDecimal.ZERO, loanDen = BigDecimal.ZERO;
        int loanRateNodes = 0, loanBalNodes = 0;
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
            loanBalNodes++;
            if (rate == null || rate.signum() == 0) continue;
            loanRateNodes++;
            loanNum = loanNum.add(bal.multiply(rate));
            loanDen = loanDen.add(bal);
        }
        base.put("loan_weighted_rate", (loanDen.compareTo(BigDecimal.ZERO) > 0
                && loanBalNodes > 0 && loanRateNodes == loanBalNodes)
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
    /**
     * <p>末级节点判定 (path 前缀推断) — 供 ExportService 复用</p>
     *
     * @param nodeCode  节点编码
     * @param path      节点 path
     * @param parentSet 有子节点的节点集合
     * @return 是否末级
     */
    public static boolean isLeafByPath(String nodeCode, String path, Set<String> parentSet) {
        if (path == null || path.isEmpty()) return true;
        return !parentSet.contains(nodeCode);
    }

    private static boolean isLeaf(String nodeCode, String path, Set<String> parentSet) {
        return isLeafByPath(nodeCode, path, parentSet);
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
        return sumByCat(nodes, revMap, cat, null);
    }

    /**
     * <p>按大类汇总余额（仅末级节点）</p>
     *
     * @param nodes    全部节点
     * @param revMap   节点 → 余额数据
     * @param cat      大类 (ASSET/LIABILITY/EQUITY/OFF_BALANCE/OTHER)
     * @param basicMap 基础数据（末级标记）；传 null 时按 baseDate 查一次库
     * @return 余额合计
     */
    private BigDecimal sumByCat(List<Map<String, Object>> nodes, Map<String, Map<String, Object>> revMap,
                                String cat, Map<String, Map<String, Object>> basicMap) {
        BigDecimal sum = BigDecimal.ZERO;
        Set<String> parents = computeParentSet(nodes);
        Map<String, Map<String, Object>> bm = basicMap != null ? basicMap : Collections.emptyMap();
        for (Map<String, Object> n : nodes) {
            String nc = (String) n.get("node_code");
            if (!cat.equals(classify(nc, (String) n.get("path")))) continue;
            // 末级判定: 优先 prcp_data_basic.is_leaf (库内权威标记), 缺失时回退 path 前缀推断。
            //  ⚠ 不能只用 path 推断 —— 权益类只有一个 L1 节点 (S030000000000 所有者权益),
            //    它在 prcp_data_basic 标了 is_leaf=1, 但 path 推断会因存在子树前缀而判为父节点,
            //    导致 equity_total 恒为 0。
            if (!isLeafFlag(nc, (String) n.get("path"), parents, bm)) continue;
            Map<String, Object> r = revMap.get(nc);
            if (r == null) continue;
            BigDecimal bal = (BigDecimal) r.getOrDefault("current_balance", BigDecimal.ZERO);
            sum = sum.add(bal);
        }
        return sum.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * <p>节点大类归类 — 供 ExportService 复用</p>
     *
     * @param nodeCode 节点编码
     * @param path     节点 path
     * @return ASSET / LIABILITY / EQUITY / OFF_BALANCE / OTHER
     */
    public static String classify(String nodeCode, String path) {
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