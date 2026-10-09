package com.prcp.business.dashboard.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * <p>Mapper: 驾驶舱 Dashboard 的 SQL 访问层 (不绑定具体表, 多表聚合查询)</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>overview - 各表数量统计 (9 个 COUNT 子查询)</li>
 *   <li>kpiTrend - 指标录入趋势 (近 N 天)</li>
 *   <li>schemeDistribution - 指标方案下定义分布</li>
 *   <li>topKpis - Top10 KPI (按当前值绝对值)</li>
 *   <li>reverseKpiSummary - 反算驾驶舱 KPI 卡片 (9 个 SUM/COUNT)</li>
 *   <li>keyIndicatorValues - 5 关键指标最新值</li>
 *   <li>keyIndicatorTrend - 5 指标 24 月趋势 (ROW_NUMBER 窗口)</li>
 *   <li>categoryDistribution - 大类分布 (按 path 前缀 ASSET/LIABILITY/EQUITY/OFF_BALANCE)</li>
 *   <li>listCoaSchemes/listReverseRuns/listReverseSchemes - 顶部筛选下拉</li>
 *   <li>nodeMetricMatrix - 节点 × 指标 数据矩阵</li>
 *   <li>topNodesByBalance - Top N 节点 (按余额绝对值)</li>
 *   <li>riskAlerts - 风险预警 (余额为负/利率超阈值/L1 余额过低)</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface DashboardMapper {

    /**
     * <p>各表数量统计 (9 个 COUNT 子查询, 一次返回概览数字)</p>
     *
     * @return 单行 Map 含 schemes/coa_nodes/reports/rpt_items/kpi_schemes/kpi_defs/kpi_values/score_rules/latest_date
     */
    @Select("""
        SELECT
            (SELECT COUNT(*) FROM prcp_coa_scheme WHERE is_deleted = 0) AS schemes,
            (SELECT COUNT(*) FROM prcp_coa_node WHERE is_deleted = 0) AS coa_nodes,
            (SELECT COUNT(*) FROM prcp_rpt_report WHERE is_deleted = 0) AS reports,
            (SELECT COUNT(*) FROM prcp_rpt_item WHERE is_deleted = 0) AS rpt_items,
            (SELECT COUNT(*) FROM prcp_kpi_scheme WHERE is_deleted = 0) AS kpi_schemes,
            (SELECT COUNT(*) FROM prcp_kpi_definition WHERE is_deleted = 0) AS kpi_defs,
            (SELECT COUNT(*) FROM prcp_kpi_value WHERE is_deleted = 0) AS kpi_values,
            (SELECT COUNT(*) FROM prcp_kpi_score_rule WHERE is_deleted = 0) AS score_rules,
            (SELECT MAX(data_date) FROM prcp_kpi_value WHERE is_deleted = 0) AS latest_date
    """)
    Map<String, Object> overview();

    /**
     * <p>指标录入趋势 (近 N 天, 按 created_at DATE 分组)</p>
     *
     * @param days 天数 (例 7/90)
     * @return [{d, n}, ...] 按日期升序
     */
    @Select("""
        SELECT DATE(created_at) AS d, COUNT(*) AS n
        FROM prcp_kpi_value
        WHERE is_deleted = 0 AND created_at >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY)
        GROUP BY DATE(created_at) ORDER BY d
    """)
    List<Map<String, Object>> kpiTrend(int days);

    /**
     * <p>指标方案下定义分布 (JOIN prcp_kpi_definition + kpi_value, 取 defCount 与 scoredCount)</p>
     *
     * @return 按 defCount DESC 取前 8
     */
    @Select("""
        SELECT s.scheme_code AS schemeCode, s.scheme_name AS schemeName,
               COUNT(d.id) AS defCount,
               COALESCE(SUM(CASE WHEN v.score IS NOT NULL THEN 1 ELSE 0 END), 0) AS scoredCount
        FROM prcp_kpi_scheme s
        LEFT JOIN prcp_kpi_definition d ON d.scheme_id = s.id AND d.is_deleted = 0
        LEFT JOIN prcp_kpi_value v ON v.kpi_id = d.id AND v.is_deleted = 0
        WHERE s.is_deleted = 0
        GROUP BY s.id, s.scheme_code, s.scheme_name
        ORDER BY defCount DESC LIMIT 8
    """)
    List<Map<String, Object>> schemeDistribution();

    /**
     * <p>Top10 KPI (按 current_value 绝对值排序)</p>
     *
     * @return KPI 列表, 字段含 kpiCode/kpiName/currentValue/dataDate/score
     */
    @Select("""
        SELECT d.kpi_code AS kpiCode, d.kpi_name AS kpiName,
               v.current_value AS currentValue, v.data_date AS dataDate, v.score
        FROM prcp_kpi_value v
        JOIN prcp_kpi_definition d ON d.id = v.kpi_id AND d.is_deleted = 0
        WHERE v.is_deleted = 0 AND v.current_value IS NOT NULL
        ORDER BY ABS(v.current_value) DESC LIMIT 10
    """)
    List<Map<String, Object>> topKpis();

    // ============ PRD 风格驾驶舱：9 个业务 KPI + 24 月趋势 + 大类分布 ============

    /**
     * <p>反算驾驶舱 KPI 卡片数据 (一次拿全部 9 个: coa_nodes/kpi_defs/scored/balance_date/4 类金额)</p>
     * <p>金额按 path 前缀 L1_ASSET/L1_LIABILITY/L1_OFF_BALANCE/L1_EQUITY 分组汇总</p>
     *
     * @return 单行 Map
     */
    @Select("""
        SELECT
            (SELECT COUNT(*) FROM prcp_coa_node WHERE is_deleted = 0) AS coa_nodes,
            (SELECT COUNT(*) FROM prcp_kpi_definition WHERE is_deleted = 0) AS kpi_defs,
            (SELECT COUNT(*) FROM prcp_kpi_value WHERE is_deleted = 0 AND score IS NOT NULL) AS scored,
            (SELECT MAX(data_date) FROM prcp_data_balance WHERE is_deleted = 0) AS balance_date,
            (SELECT COALESCE(SUM(current_amount),0)
               FROM prcp_data_balance b
               JOIN prcp_coa_node n ON n.id = b.coa_node_id AND n.is_deleted = 0
               WHERE b.is_deleted = 0 AND n.path LIKE '%/L1_ASSET/%') AS asset_amt,
            (SELECT COALESCE(SUM(current_amount),0)
               FROM prcp_data_balance b
               JOIN prcp_coa_node n ON n.id = b.coa_node_id AND n.is_deleted = 0
               WHERE b.is_deleted = 0 AND n.path LIKE '%/L1_LIABILITY/%') AS liability_amt,
            (SELECT COALESCE(SUM(current_amount),0)
               FROM prcp_data_balance b
               JOIN prcp_coa_node n ON n.id = b.coa_node_id AND n.is_deleted = 0
               WHERE b.is_deleted = 0 AND n.path LIKE '%/L1_OFF_BALANCE/%') AS off_balance_amt,
            (SELECT COALESCE(SUM(current_amount),0)
               FROM prcp_data_balance b
               JOIN prcp_coa_node n ON n.id = b.coa_node_id AND n.is_deleted = 0
               WHERE b.is_deleted = 0 AND n.path LIKE '%/L1_EQUITY/%') AS equity_amt
    """)
    Map<String, Object> reverseKpiSummary();

    /**
     * <p>5 个关键指标的当前最新值 (按 kpi_code 固定顺序)</p>
     * <p>kpi_code IN: KPI_PNN_ROE / KPI_PNN_CET1 / KPI_PNN_LCR / KPI_PNN_NSFR / KPI_PNN_DEVE</p>
     *
     * @return 5 行, 顺序由 FIELD() 固定
     */
    @Select("""
        SELECT d.kpi_code AS code, d.kpi_name AS name,
               v.current_value AS value, v.data_date AS dataDate
        FROM prcp_kpi_value v
        JOIN prcp_kpi_definition d ON d.id = v.kpi_id AND d.is_deleted = 0
        WHERE v.is_deleted = 0 AND d.kpi_code IN
            ('KPI_PNN_ROE','KPI_PNN_CET1','KPI_PNN_LCR','KPI_PNN_NSFR','KPI_PNN_DEVE')
        ORDER BY FIELD(d.kpi_code,'KPI_PNN_ROE','KPI_PNN_CET1','KPI_PNN_LCR','KPI_PNN_NSFR','KPI_PNN_DEVE')
    """)
    List<Map<String, Object>> keyIndicatorValues();

    /**
     * <p>5 指标 24 月趋势 (ROW_NUMBER 窗口取每个 kpi 最近 24 个值)</p>
     *
     * @return 按 code + data_date 升序
     */
    @Select("""
        SELECT t.code AS code, t.name AS name, t.data_date AS dataDate, t.value
        FROM (
            SELECT d.kpi_code AS code, d.kpi_name AS name,
                   v.data_date AS data_date, v.current_value AS value,
                   ROW_NUMBER() OVER (PARTITION BY d.kpi_code ORDER BY v.data_date DESC) AS rn
            FROM prcp_kpi_value v
            JOIN prcp_kpi_definition d ON d.id = v.kpi_id AND d.is_deleted = 0
            WHERE v.is_deleted = 0 AND d.kpi_code IN
                ('KPI_PNN_ROE','KPI_PNN_CET1','KPI_PNN_LCR','KPI_PNN_NSFR','KPI_PNN_DEVE')
              AND v.current_value IS NOT NULL
        ) t
        WHERE t.rn <= 24
        ORDER BY t.code, t.data_date
    """)
    List<Map<String, Object>> keyIndicatorTrend();

    /**
     * <p>大类分布 (环形 + 柱状共用, 按 path 前缀分组 ASSET/LIABILITY/EQUITY/OFF_BALANCE/OTHER)</p>
     *
     * @param dataDate 数据日期 yyyy-MM-dd
     * @return 按 amount DESC 排序
     */
    @Select("""
        SELECT
            CASE
                WHEN n.path LIKE '%/L1_ASSET/%'       THEN 'ASSET'
                WHEN n.path LIKE '%/L1_LIABILITY/%'   THEN 'LIABILITY'
                WHEN n.path LIKE '%/L1_EQUITY/%'      THEN 'EQUITY'
                WHEN n.path LIKE '%/L1_OFF_BALANCE/%' THEN 'OFF_BALANCE'
                ELSE 'OTHER'
            END AS category,
            COALESCE(SUM(b.current_amount), 0) AS amount,
            COUNT(*) AS cnt
        FROM prcp_data_balance b
        JOIN prcp_coa_node n ON n.id = b.coa_node_id AND n.is_deleted = 0
        WHERE b.is_deleted = 0 AND b.data_date = #{dataDate}
        GROUP BY category
        ORDER BY amount DESC
    """)
    List<Map<String, Object>> categoryDistribution(@Param("dataDate") String dataDate);

    /**
     * <p>账户册方案下拉 (顶部筛选器)</p>
     *
     * @return [{value, label}, ...]
     */
    @Select("""
        SELECT id AS value, scheme_name AS label
        FROM prcp_coa_scheme
        WHERE is_deleted = 0
        ORDER BY id DESC
    """)
    List<Map<String, Object>> listCoaSchemes();

    /**
     * <p>反算运行记录下拉 (最多 50 条, 按 ID DESC)</p>
     *
     * @return label = "run_code [status]"
     */
    @Select("""
        SELECT id AS value, CONCAT(IFNULL(run_code,''), ' [', IFNULL(status,''), ']') AS label
        FROM prcp_reverse_run
        WHERE is_deleted = 0
        ORDER BY id DESC LIMIT 50
    """)
    List<Map<String, Object>> listReverseRuns();

    /**
     * <p>反算方案下拉 (顶部筛选器: scheme_code + scheme_name)</p>
     *
     * @return 最多 50 条, label = "scheme_code - scheme_name [status]"
     */
    @Select("""
        SELECT id AS value, CONCAT(IFNULL(scheme_code,''), ' - ', IFNULL(scheme_name,''), ' [', IFNULL(status,''), ']') AS label
        FROM prcp_reverse_scheme
        WHERE is_deleted = 0
        ORDER BY id DESC LIMIT 50
    """)
    List<Map<String, Object>> listReverseSchemes();

    /**
     * <p>节点 × 指标 数据矩阵 (按 category 分组, 按 level 排序)</p>
     * <p>数据源: prcp_data_reverse 的 current_balance / weighted_rate, 取最新 data_date</p>
     * <p>每节点一行: node_code, node_name, level, category, current_balance, weighted_rate</p>
     *
     * @return 最多 500 行, 按 category/level/id 排序
     */
    @Select("""
        SELECT n.id AS node_id,
               n.node_code AS node_code,
               n.node_name AS node_name,
               n.node_level AS level,
               CASE
                   WHEN n.path LIKE '%/L1_ASSET/%'        OR n.node_code LIKE 'ZX_A%' THEN 'ASSET'
                   WHEN n.path LIKE '%/L1_LIABILITY/%'    OR n.node_code LIKE 'ZX_L%' THEN 'LIABILITY'
                   WHEN n.path LIKE '%/L1_EQUITY/%'       OR n.node_code LIKE 'ZX_E%' THEN 'EQUITY'
                   WHEN n.path LIKE '%/L1_OFF_BALANCE/%'  OR n.node_code LIKE 'ZX_O%' THEN 'OFF_BALANCE'
                   ELSE 'OTHER'
               END AS category,
               COALESCE(r.current_balance, 0) AS current_balance,
               COALESCE(r.weighted_rate, 0)    AS weighted_rate,
               COALESCE(r.avg_balance, 0)      AS avg_balance,
               COALESCE(r.interest_amount, 0)  AS interest_amount
        FROM prcp_data_reverse r
        JOIN prcp_coa_node n ON n.id = r.coa_node_id AND n.is_deleted = 0
        WHERE r.is_deleted = 0
          AND r.data_date = (SELECT MAX(data_date) FROM prcp_data_reverse WHERE is_deleted = 0)
        ORDER BY category, level, n.id
        LIMIT 500
    """)
    List<Map<String, Object>> nodeMetricMatrix();

    /**
     * <p>Top N 节点 (按 current_balance 绝对值)</p>
     *
     * @param limit 取前 N 条
     * @return 节点 + category + 余额 + 加权利率
     */
    @Select("""
        SELECT n.id AS node_id,
               n.node_code AS node_code,
               n.node_name AS node_name,
               n.node_level AS level,
               CASE
                   WHEN n.path LIKE '%/L1_ASSET/%'        OR n.node_code LIKE 'ZX_A%' THEN 'ASSET'
                   WHEN n.path LIKE '%/L1_LIABILITY/%'    OR n.node_code LIKE 'ZX_L%' THEN 'LIABILITY'
                   WHEN n.path LIKE '%/L1_EQUITY/%'       OR n.node_code LIKE 'ZX_E%' THEN 'EQUITY'
                   WHEN n.path LIKE '%/L1_OFF_BALANCE/%'  OR n.node_code LIKE 'ZX_O%' THEN 'OFF_BALANCE'
                   ELSE 'OTHER'
               END AS category,
               r.current_balance AS current_balance,
               r.weighted_rate    AS weighted_rate
        FROM prcp_data_reverse r
        JOIN prcp_coa_node n ON n.id = r.coa_node_id AND n.is_deleted = 0
        WHERE r.is_deleted = 0
          AND r.data_date = (SELECT MAX(data_date) FROM prcp_data_reverse WHERE is_deleted = 0)
        ORDER BY ABS(COALESCE(r.current_balance, 0)) DESC
        LIMIT #{limit}
    """)
    List<Map<String, Object>> topNodesByBalance(@Param("limit") int limit);

    /**
     * <p>风险预警: 余额异常大的负值 / 加权利率超阈值 / L1 节点余额过低</p>
     *
     * @return 最多 50 行, 含 alert_type (余额为负/加权利率超阈值/L1 节点余额过低/正常) 与 severity (critical/warning/info)
     */
    @Select("""
        SELECT n.id AS node_id,
               n.node_code AS node_code,
               n.node_name AS node_name,
               n.node_level AS level,
               CASE
                   WHEN n.path LIKE '%/L1_ASSET/%'        OR n.node_code LIKE 'ZX_A%' THEN 'ASSET'
                   WHEN n.path LIKE '%/L1_LIABILITY/%'    OR n.node_code LIKE 'ZX_L%' THEN 'LIABILITY'
                   WHEN n.path LIKE '%/L1_EQUITY/%'       OR n.node_code LIKE 'ZX_E%' THEN 'EQUITY'
                   WHEN n.path LIKE '%/L1_OFF_BALANCE/%'  OR n.node_code LIKE 'ZX_O%' THEN 'OFF_BALANCE'
                   ELSE 'OTHER'
               END AS category,
               r.current_balance AS current_balance,
               r.weighted_rate    AS weighted_rate,
               CASE
                   WHEN r.current_balance < 0                            THEN '余额为负'
                   WHEN ABS(r.weighted_rate) > 20                        THEN '加权利率超阈值'
                   WHEN n.node_level = 1 AND ABS(r.current_balance) < 1 THEN 'L1 节点余额过低'
                   ELSE '正常'
               END AS alert_type,
               CASE
                   WHEN r.current_balance < 0                            THEN 'critical'
                   WHEN ABS(r.weighted_rate) > 20                        THEN 'warning'
                   WHEN n.node_level = 1 AND ABS(r.current_balance) < 1 THEN 'warning'
                   ELSE 'info'
               END AS severity
        FROM prcp_data_reverse r
        JOIN prcp_coa_node n ON n.id = r.coa_node_id AND n.is_deleted = 0
        WHERE r.is_deleted = 0
          AND r.data_date = (SELECT MAX(data_date) FROM prcp_data_reverse WHERE is_deleted = 0)
          AND (r.current_balance < 0
               OR ABS(r.weighted_rate) > 20
               OR (n.node_level = 1 AND ABS(r.current_balance) < 1))
        ORDER BY ABS(COALESCE(r.current_balance, 0)) DESC
        LIMIT 50
    """)
    List<Map<String, Object>> riskAlerts();
}