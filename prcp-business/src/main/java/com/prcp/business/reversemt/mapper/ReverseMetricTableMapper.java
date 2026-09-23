package com.prcp.business.reversemt.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface ReverseMetricTableMapper {

    /**
     * 反算指标结果表：按方案 + 数据日期范围，列出每条 (scheme_code, node_code, metric_code) × 各期 current/y1..y5
     */
    @Select("""
        SELECT
          mc.scheme_code AS schemeCode,
          mc.node_code AS nodeCode,
          n.node_name AS nodeName,
          mc.metric_code AS metricCode,
          d.dict_label AS metricLabel,
          mc.data_date AS dataDate,
          mc.current_value AS currentValue,
          mc.y1_value AS y1Value,
          mc.y2_value AS y2Value,
          mc.y3_value AS y3Value,
          mc.y4_value AS y4Value,
          mc.y5_value AS y5Value,
          mc.unit
        FROM prcp_metric_coefficient mc
        LEFT JOIN prcp_coa_node n ON n.id = mc.node_id AND n.is_deleted = 0
        LEFT JOIN sys_dict d ON d.dict_type = 'PRCP_METRIC_TYPE' AND d.dict_key = mc.metric_code AND d.is_deleted = 0
        WHERE mc.is_deleted = 0
          AND (:schemeCode IS NULL OR mc.scheme_code = :schemeCode)
          AND (:metricCode IS NULL OR mc.metric_code = :metricCode)
          AND (:fromDate IS NULL OR mc.data_date >= :fromDate)
          AND (:toDate   IS NULL OR mc.data_date <= :toDate)
        ORDER BY mc.scheme_code, mc.metric_code, mc.node_code, mc.data_date
    """)
    List<Map<String, Object>> queryTable(@Param("schemeCode") String schemeCode,
                                          @Param("metricCode") String metricCode,
                                          @Param("fromDate") String fromDate,
                                          @Param("toDate") String toDate);

    /**
     * 取可用的方案编码
     */
    @Select("SELECT DISTINCT scheme_code AS schemeCode FROM prcp_metric_coefficient WHERE is_deleted = 0 ORDER BY scheme_code")
    List<String> listSchemes();

    /**
     * 取可用的指标编码（来自 PRCP_METRIC_TYPE 字典）
     */
    @Select("SELECT dict_key AS metricCode, dict_label AS metricLabel FROM sys_dict WHERE is_deleted = 0 AND status='ACTIVE' AND dict_type='PRCP_METRIC_TYPE' ORDER BY sort_order")
    List<Map<String, Object>> listMetrics();
}