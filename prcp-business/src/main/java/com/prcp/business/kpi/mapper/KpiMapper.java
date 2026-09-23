package com.prcp.business.kpi.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.kpi.entity.KpiDefinition;
import com.prcp.business.kpi.entity.KpiScoreRule;
import com.prcp.business.kpi.entity.KpiValue;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface KpiMapper extends BaseMapper<KpiDefinition> {

    /** KPI 定义列表（连 scheme 和 report 名称） */
    @Select("""
        SELECT d.id, d.scheme_id AS schemeId, s.scheme_name AS schemeName,
               s.scheme_code AS schemeCode,
               d.indicator_type AS indicatorType,
               d.kpi_code AS kpiCode, d.kpi_name AS kpiName,
               d.rpt_id AS rptId, r.report_name AS reportName,
               d.formula, d.script_path AS scriptPath, d.script_name AS scriptName,
               d.calc_unit AS calcUnit, d.formula_desc AS formulaDesc,
               d.threshold_min AS thresholdMin, d.threshold_max AS thresholdMax,
               d.status, d.created_at AS createdAt
        FROM prcp_kpi_definition d
        LEFT JOIN prcp_kpi_scheme s ON s.id = d.scheme_id AND s.is_deleted = 0
        LEFT JOIN prcp_rpt_report r ON r.id = d.rpt_id
        WHERE d.is_deleted = 0
        ORDER BY d.id DESC
    """)
    List<Map<String, Object>> listDefs(@Param("schemeId") Long schemeId,
                                        @Param("kpiCode") String kpiCode,
                                        @Param("keyword") String keyword);

    /** KPI 值列表 */
    @Select("""
        SELECT v.id, v.kpi_id AS kpiId, d.kpi_code AS kpiCode, d.kpi_name AS kpiName,
               v.data_date AS dataDate, v.version,
               v.current_value AS currentValue, v.prev_value AS prevValue,
               v.prev_year_value AS prevYearValue, v.calc_source AS calcSource,
               v.score
        FROM prcp_kpi_value v
        JOIN prcp_kpi_definition d ON d.id = v.kpi_id AND d.is_deleted = 0
        WHERE v.is_deleted = 0
        ORDER BY v.data_date DESC, v.id DESC
    """)
    List<Map<String, Object>> listValues(@Param("kpiId") Long kpiId,
                                           @Param("dataDate") String dataDate);

    /** 评分规则列表 */
    @Select("""
        SELECT r.id, r.scheme_id AS schemeId, s.scheme_name AS schemeName,
               r.kpi_id AS kpiId, d.kpi_code AS kpiCode, d.kpi_name AS kpiName,
               r.rule_name AS ruleName, r.calc_method AS calcMethod,
               r.total_score AS totalScore, r.higher_is_better AS higherIsBetter,
               r.description, r.status
        FROM prcp_kpi_score_rule r
        LEFT JOIN prcp_kpi_scheme s ON s.id = r.scheme_id AND s.is_deleted = 0
        LEFT JOIN prcp_kpi_definition d ON d.id = r.kpi_id AND d.is_deleted = 0
        WHERE r.is_deleted = 0
        ORDER BY r.id DESC
    """)
    List<Map<String, Object>> listScoreRules(@Param("schemeId") Long schemeId,
                                              @Param("kpiId") Long kpiId);

    /** 报表表项（供 KPI 公式引用） */
    @Select("""
        SELECT id, report_id AS reportId, item_code AS itemCode, item_name AS itemName,
               data_type AS dataType
        FROM prcp_rpt_item
        WHERE report_id = #{rptId} AND is_deleted = 0
        ORDER BY item_level, sort_order
    """)
    List<Map<String, Object>> listRptItems(@Param("rptId") Long rptId);

    /** KPI 方案列表 */
    @Select("""
        SELECT id, scheme_code AS schemeCode, scheme_name AS schemeName,
               description, status
        FROM prcp_kpi_scheme
        WHERE is_deleted = 0
        ORDER BY id
    """)
    List<Map<String, Object>> listKpiSchemes();
}