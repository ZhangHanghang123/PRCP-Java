package com.prcp.business.reverse.result;

import com.prcp.common.exception.BizException;
import com.prcp.common.result.R;
import com.prcp.business.data.BasicDataBuckets;
import com.alibaba.excel.EasyExcel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * <p>反算结果查询 Service — 对位 Python /data-reverse 路由</p>
 *
 * <p>核心职责:
 * <ol>
 *   <li>有反算数据的方案列表 (按 scheme_code 聚合)</li>
 *   <li>方案下的 Run 列表 (仅 status='SUCCESS')</li>
 *   <li>Run 下的所有 data_date + date_offset</li>
 *   <li>节点 × 64+桶 + 度量 矩阵 (账户册矩阵)</li>
 *   <li>大类汇总 (按 category 聚合)</li>
 *   <li>Excel 导出</li>
 * </ol>
 * </p>
 *
 * <p>关键约定:
 * <ul>
 *   <li>数据源: prcp_data_reverse (64 桶 + 度量) + prcp_reverse_scheme + prcp_reverse_run</li>
 *   <li>桶维度: orig_m1..orig_m60 + orig_y10/y15/y20/y30 = 64 orig + 64 rem = 128 桶</li>
 *   <li>矩阵 LIMIT: 1000 行 (单次查询上限)</li>
 *   <li>导出 LIMIT: 5000 行</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReverseResultService {

    private final JdbcTemplate jdbc;

    /**
     * <p>有反算数据的方案列表 (按 scheme_code 聚合)</p>
     *
     * @return R.ok(Map.of("items", list)); 含 run_count/total_rows/first_date/last_date/date_count/last_run_at
     */
    public R<Map<String, Object>> listSchemes() {
        String sql =
                "SELECT scheme_code AS scheme_code," +
                "       COUNT(DISTINCT run_id) AS run_count," +
                "       COUNT(*) AS total_rows," +
                "       MIN(data_date) AS first_date," +
                "       MAX(data_date) AS last_date," +
                "       COUNT(DISTINCT data_date) AS date_count," +
                "       MAX(DATE(updated_at)) AS last_run_at" +
                "  FROM prcp_data_reverse WHERE is_deleted=0" +
                " GROUP BY scheme_code ORDER BY last_date DESC, scheme_code";
        List<Map<String, Object>> items = jdbc.queryForList(sql);
        return R.ok(Collections.singletonMap("items", items));
    }

    /**
     * <p>方案下的运行列表 (仅 status='SUCCESS')</p>
     *
     * @param schemeCode 反算方案编码 (必填)
     * @return R.ok(Map.of("items", list)) LIMIT 200
     */
    public R<Map<String, Object>> listRuns(String schemeCode) {
        if (schemeCode == null || schemeCode.isEmpty()) {
            throw BizException.badRequest("scheme_code 必填");
        }
        String sql =
                "SELECT r.id, r.run_code AS run_code, s.scheme_code AS scheme_code," +
                "       s.scheme_name AS scheme_name, r.status, r.progress," +
                "       r.optimal_value AS optimal_value," +
                "       r.start_at AS start_at, r.end_at AS end_at," +
                "       r.duration_sec AS duration_sec, r.created_at AS created_at," +
                "       (SELECT COUNT(DISTINCT data_date) FROM prcp_data_reverse" +
                "         WHERE scheme_code=s.scheme_code AND run_id=r.id AND is_deleted=0) AS date_count," +
                "       (SELECT COUNT(*) FROM prcp_data_reverse" +
                "         WHERE scheme_code=s.scheme_code AND run_id=r.id AND is_deleted=0) AS row_count" +
                "  FROM prcp_reverse_run r" +
                "  JOIN prcp_reverse_scheme s ON s.id = r.scheme_id" +
                " WHERE s.scheme_code=? AND r.status='SUCCESS'" +
                " ORDER BY r.id DESC LIMIT 200";
        List<Map<String, Object>> items = jdbc.queryForList(sql, schemeCode);
        return R.ok(Collections.singletonMap("items", items));
    }

    /**
     * <p>方案 + Run 下的所有 data_date + date_offset (DISTINCT)</p>
     *
     * @param schemeCode 反算方案编码 (必填)
     * @param runId      Run ID (可选)
     * @return R.ok(Map.of("items", list))
     */
    public R<Map<String, Object>> listDates(String schemeCode, Long runId) {
        if (schemeCode == null || schemeCode.isEmpty()) throw BizException.badRequest("scheme_code 必填");
        StringBuilder sql = new StringBuilder(
                "SELECT DISTINCT data_date, date_offset, offset_unit" +
                "  FROM prcp_data_reverse" +
                " WHERE is_deleted=0 AND scheme_code=?");
        List<Object> params = new ArrayList<>();
        params.add(schemeCode);
        if (runId != null) {
            sql.append(" AND run_id=?");
            params.add(runId);
        }
        sql.append(" ORDER BY date_offset, data_date");
        List<Map<String, Object>> items = jdbc.queryForList(sql.toString(), params.toArray());
        return R.ok(Collections.singletonMap("items", items));
    }

    /**
     * <p>节点 × 128 桶 (orig+rem) + 度量 矩阵 (账户册矩阵)</p>
     *
     * @param schemeCode 反算方案编码 (必填)
     * @param runId      Run ID (可选)
     * @param dataDate   数据日期 yyyy-MM-dd (可选)
     * @param dateOffset 日期偏移 (可选)
     * @return R.ok(Map.of("scheme_code"/"run_id"/"data_date"/"date_offset"/"buckets"/"rows"/"total", ...)) LIMIT 1000
     */
    public R<Map<String, Object>> bySchemeMatrix(String schemeCode, Long runId, String dataDate, Integer dateOffset) {
        if (schemeCode == null || schemeCode.isEmpty()) throw BizException.badRequest("scheme_code 必填");
        StringBuilder sql = new StringBuilder(
                "SELECT coa_node_id, node_code, node_name, node_level, parent_code, is_leaf, category," +
                "       asf_rsf, hqla_factor, current_balance, avg_balance," +
                "       weighted_rate, interest_amount, risk_weight");
        // 64 orig 桶 + 64 rem 桶 = 128 列
        for (String k : BasicDataBuckets.KEYS) {
            sql.append(", orig_").append(k).append(" AS orig").append(toCamel(k));
            sql.append(", rem_").append(k).append("  AS rem").append(toCamel(k));
        }
        sql.append(" FROM prcp_data_reverse WHERE is_deleted=0 AND scheme_code=?");
        List<Object> params = new ArrayList<>();
        params.add(schemeCode);
        if (runId != null) { sql.append(" AND run_id=?"); params.add(runId); }
        if (dataDate != null && !dataDate.isEmpty()) { sql.append(" AND data_date=?"); params.add(LocalDate.parse(dataDate)); }
        if (dateOffset != null) { sql.append(" AND date_offset=?"); params.add(dateOffset); }
        sql.append(" ORDER BY node_level, parent_code, node_code LIMIT 1000");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), params.toArray());

        // 构造 matrix[coaNodeId] 形式给前端按行访问
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("scheme_code", schemeCode);
        resp.put("run_id", runId);
        resp.put("data_date", dataDate);
        resp.put("date_offset", dateOffset);
        resp.put("buckets", BasicDataBuckets.KEYS);
        resp.put("rows", rows);
        resp.put("total", rows.size());
        return R.ok(resp);
    }

    /**
     * <p>大类汇总: 按 category 聚合 128 桶 + 度量</p>
     *
     * @param schemeCode 反算方案编码 (必填)
     * @param runId      Run ID (可选)
     * @param dataDate   数据日期 yyyy-MM-dd (可选)
     * @param dateOffset 日期偏移 (可选)
     * @return R.ok(Map.of("items", list)) 按 total_current_balance DESC
     */
    public R<Map<String, Object>> categorySummary(String schemeCode, Long runId, String dataDate, Integer dateOffset) {
        if (schemeCode == null || schemeCode.isEmpty()) throw BizException.badRequest("scheme_code 必填");
        StringBuilder sql = new StringBuilder(
                "SELECT category, COUNT(*) AS node_count," +
                "       SUM(current_balance) AS total_current_balance," +
                "       SUM(avg_balance) AS total_avg_balance," +
                "       SUM(interest_amount) AS total_interest");
        for (String k : BasicDataBuckets.KEYS) {
            sql.append(", SUM(orig_").append(k).append(") AS total_orig_").append(k);
            sql.append(", SUM(rem_").append(k).append(")  AS total_rem_").append(k);
        }
        sql.append(" FROM prcp_data_reverse WHERE is_deleted=0 AND scheme_code=?");
        List<Object> params = new ArrayList<>();
        params.add(schemeCode);
        if (runId != null) { sql.append(" AND run_id=?"); params.add(runId); }
        if (dataDate != null && !dataDate.isEmpty()) { sql.append(" AND data_date=?"); params.add(LocalDate.parse(dataDate)); }
        if (dateOffset != null) { sql.append(" AND date_offset=?"); params.add(dateOffset); }
        sql.append(" GROUP BY category ORDER BY total_current_balance DESC");
        List<Map<String, Object>> items = jdbc.queryForList(sql.toString(), params.toArray());
        return R.ok(Collections.singletonMap("items", items));
    }

    /**
     * <p>Excel 导出: 节点 × 128 桶 + 度量</p>
     *
     * @param schemeCode 反算方案编码 (必填)
     * @param runId      Run ID (可选)
     * @param out        输出流 (用于直接写出 xlsx 字节)
     */
    public void exportXlsx(String schemeCode, Long runId, OutputStream out) {
        if (schemeCode == null || schemeCode.isEmpty()) throw BizException.badRequest("scheme_code 必填");
        StringBuilder sql = new StringBuilder(
                "SELECT node_code, node_name, node_level, parent_code, category," +
                "       asf_rsf, hqla_factor, current_balance, avg_balance," +
                "       weighted_rate, interest_amount, risk_weight");
        for (String k : BasicDataBuckets.KEYS) {
            sql.append(", orig_").append(k).append(", rem_").append(k);
        }
        sql.append(" FROM prcp_data_reverse WHERE is_deleted=0 AND scheme_code=?");
        List<Object> params = new ArrayList<>();
        params.add(schemeCode);
        if (runId != null) { sql.append(" AND run_id=?"); params.add(runId); }
        sql.append(" ORDER BY node_level, parent_code, node_code LIMIT 5000");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), params.toArray());

        try {
            // 表头
            List<List<String>> head = new ArrayList<>();
            head.add(Collections.singletonList("节点编码"));
            head.add(Collections.singletonList("节点名称"));
            head.add(Collections.singletonList("层级"));
            head.add(Collections.singletonList("父编码"));
            head.add(Collections.singletonList("大类"));
            head.add(Collections.singletonList("ASF/RSF"));
            head.add(Collections.singletonList("HQLA"));
            head.add(Collections.singletonList("当前余额"));
            head.add(Collections.singletonList("平均余额"));
            head.add(Collections.singletonList("加权利率"));
            head.add(Collections.singletonList("利息"));
            head.add(Collections.singletonList("风险权重"));
            for (String k : BasicDataBuckets.KEYS) {
                head.add(Collections.singletonList("原始-" + BasicDataBuckets.NAMES.get(k)));
                head.add(Collections.singletonList("剩余-" + BasicDataBuckets.NAMES.get(k)));
            }
            // 数据
            List<List<Object>> data = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                List<Object> line = new ArrayList<>();
                line.add(nullSafe(row.get("node_code")));
                line.add(nullSafe(row.get("node_name")));
                line.add(row.get("node_level"));
                line.add(nullSafe(row.get("parent_code")));
                line.add(nullSafe(row.get("category")));
                line.add(nullSafe(row.get("asf_rsf")));
                line.add(row.get("hqla_factor"));
                line.add(row.get("current_balance"));
                line.add(row.get("avg_balance"));
                line.add(row.get("weighted_rate"));
                line.add(row.get("interest_amount"));
                line.add(row.get("risk_weight"));
                for (String k : BasicDataBuckets.KEYS) {
                    line.add(row.get("orig_" + k));
                    line.add(row.get("rem_" + k));
                }
                data.add(line);
            }
            EasyExcel.write(out).head(head).sheet("反算结果_" + schemeCode).doWrite(data);
        } catch (Exception e) {
            log.error("导出 Excel 失败", e);
            throw BizException.badRequest("导出失败：" + e.getMessage());
        }
    }

    private static String toCamel(String k) {
        if (k.startsWith("m")) return "M" + k.substring(1);
        if (k.startsWith("y")) return "Y" + k.substring(1);
        return k;
    }

    private static String nullSafe(Object v) {
        return v == null ? "" : v.toString();
    }
}