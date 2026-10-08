package com.prcp.business.dashboard.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface DashboardMapper {

    /** 各表数量统计 */
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

    /** 指标录入趋势（近 N 天） */
    @Select("""
        SELECT DATE(created_at) AS d, COUNT(*) AS n
        FROM prcp_kpi_value
        WHERE is_deleted = 0 AND created_at >= DATE_SUB(CURDATE(), INTERVAL #{days} DAY)
        GROUP BY DATE(created_at) ORDER BY d
    """)
    List<Map<String, Object>> kpiTrend(int days);

    /** 指标方案下定义分布 */
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

    /** Top10 KPI（按当前值） */
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

    /** 反算驾驶舱 KPI 卡片数据（一次拿全部 9 个） */
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

    /** 5 个关键指标的当前最新值（按 kpi_code） */
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

    /** 5 指标 24 月趋势（每个 kpi 拿最近 24 个值） */
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

    /** 大类分布（环形 + 柱状共用，按 path 前缀分组） */
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

    /** 账户册方案下拉（顶部筛选器） */
    @Select("""
        SELECT id AS value, scheme_name AS label
        FROM prcp_coa_scheme
        WHERE is_deleted = 0
        ORDER BY id DESC
    """)
    List<Map<String, Object>> listCoaSchemes();

    /** 反算运行记录下拉 */
    @Select("""
        SELECT id AS value, CONCAT(IFNULL(run_code,''), ' [', IFNULL(status,''), ']') AS label
        FROM prcp_reverse_run
        WHERE is_deleted = 0
        ORDER BY id DESC LIMIT 50
    """)
    List<Map<String, Object>> listReverseRuns();

    /** 反算方案下拉（顶部筛选器：scheme_code + scheme_name） */
    @Select("""
        SELECT id AS value, CONCAT(IFNULL(scheme_code,''), ' - ', IFNULL(scheme_name,''), ' [', IFNULL(status,''), ']') AS label
        FROM prcp_reverse_scheme
        WHERE is_deleted = 0
        ORDER BY id DESC LIMIT 50
    """)
    List<Map<String, Object>> listReverseSchemes();

    /**
     * 节点 × 指标 数据矩阵（按 category 分组，按 level 排序）
     * 数据源：prcp_data_reverse 的 current_balance / weighted_rate
     * 每节点一行：node_code, node_name, level, category, current_balance, weighted_rate
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

    /** Top N 节点（按 current_balance 绝对值） */
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

    /** 风险预警：余额异常大的负值 / 加权利率超阈值 */
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