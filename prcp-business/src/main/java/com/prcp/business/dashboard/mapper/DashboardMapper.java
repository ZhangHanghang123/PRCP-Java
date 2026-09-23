package com.prcp.business.dashboard.mapper;

import org.apache.ibatis.annotations.Mapper;
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
}