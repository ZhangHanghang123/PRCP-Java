package com.prcp.business.metric.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.metric.entity.MetricCoefficient;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * <p>Mapper: prcp_metric_coefficient 表的 SQL 访问层 (指标系数表)</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>query - 多维查询 (schemeId/nodeCode/metricCode/dataDate), LEFT JOIN node/scheme/dict 取名称</li>
 *   <li>listSchemeOptions - 账户册方案下拉</li>
 *   <li>listNodeOptions - 节点下拉 (可按 schemeId 过滤)</li>
 *   <li>listMetricOptions - METRIC_TYPE 字典 (指标类型下拉)</li>
 * </ul>
 * </p>
 *
 * <p>度量字段: current_value + y1_value..y5_value (5 年预测)。</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface MetricMapper extends BaseMapper<MetricCoefficient> {

    /**
     * <p>指标系数多维查询 (LEFT JOIN coa_node 取 nodeName, coa_scheme 取 schemeName, sys_dict 取 metricLabel)</p>
     *
     * @param schemeId  方案 ID (可选)
     * @param nodeCode  节点编码 (可选)
     * @param metricCode 指标编码 (可选)
     * @param dataDate  数据日期 yyyy-MM-dd (可选)
     * @return 系数 Map 列表, 含 currentValue/y1Value..y5Value, 按 scheme_code, node_code, metric_code, data_date DESC 排序
     */
    @Select("""
        SELECT
          mc.id,
          mc.scheme_id AS schemeId,
          mc.scheme_code AS schemeCode,
          mc.node_id AS nodeId,
          mc.node_code AS nodeCode,
          mc.metric_type AS metricType,
          mc.metric_code AS metricCode,
          mc.data_date AS dataDate,
          mc.current_value AS currentValue,
          mc.y1_value AS y1Value,
          mc.y2_value AS y2Value,
          mc.y3_value AS y3Value,
          mc.y4_value AS y4Value,
          mc.y5_value AS y5Value,
          mc.unit,
          mc.description,
          mc.status,
          n.node_name AS nodeName,
          s.scheme_name AS schemeName,
          d.dict_label AS metricLabel
        FROM prcp_metric_coefficient mc
        LEFT JOIN prcp_coa_node n ON n.id = mc.node_id AND n.is_deleted = 0
        LEFT JOIN prcp_coa_scheme s ON s.id = mc.scheme_id AND s.is_deleted = 0
        LEFT JOIN sys_dict d ON d.dict_type = 'METRIC_TYPE' AND d.dict_key = mc.metric_code AND d.is_deleted = 0
        WHERE mc.is_deleted = 0
          AND (#{schemeId} IS NULL OR mc.scheme_id = #{schemeId})
          AND (#{nodeCode} IS NULL OR mc.node_code = #{nodeCode})
          AND (#{metricCode} IS NULL OR mc.metric_code = #{metricCode})
          AND (#{dataDate} IS NULL OR mc.data_date = #{dataDate})
        ORDER BY mc.scheme_code, mc.node_code, mc.metric_code, mc.data_date DESC
    """)
    List<Map<String, Object>> query(@Param("schemeId") Long schemeId,
                                     @Param("nodeCode") String nodeCode,
                                     @Param("metricCode") String metricCode,
                                     @Param("dataDate") String dataDate);

    /**
     * <p>账户册方案下拉 (按 ID 升序)</p>
     *
     * @return [{id, schemeCode, schemeName}, ...]
     */
    @Select("""
        SELECT id, scheme_code AS schemeCode, scheme_name AS schemeName
        FROM prcp_coa_scheme WHERE is_deleted = 0 ORDER BY id
    """)
    List<Map<String, Object>> listSchemeOptions();

    /**
     * <p>节点下拉 (可按 schemeId 过滤, 按 path, sort_order 排序)</p>
     *
     * @param schemeId 方案 ID (可选)
     * @return [{id, nodeCode, nodeName, nodeLevel}, ...]
     */
    @Select("""
        SELECT id, node_code AS nodeCode, node_name AS nodeName, node_level AS nodeLevel
        FROM prcp_coa_node WHERE is_deleted = 0
          AND (#{schemeId} IS NULL OR scheme_id = #{schemeId})
        ORDER BY path, sort_order
    """)
    List<Map<String, Object>> listNodeOptions(@Param("schemeId") Long schemeId);

    /**
     * <p>METRIC_TYPE 字典下拉 (指标类型)</p>
     *
     * @return [{metricCode, metricLabel, color, sortOrder}, ...]
     */
    @Select("""
        SELECT dict_key AS metricCode, dict_label AS metricLabel, color, sort_order AS sortOrder
        FROM sys_dict WHERE is_deleted = 0 AND status = 'ACTIVE' AND dict_type = 'METRIC_TYPE'
        ORDER BY sort_order
    """)
    List<Map<String, Object>> listMetricOptions();
}

