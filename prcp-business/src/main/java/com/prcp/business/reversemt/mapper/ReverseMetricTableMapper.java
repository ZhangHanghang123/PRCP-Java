package com.prcp.business.reversemt.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * <p>Mapper: 反算指标表 (prcp_metric_coefficient) 的查询入口</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>queryTable - 多维查询 (schemeCode/metricCode/fromDate/toDate), 含 currentValue + y1..y5 预测</li>
 *   <li>listSchemes - 已有数据的方案列表 (下拉用)</li>
 *   <li>listMetrics - METRIC_TYPE 字典 (指标下拉)</li>
 * </ul>
 * </p>
 *
 * <p>说明: 与 {@link com.prcp.business.metric.mapper.MetricMapper} 字段重叠, 但参数语义不同 (按 scheme_code 字符串而非 id)。</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface ReverseMetricTableMapper {

    /**
     * <p>反算指标表多维查询 (LEFT JOIN coa_node 取 nodeName, sys_dict 取 metricLabel)</p>
     *
     * @param schemeCode 方案编码 (可选)
     * @param metricCode 指标编码 (可选)
     * @param fromDate   起始日期 yyyy-MM-dd (可选)
     * @param toDate     结束日期 yyyy-MM-dd (可选)
     * @return 系数 Map 列表, 含 currentValue/y1Value..y5Value, 按 scheme_code, metric_code, node_code, data_date 排序
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
        LEFT JOIN sys_dict d ON d.dict_type = 'METRIC_TYPE' AND d.dict_key = mc.metric_code AND d.is_deleted = 0
        WHERE mc.is_deleted = 0
          AND (#{schemeCode} IS NULL OR mc.scheme_code = #{schemeCode})
          AND (#{metricCode} IS NULL OR mc.metric_code = #{metricCode})
          AND (#{fromDate} IS NULL OR mc.data_date >= #{fromDate})
          AND (#{toDate}   IS NULL OR mc.data_date <= #{toDate})
        ORDER BY mc.scheme_code, mc.metric_code, mc.node_code, mc.data_date
    """)
    List<Map<String, Object>> queryTable(@Param("schemeCode") String schemeCode,
                                          @Param("metricCode") String metricCode,
                                          @Param("fromDate") String fromDate,
                                          @Param("toDate") String toDate);

    /**
     * <p>已有数据的方案编码 (下拉用)</p>
     *
     * @return scheme_code 字符串列表, 升序
     */
    @Select("SELECT DISTINCT scheme_code AS schemeCode FROM prcp_metric_coefficient WHERE is_deleted = 0 ORDER BY scheme_code")
    List<String> listSchemes();

    /**
     * <p>METRIC_TYPE 字典下拉 (指标类型)</p>
     *
     * @return [{metricCode, metricLabel}, ...]
     */
    @Select("SELECT dict_key AS metricCode, dict_label AS metricLabel FROM sys_dict WHERE is_deleted = 0 AND status='ACTIVE' AND dict_type='METRIC_TYPE' ORDER BY sort_order")
    List<Map<String, Object>> listMetrics();
}

