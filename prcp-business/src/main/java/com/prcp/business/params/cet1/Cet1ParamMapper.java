package com.prcp.business.params.cet1;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * CET1 参数补录 Mapper
 *
 * - query(...)          ：列表查询（带筛选），JOIN 账户册方案补 schemeName
 * - listSchemes/listNodes/listOperators/listDataDates  ：下拉选项
 * - getNodeNameById     ：新增时按 node_id 自动补 node_name
 *
 * 命名遵循驼峰别名，前端可直接用 schemeId / nodeCode 等字段名取值。
 */
@Mapper
public interface Cet1ParamMapper extends BaseMapper<Cet1ParamEntity> {

    @Select("""
        SELECT
          p.id,
          p.scheme_id    AS schemeId,
          p.scheme_code  AS schemeCode,
          p.node_id      AS nodeId,
          p.node_code    AS nodeCode,
          p.node_name    AS nodeName,
          p.data_date    AS dataDate,
          p.is_numerator   AS isNumerator,
          p.numerator_factor   AS numeratorFactor,
          p.numerator_operator AS numeratorOperator,
          p.is_rwa          AS isRwa,
          p.rwa_weight      AS rwaWeight,
          p.rwa_operator    AS rwaOperator,
          p.current_balance AS currentBalance,
          p.rule_note   AS ruleNote,
          p.status,
          p.created_at  AS createdAt,
          p.updated_at  AS updatedAt,
          s.scheme_name AS schemeName
        FROM prcp_cet1_param p
        LEFT JOIN prcp_coa_scheme s ON s.id = p.scheme_id AND s.is_deleted = 0
        WHERE p.is_deleted = 0
          AND (#{schemeId} IS NULL OR p.scheme_id = #{schemeId})
          AND (#{nodeId}   IS NULL OR p.node_id   = #{nodeId})
          AND (#{dataDate} IS NULL OR p.data_date = #{dataDate})
          AND (#{keyword}  IS NULL OR p.node_code LIKE CONCAT('%', #{keyword}, '%')
                               OR p.node_name LIKE CONCAT('%', #{keyword}, '%')
                               OR p.rule_note LIKE CONCAT('%', #{keyword}, '%'))
        ORDER BY p.data_date DESC, p.scheme_code, p.node_code
    """)
    List<Map<String, Object>> query(@Param("schemeId") Long schemeId,
                                    @Param("nodeId")   Long nodeId,
                                    @Param("dataDate") String dataDate,
                                    @Param("keyword")  String keyword);

    @Select("""
        SELECT id, scheme_code AS schemeCode, scheme_name AS schemeName, status
        FROM prcp_coa_scheme
        WHERE is_deleted = 0 AND status = 'ACTIVE'
        ORDER BY scheme_code
    """)
    List<Map<String, Object>> listSchemes();

    @Select("""
        SELECT id,
               scheme_id  AS schemeId,
               node_code  AS nodeCode,
               node_name  AS nodeName,
               node_level AS nodeLevel,
               status
        FROM prcp_coa_node
        WHERE is_deleted = 0 AND status = 'ACTIVE'
        ORDER BY scheme_id, path, sort_order
    """)
    List<Map<String, Object>> listNodes();

    @Select("""
        SELECT dict_key   AS dictKey,
               dict_label AS dictLabel,
               sort_order AS sortOrder
        FROM sys_dict
        WHERE is_deleted = 0 AND status = 'ACTIVE' AND dict_type = 'CET1_OPERATOR'
        ORDER BY sort_order, dict_key
    """)
    List<Map<String, Object>> listOperators();

    @Select("""
        SELECT DISTINCT data_date AS dataDate
        FROM prcp_cet1_param
        WHERE is_deleted = 0
        ORDER BY data_date DESC
        LIMIT 60
    """)
    List<Map<String, Object>> listDataDates();

    /** 新增时按 node_id 自动补 node_name（前端未传时使用） */
    @Select("""
        SELECT node_name FROM prcp_coa_node
        WHERE id = #{nodeId} AND is_deleted = 0 LIMIT 1
    """)
    String getNodeNameById(@Param("nodeId") Long nodeId);
}