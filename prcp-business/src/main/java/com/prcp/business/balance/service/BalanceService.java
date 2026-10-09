package com.prcp.business.balance.service;

import com.prcp.common.exception.BizException;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * <p>资产负债表 Service — 对位 Python app/routers/balance.py</p>
 *
 * <p>核心职责:
 * <ol>
 *   <li>列表查询 (按节点/日期/方案过滤)</li>
 *   <li>按 (coa_node_id, data_date) 唯一键 upsert</li>
 *   <li>软删除 (is_deleted=1)</li>
 *   <li>24 月缺口汇总 (sum/min/max)</li>
 *   <li>方案 × 月份矩阵 (按 L1 大类聚合 + 加权)</li>
 *   <li>已录入日期统计 (行数/总额/总息)</li>
 *   <li>单日方案查询 + L1 大类汇总</li>
 * </ol>
 * </p>
 *
 * <p>关键约定:
 * <ul>
 *   <li>数据来源: prcp_data_balance (24 月缺口 + 7 度量)</li>
 *   <li>唯一约束: (coa_node_id, data_date) + is_deleted=0</li>
 *   <li>软删除: is_deleted=1, 不物理删除</li>
 *   <li>gap 字段: m1_gap ... m24_gap (24 个月, BigDecimal)</li>
 *   <li>加权口径: interest_rate / capital_ratio / risk_weight 按 avg_balance 加权</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BalanceService {

    private final JdbcTemplate jdbc;

    private static final List<String> GAP_COLS = buildGapCols();
    private static final List<String> MEASURE_COLS = List.of(
            "begin_balance", "avg_balance", "interest_rate", "interest_amount",
            "capital_ratio", "risk_weight");

    private static List<String> buildGapCols() {
        List<String> r = new ArrayList<>();
        for (int i = 1; i <= 24; i++) r.add("m" + i + "_gap");
        return r;
    }

    private static String gapColsSql() {
        StringBuilder sb = new StringBuilder();
        for (String c : GAP_COLS) { sb.append(", b.").append(c); }
        return sb.toString();
    }

    /**
     * <p>列表查询 (按节点/日期/方案过滤)</p>
     *
     * <p>任意过滤参数为空都视为不限制, 结果按 n.path, data_date.当前, coa_node_id 排序</p>
     *
     * @param coaNodeId 节点 ID (可选)
     * @param dataDate  数据日期 yyyy-MM-dd (可选)
     * @param startDate 起始日期 yyyy-MM-dd (可选)
     * @param endDate   结束日期 yyyy-MM-dd (可选)
     * @param schemeId  方案 ID (可选)
     * @return R.ok(Map.of("items", list, "total", list.size())), 每条含 gaps 列表
     */
    public R<Map<String, Object>> list(Long coaNodeId, String dataDate, String startDate, String endDate, Long schemeId) {
        StringBuilder sql = new StringBuilder(
                "SELECT b.id, b.coa_node_id AS coaNodeId, n.node_code AS nodeCode, n.node_name AS nodeName,"
              + " n.node_level AS nodeLevel, n.path, n.node_type AS nodeType, b.data_date AS dataDate,"
              + " b.current_amount AS currentAmount, b.begin_balance AS beginBalance, b.avg_balance AS avgBalance,"
              + " b.interest_rate AS interestRate, b.interest_amount AS interestAmount,"
              + " b.capital_ratio AS capitalRatio, b.risk_weight AS riskWeight,"
              + " b.calc_note AS calcNote, b.created_at AS createdAt"
              + gapColsSql()
              + " FROM prcp_data_balance b LEFT JOIN prcp_coa_node n ON n.id = b.coa_node_id"
              + " WHERE b.is_deleted = 0");
        List<Object> params = new ArrayList<>();
        if (coaNodeId != null) { sql.append(" AND b.coa_node_id = ?"); params.add(coaNodeId); }
        if (dataDate != null && !dataDate.isEmpty()) { sql.append(" AND b.data_date = ?"); params.add(dataDate); }
        if (startDate != null && !startDate.isEmpty()) { sql.append(" AND b.data_date >= ?"); params.add(startDate); }
        if (endDate != null && !endDate.isEmpty()) { sql.append(" AND b.data_date <= ?"); params.add(endDate); }
        if (schemeId != null) { sql.append(" AND n.scheme_id = ?"); params.add(schemeId); }
        sql.append(" ORDER BY n.path, b.data_date DESC, b.coa_node_id LIMIT 2000");

        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), params.toArray());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = toCamel(r);
            m.put("gaps", buildGaps(r));
            items.add(m);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        resp.put("total", items.size());
        return R.ok(resp);
    }

    private static List<Double> buildGaps(Map<String, Object> r) {
        List<Double> gaps = new ArrayList<>();
        for (String c : GAP_COLS) {
            Object v = r.get(c);
            gaps.add(v == null ? 0.0 : ((Number) v).doubleValue());
        }
        return gaps;
    }

    private static Map<String, Object> toCamel(Map<String, Object> snake) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : snake.entrySet()) {
            Object v = e.getValue();
            String k = e.getKey();
            if (v instanceof java.sql.Date) v = v.toString();
            else if (v instanceof java.sql.Timestamp) v = v.toString();
            else if (v instanceof BigDecimal) v = ((BigDecimal) v).setScale(4, RoundingMode.HALF_UP);
            m.put(k, v);
        }
        return m;
    }

    /**
     * <p>按 (coa_node_id, data_date) 唯一键 upsert 资产负债表</p>
     *
     * <p>存在则更新 (action=updated), 不存在则插入 (action=created); gaps 不足 24 位补 0</p>
     *
     * @param body 含 coa_node_id, data_date, 7 度量 + gaps 列表 + calc_note 的 Map
     * @return R.ok(Map.of("id", recordId, "action", "updated"|"created"))
     */
    @Transactional
    public R<Map<String, Object>> upsert(Map<String, Object> body) {
        Object nodeIdObj = body.get("coa_node_id");
        Object dateObj = body.get("data_date");
        if (nodeIdObj == null) throw BizException.badRequest("coa_node_id 必填");
        if (dateObj == null) throw BizException.badRequest("data_date 必填");
        Long coaNodeId = ((Number) nodeIdObj).longValue();
        String dataDate = dateObj.toString();

        BigDecimal currentAmount = toBD(body.get("current_amount"));
        BigDecimal beginBalance = toBD(body.get("begin_balance"));
        BigDecimal avgBalance = toBD(body.get("avg_balance"));
        BigDecimal interestRate = toBD(body.get("interest_rate"));
        BigDecimal interestAmount = toBD(body.get("interest_amount"));
        BigDecimal capitalRatio = toBD(body.get("capital_ratio"));
        BigDecimal riskWeight = toBD(body.get("risk_weight"));
        String calcNote = (String) body.get("calc_note");

        // gaps
        @SuppressWarnings("unchecked")
        List<Number> gaps = (List<Number>) body.get("gaps");
        if (gaps == null) gaps = new ArrayList<>();
        while (gaps.size() < 24) gaps.add(0);

        Long uid = 1L;
        Map<String, Object> exist = jdbc.queryForMap(
                "SELECT id FROM prcp_data_balance WHERE coa_node_id = ? AND data_date = ? AND is_deleted = 0",
                coaNodeId, dataDate);
        if (exist != null && !exist.isEmpty()) {
            StringBuilder sql = new StringBuilder(
                    "UPDATE prcp_data_balance SET current_amount=?, begin_balance=?, avg_balance=?,"
                  + " interest_rate=?, interest_amount=?, capital_ratio=?, risk_weight=?,");
            for (int i = 0; i < 24; i++) sql.append(" m").append(i + 1).append("_gap=?,");
            sql.append(" calc_note=?, updated_by=? WHERE id=?");
            List<Object> params = new ArrayList<>();
            params.add(currentAmount == null ? BigDecimal.ZERO : currentAmount);
            params.add(beginBalance == null ? BigDecimal.ZERO : beginBalance);
            params.add(avgBalance == null ? BigDecimal.ZERO : avgBalance);
            params.add(interestRate == null ? BigDecimal.ZERO : interestRate);
            params.add(interestAmount == null ? BigDecimal.ZERO : interestAmount);
            params.add(capitalRatio == null ? BigDecimal.ZERO : capitalRatio);
            params.add(riskWeight == null ? BigDecimal.ZERO : riskWeight);
            for (Number g : gaps) params.add(g == null ? BigDecimal.ZERO : BigDecimal.valueOf(g.doubleValue()));
            params.add(calcNote); params.add(uid); params.add(exist.get("id"));
            jdbc.update(sql.toString(), params.toArray());
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("id", exist.get("id")); resp.put("action", "updated");
            return R.ok(resp);
        } else {
            StringBuilder sql = new StringBuilder(
                    "INSERT INTO prcp_data_balance (coa_node_id, data_date, current_amount, begin_balance,"
                  + " avg_balance, interest_rate, interest_amount, capital_ratio, risk_weight,");
            for (int i = 0; i < 24; i++) sql.append(" m").append(i + 1).append("_gap,");
            sql.append(" calc_note, created_by, updated_by) VALUES (");
            for (int i = 0; i < 13; i++) sql.append("?,");
            sql.append("?,?,?)");
            List<Object> params = new ArrayList<>();
            params.add(coaNodeId); params.add(dataDate);
            params.add(currentAmount == null ? BigDecimal.ZERO : currentAmount);
            params.add(beginBalance == null ? BigDecimal.ZERO : beginBalance);
            params.add(avgBalance == null ? BigDecimal.ZERO : avgBalance);
            params.add(interestRate == null ? BigDecimal.ZERO : interestRate);
            params.add(interestAmount == null ? BigDecimal.ZERO : interestAmount);
            params.add(capitalRatio == null ? BigDecimal.ZERO : capitalRatio);
            params.add(riskWeight == null ? BigDecimal.ZERO : riskWeight);
            for (Number g : gaps) params.add(g == null ? BigDecimal.ZERO : BigDecimal.valueOf(g.doubleValue()));
            params.add(calcNote); params.add(uid); params.add(uid);
            jdbc.update(sql.toString(), params.toArray());
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("id", jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class));
            resp.put("action", "created");
            return R.ok(resp);
        }
    }

    /**
     * <p>软删除资产负债表 (is_deleted=1)</p>
     *
     * @param bid 资产负债表主键 ID (必填)
     * @return R.ok({ok: true}); 不存在时抛 notFound
     */
    @Transactional
    public R<Map<String, Object>> delete(Long bid) {
        if (bid == null) throw BizException.badRequest("id 必填");
        int n = jdbc.update("UPDATE prcp_data_balance SET is_deleted = 1, updated_by = ?, updated_at = NOW() WHERE id = ? AND is_deleted = 0", 1L, bid);
        if (n == 0) throw BizException.notFound("记录不存在");
        return R.ok(Collections.singletonMap("ok", true));
    }

    /**
     * <p>某数据日期的 24 月缺口汇总 (sum/min/max)</p>
     *
     * @param dataDate 数据日期 yyyy-MM-dd (必填)
     * @return R.ok(Map.of("data_date", dataDate, "items", list, "total", size)), 每条含 24 月 gaps 列表 + sum_24m/min_gap/max_gap
     */
    public R<Map<String, Object>> gapSummary(String dataDate) {
        if (dataDate == null || dataDate.isEmpty()) throw BizException.badRequest("data_date 必填");
        StringBuilder sql = new StringBuilder(
                "SELECT b.coa_node_id AS coaNodeId, n.node_code AS nodeCode, n.node_name AS nodeName,"
              + " b.current_amount AS currentAmount"
              + gapColsSql()
              + " FROM prcp_data_balance b LEFT JOIN prcp_coa_node n ON n.id = b.coa_node_id"
              + " WHERE b.data_date = ? AND b.is_deleted = 0 ORDER BY b.coa_node_id");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), dataDate);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            List<Double> gaps = buildGaps(r);
            double sum = 0, mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
            for (double g : gaps) { sum += g; mn = Math.min(mn, g); mx = Math.max(mx, g); }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("coa_node_id", r.get("coaNodeId"));
            m.put("node_code", r.get("nodeCode"));
            m.put("node_name", r.get("nodeName"));
            m.put("current_amount", r.get("currentAmount"));
            m.put("gaps", gaps);
            m.put("sum_24m", sum);
            m.put("min_gap", gaps.isEmpty() ? 0.0 : mn);
            m.put("max_gap", gaps.isEmpty() ? 0.0 : mx);
            items.add(m);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data_date", dataDate); resp.put("items", items); resp.put("total", items.size());
        return R.ok(resp);
    }

    /**
     * <p>按方案 × 月份的矩阵查询 (含 L1 大类加权聚合)</p>
     *
     * <p>输出 dates (月份列表), nodes (节点含 L1 大类), matrix[nodeId][ym] (7 度量), categories[L1][ym] (按 avg_balance 加权)</p>
     *
     * @param schemeId  方案 ID (必填)
     * @param startDate 起始日期 yyyy-MM-dd (必填)
     * @param endDate   结束日期 yyyy-MM-dd (必填)
     * @return R.ok(Map.of("dates"/"nodes"/"matrix"/"categories", ...))
     */
    public R<Map<String, Object>> bySchemeMatrix(Long schemeId, String startDate, String endDate) {
        if (schemeId == null) throw BizException.badRequest("scheme_id 必填");
        if (startDate == null || endDate == null) throw BizException.badRequest("start_date 和 end_date 必填");

        // 1) 加载节点
        List<Map<String, Object>> nodeRows = jdbc.queryForList(
                "SELECT id, node_code AS nodeCode, node_name AS nodeName, parent_id AS parentId,"
              + " node_level AS nodeLevel, node_type AS nodeType, path, sort_order AS sortOrder, description"
              + " FROM prcp_coa_node WHERE scheme_id = ? AND is_deleted = 0 ORDER BY sort_order, path", schemeId);
        List<Map<String, Object>> nodes = new ArrayList<>();
        Map<Long, Map<String, Object>> nodeById = new LinkedHashMap<>();
        for (Map<String, Object> n : nodeRows) {
            String path = (String) n.getOrDefault("path", "");
            String cat = "";
            if (path.contains("/")) cat = path.split("/")[1].replace("L1_", "");
            n.put("coa_node_id", n.get("id"));
            n.put("category", cat);
            n.put("description", n.getOrDefault("description", ""));
            nodes.add(n);
            nodeById.put(((Number) n.get("id")).longValue(), n);
        }

        // 2) 月份列表
        List<String> dates = new ArrayList<>();
        LocalDate cur = LocalDate.parse(startDate).withDayOfMonth(1);
        LocalDate end = LocalDate.parse(endDate).withDayOfMonth(1);
        while (!cur.isAfter(end)) {
            dates.add(cur.toString().substring(0, 7));
            cur = cur.plusMonths(1);
        }
        if (dates.isEmpty()) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("dates", new ArrayList<>()); resp.put("nodes", new ArrayList<>());
            resp.put("matrix", new LinkedHashMap<>()); resp.put("categories", new LinkedHashMap<>());
            return R.ok(resp);
        }

        // 3) 一次性查 balance
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT b.coa_node_id AS coaNodeId, DATE_FORMAT(b.data_date, '%Y-%m') AS ym,"
              + " b.begin_balance AS beginBalance, b.avg_balance AS avgBalance,"
              + " b.current_amount AS currentAmount, b.interest_rate AS interestRate,"
              + " b.interest_amount AS interestAmount, b.capital_ratio AS capitalRatio, b.risk_weight AS riskWeight"
              + " FROM prcp_data_balance b JOIN prcp_coa_node n ON n.id = b.coa_node_id"
              + " WHERE n.scheme_id = ? AND b.is_deleted = 0 AND b.data_date BETWEEN ? AND ?"
              + " ORDER BY b.coa_node_id, b.data_date", schemeId, startDate, endDate);

        // 4) 构建 matrix[coa_node_id][ym] = 7 度量
        Map<Long, Map<String, Map<String, Object>>> matrix = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            long cid = ((Number) r.get("coaNodeId")).longValue();
            String ym = (String) r.get("ym");
            matrix.computeIfAbsent(cid, k -> new LinkedHashMap<>())
                    .computeIfAbsent(ym, k -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("begin_balance", 0.0); m.put("avg_balance", 0.0); m.put("current_amount", 0.0);
                        m.put("interest_rate", 0.0); m.put("interest_amount", 0.0);
                        m.put("capital_ratio", 0.0); m.put("risk_weight", 0.0);
                        return m;
                    });
            Map<String, Object> bucket = matrix.get(cid).get(ym);
            for (String k : new String[]{"begin_balance", "avg_balance", "current_amount", "interest_rate", "interest_amount", "capital_ratio", "risk_weight"}) {
                String camel = camelize(k);
                Object v = r.get(camel);
                if (v != null) bucket.put(k, ((Number) v).doubleValue());
            }
        }

        // 5) 按 L1 大类聚合
        Map<String, Map<String, Map<String, Object>>> categories = new LinkedHashMap<>();
        for (Map.Entry<Long, Map<String, Map<String, Object>>> e : matrix.entrySet()) {
            Map<String, Object> n = nodeById.get(e.getKey());
            if (n == null || n.get("path") == null) continue;
            String path = (String) n.get("path");
            String cat = path.contains("/") ? path.split("/")[1].replace("L1_", "") : "其他";
            Map<String, Map<String, Object>> catMap = categories.computeIfAbsent(cat, k -> new LinkedHashMap<>());
            for (Map.Entry<String, Map<String, Object>> ymE : e.getValue().entrySet()) {
                Map<String, Object> cb = catMap.computeIfAbsent(ymE.getKey(), k -> {
                    Map<String, Object> mm = new LinkedHashMap<>();
                    mm.put("begin_balance", 0.0); mm.put("avg_balance", 0.0); mm.put("current_amount", 0.0);
                    mm.put("interest_rate", 0.0); mm.put("interest_amount", 0.0);
                    mm.put("capital_ratio", 0.0); mm.put("risk_weight", 0.0);
                    mm.put("account_count", 0);
                    return mm;
                });
                for (String k : new String[]{"begin_balance", "avg_balance", "current_amount", "interest_amount"}) {
                    addToBucket(cb, k, ((Number) ymE.getValue().get(k)).doubleValue());
                }
                cb.merge("account_count", 1, (a, b2) -> ((Number) a).intValue() + ((Number) b2).intValue());
            }
        }
        // 计算加权值
        for (Map<String, Map<String, Object>> catMap : categories.values()) {
            for (Map.Entry<String, Map<String, Object>> ymE : catMap.entrySet()) {
                Map<String, Object> m = ymE.getValue();
                double avg = ((Number) m.get("avg_balance")).doubleValue();
                if (avg > 0) {
                    double sumWr = 0, sumCap = 0, sumRw = 0;
                    for (Map.Entry<Long, Map<String, Map<String, Object>>> ce : matrix.entrySet()) {
                        Map<String, Object> mm = ce.getValue().get(ymE.getKey());
                        if (mm == null) continue;
                        sumWr += ((Number) mm.get("interest_rate")).doubleValue() * ((Number) mm.get("avg_balance")).doubleValue();
                        sumCap += ((Number) mm.get("capital_ratio")).doubleValue() * ((Number) mm.get("avg_balance")).doubleValue();
                        sumRw += ((Number) mm.get("risk_weight")).doubleValue() * ((Number) mm.get("avg_balance")).doubleValue();
                    }
                    m.put("interest_rate", round(sumWr / avg, 4));
                    m.put("capital_ratio", round(sumCap / avg, 4));
                    m.put("risk_weight", round(sumRw / avg, 4));
                }
                for (String k : new String[]{"begin_balance", "avg_balance", "current_amount", "interest_amount"}) {
                    Object v = m.get(k);
                    if (v != null) m.put(k, round(((Number) v).doubleValue(), 4));
                }
            }
        }

        // matrix 输出（key 转 String 以 JSON 兼容）
        Map<String, Map<String, Map<String, Object>>> matrixOut = new LinkedHashMap<>();
        for (Map.Entry<Long, Map<String, Map<String, Object>>> e : matrix.entrySet()) {
            matrixOut.put(String.valueOf(e.getKey()), e.getValue());
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("scheme_id", schemeId);
        resp.put("start_date", startDate); resp.put("end_date", endDate);
        resp.put("dates", dates); resp.put("nodes", nodes);
        resp.put("matrix", matrixOut); resp.put("categories", categories);
        return R.ok(resp);
    }

    private static double round(double v, int p) {
        return BigDecimal.valueOf(v).setScale(p, RoundingMode.HALF_UP).doubleValue();
    }

    private static String camelize(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') upper = true;
            else { if (upper) { sb.append(Character.toUpperCase(c)); upper = false; } else sb.append(c); }
        }
        return sb.toString();
    }

    /**
     * <p>按方案 × 单日查询 (按 n.path 排序, 含 gaps 列表 + sum_24m)</p>
     *
     * @param schemeId 方案 ID (必填)
     * @param dataDate 数据日期 yyyy-MM-dd (必填)
     * @return R.ok(Map.of("scheme_id"/"data_date"/"items"/"total", ...))
     */
    public R<Map<String, Object>> byScheme(Long schemeId, String dataDate) {
        if (schemeId == null || dataDate == null) throw BizException.badRequest("scheme_id 和 data_date 必填");
        StringBuilder sql = new StringBuilder(
                "SELECT b.id, b.coa_node_id AS coaNodeId, n.node_code AS nodeCode, n.node_name AS nodeName,"
              + " n.node_level AS nodeLevel, n.node_type AS nodeType, n.path, b.current_amount AS currentAmount,"
              + " b.begin_balance AS beginBalance, b.avg_balance AS avgBalance, b.interest_rate AS interestRate,"
              + " b.interest_amount AS interestAmount, b.capital_ratio AS capitalRatio, b.risk_weight AS riskWeight,"
              + " b.calc_note AS calcNote"
              + gapColsSql()
              + " FROM prcp_data_balance b JOIN prcp_coa_node n ON n.id = b.coa_node_id"
              + " WHERE b.data_date = ? AND b.is_deleted = 0 AND n.scheme_id = ? ORDER BY n.path");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), dataDate, schemeId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = toCamel(r);
            List<Double> gaps = buildGaps(r);
            double sum = 0; for (double g : gaps) sum += g;
            m.put("gaps", gaps); m.put("sum_24m", sum);
            items.add(m);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("scheme_id", schemeId); resp.put("data_date", dataDate);
        resp.put("items", items); resp.put("total", items.size());
        return R.ok(resp);
    }

    /**
     * <p>已录入日期统计 (按方案过滤), 含记录数/总余额/总利息</p>
     *
     * @param schemeId 方案 ID (可选, null 则全局)
     * @return R.ok(Map.of("items", list, "total", size)), 按 data_date 倒序
     */
    public R<Map<String, Object>> dates(Long schemeId) {
        StringBuilder sql = new StringBuilder(
                "SELECT b.data_date AS dataDate, COUNT(*) AS cnt,"
              + " SUM(b.current_amount) AS totalAmt, SUM(b.interest_amount) AS totalInt"
              + " FROM prcp_data_balance b JOIN prcp_coa_node n ON n.id = b.coa_node_id"
              + " WHERE b.is_deleted = 0");
        List<Object> params = new ArrayList<>();
        if (schemeId != null) { sql.append(" AND n.scheme_id = ?"); params.add(schemeId); }
        sql.append(" GROUP BY b.data_date ORDER BY b.data_date DESC");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), params.toArray());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("data_date", r.get("dataDate") == null ? null : r.get("dataDate").toString());
            m.put("record_count", ((Number) r.get("cnt")).intValue());
            m.put("total_amount", r.get("totalAmt") == null ? 0.0 : ((Number) r.get("totalAmt")).doubleValue());
            m.put("total_interest", r.get("totalInt") == null ? 0.0 : ((Number) r.get("totalInt")).doubleValue());
            items.add(m);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items); resp.put("total", items.size());
        return R.ok(resp);
    }

    /**
     * <p>单日 × L1 大类的分类汇总 (按 node_level=3 聚合, 加权计算 rate/cap/rw)</p>
     *
     * @param dataDate 数据日期 yyyy-MM-dd (必填)
     * @param schemeId 方案 ID (可选, null 则全局)
     * @return R.ok(Map.of("data_date"/"items"/"total", ...))
     */
    public R<Map<String, Object>> categorySummary(String dataDate, Long schemeId) {
        if (dataDate == null) throw BizException.badRequest("data_date 必填");
        StringBuilder sql = new StringBuilder(
                "SELECT n.id, n.node_code AS nodeCode, n.node_name AS nodeName, n.path,"
              + " b.current_amount AS currentAmount, b.begin_balance AS beginBalance,"
              + " b.avg_balance AS avgBalance, b.interest_rate AS interestRate,"
              + " b.interest_amount AS interestAmount, b.capital_ratio AS capitalRatio, b.risk_weight AS riskWeight"
              + gapColsSql()
              + " FROM prcp_data_balance b JOIN prcp_coa_node n ON n.id = b.coa_node_id"
              + " WHERE b.is_deleted = 0 AND b.data_date = ? AND n.node_level = 3");
        List<Object> params = new ArrayList<>();
        params.add(dataDate);
        if (schemeId != null) { sql.append(" AND n.scheme_id = ?"); params.add(schemeId); }
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), params.toArray());
        Map<String, Map<String, Object>> buckets = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String path = (String) r.getOrDefault("path", "");
            String cat = path.contains("/") ? path.split("/")[1].replace("L1_", "") : "其他";
            Map<String, Object> b = buckets.computeIfAbsent(cat, k -> {
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("category", k); mm.put("account_count", 0);
                mm.put("total_amount", 0.0); mm.put("total_begin", 0.0); mm.put("total_avg", 0.0);
                mm.put("total_interest", 0.0); mm.put("weighted_rate_sum", 0.0);
                mm.put("weighted_cap_sum", 0.0); mm.put("weighted_rw_sum", 0.0); mm.put("sum_24m", 0.0);
                return mm;
            });
            b.merge("account_count", 1, (a, b2) -> ((Number) a).intValue() + ((Number) b2).intValue());
            double amt = r.get("currentAmount") == null ? 0 : ((Number) r.get("currentAmount")).doubleValue();
            double avg = r.get("avgBalance") == null ? 0 : ((Number) r.get("avgBalance")).doubleValue();
            double rate = r.get("interestRate") == null ? 0 : ((Number) r.get("interestRate")).doubleValue();
            double cap = r.get("capitalRatio") == null ? 0 : ((Number) r.get("capitalRatio")).doubleValue();
            double rw = r.get("riskWeight") == null ? 0 : ((Number) r.get("riskWeight")).doubleValue();
            double curInt = r.get("interestAmount") == null ? 0 : ((Number) r.get("interestAmount")).doubleValue();
            double curBegin = r.get("beginBalance") == null ? 0 : ((Number) r.get("beginBalance")).doubleValue();
            double sum24 = 0;
            for (String c : GAP_COLS) sum24 += r.get(c) == null ? 0 : ((Number) r.get(c)).doubleValue();
            addToBucket(b, "total_amount", amt);
            addToBucket(b, "total_begin", curBegin);
            addToBucket(b, "total_avg", avg);
            addToBucket(b, "total_interest", curInt);
            addToBucket(b, "weighted_rate_sum", rate * avg);
            addToBucket(b, "weighted_cap_sum", cap * avg);
            addToBucket(b, "weighted_rw_sum", rw * avg);
            addToBucket(b, "sum_24m", sum24);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> b : buckets.values()) {
            double avg = ((Number) b.get("total_avg")).doubleValue();
            if (avg == 0) avg = 1;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("category", b.get("category"));
            m.put("account_count", b.get("account_count"));
            m.put("total_amount", round((double) b.get("total_amount"), 2));
            m.put("total_begin", round((double) b.get("total_begin"), 2));
            m.put("total_avg", round((double) b.get("total_avg"), 2));
            m.put("total_interest", round((double) b.get("total_interest"), 2));
            m.put("weighted_rate", round((double) b.get("weighted_rate_sum") / avg, 4));
            m.put("weighted_capital", round((double) b.get("weighted_cap_sum") / avg, 4));
            m.put("weighted_risk_weight", round((double) b.get("weighted_rw_sum") / avg, 4));
            m.put("sum_24m", round((double) b.get("sum_24m"), 2));
            items.add(m);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data_date", dataDate); resp.put("items", items); resp.put("total", items.size());
        return R.ok(resp);
    }

    // ============== helper ==============
    private static void addToBucket(Map<String, Object> bucket, String key, double add) {
        Object cur = bucket.get(key);
        double newVal = (cur == null ? 0.0 : ((Number) cur).doubleValue()) + add;
        bucket.put(key, newVal);
    }

    private static BigDecimal toBD(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal) return (BigDecimal) o;
        if (o instanceof Number) return new BigDecimal(o.toString());
        try { return new BigDecimal(o.toString()); } catch (Exception e) { return null; }
    }
}