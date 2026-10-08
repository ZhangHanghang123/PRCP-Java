package com.prcp.business.params.lcr;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * LCR 参数补录 Mapper
 * <p>对位 Python app/routers/lcr_param.py</p>
 */
@Mapper
public interface LcrParamMapper extends BaseMapper<LcrParamEntity> {

    /**
     * 列表查询（JOIN prcp_coa_scheme 取 scheme_name）
     * <p>对应 Python GET /lcr-param/，默认过滤 is_deleted=0</p>
     */
    @Select("""
        SELECT p.id,
               p.scheme_id    AS schemeId,
               p.scheme_code  AS schemeCode,
               p.node_id      AS nodeId,
               p.node_code    AS nodeCode,
               p.node_name    AS nodeName,
               p.data_date    AS dataDate,
               p.is_numerator    AS isNumerator,
               p.num_factor      AS numFactor,
               p.num_operator    AS numOperator,
               p.is_denominator  AS isDenominator,
               p.den_factor      AS denFactor,
               p.den_operator    AS denOperator,
               p.current_balance AS currentBalance,
               p.rule_note    AS ruleNote,
               p.status,
               p.created_at   AS createdAt,
               p.updated_at   AS updatedAt,
               s.scheme_name  AS schemeName
        FROM prcp_lcr_param p
        LEFT JOIN prcp_coa_scheme s ON s.id = p.scheme_id AND s.is_deleted = 0
        WHERE p.is_deleted = 0
          AND (#{schemeId} IS NULL OR p.scheme_id = #{schemeId})
          AND (#{nodeId}   IS NULL OR p.node_id   = #{nodeId})
          AND (#{dataDate} IS NULL OR p.data_date = #{dataDate})
          AND (#{keyword}  IS NULL OR p.node_code LIKE CONCAT('%', #{keyword}, '%')
                            OR p.node_name LIKE CONCAT('%', #{keyword}, '%')
                            OR p.rule_note LIKE CONCAT('%', #{keyword}, '%'))
        ORDER BY p.data_date DESC, p.scheme_code, p.node_code
        LIMIT 5000
    """)
    List<Map<String, Object>> query(@Param("schemeId") Long schemeId,
                                    @Param("nodeId") Long nodeId,
                                    @Param("dataDate") String dataDate,
                                    @Param("keyword") String keyword);

    /**
     * 下拉选项：账户册方案 + 该方案下的节点 + LCR_OPERATOR 字典 + 已有数据日期
     * <p>对应 Python GET /lcr-param/options</p>
     */
    @Select("""
        SELECT 'schemes'  AS bucket, id, scheme_code AS code, scheme_name AS name,
               status, NULL AS scheme_id, NULL AS node_code,
               NULL AS node_name, NULL AS node_level, NULL AS sort_order,
               NULL AS dict_key, NULL AS dict_label, NULL AS data_date
        FROM prcp_coa_scheme
        WHERE is_deleted = 0 AND status = 'ACTIVE'
        UNION ALL
        SELECT 'nodes', id, NULL, NULL,
               status, scheme_id, node_code,
               node_name, node_level, sort_order,
               NULL, NULL, NULL
        FROM prcp_coa_node
        WHERE is_deleted = 0 AND status = 'ACTIVE'
        UNION ALL
        SELECT 'operators', NULL, NULL, NULL,
               status, NULL, NULL,
               NULL, NULL, sort_order,
               dict_key, dict_label, NULL
        FROM sys_dict
        WHERE is_deleted = 0 AND status = 'ACTIVE' AND dict_type = 'LCR_OPERATOR'
        UNION ALL
        SELECT 'data_dates', NULL, NULL, NULL,
               NULL, NULL, NULL,
               NULL, NULL, NULL,
               NULL, NULL, data_date
        FROM (
            SELECT DISTINCT data_date
            FROM prcp_lcr_param
            WHERE is_deleted = 0
            ORDER BY data_date DESC
            LIMIT 60
        ) t
    """)
    List<Map<String, Object>> listOptionsRaw();

    /**
     * 取节点名称（用于创建时自动补 node_name）
     */
    @Select("""
        SELECT node_name FROM prcp_coa_node
        WHERE id = #{nodeId} AND is_deleted = 0
        LIMIT 1
    """)
    String nodeNameById(@Param("nodeId") Long nodeId);
}
