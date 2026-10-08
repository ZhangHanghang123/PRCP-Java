package com.prcp.business.params.eve;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface EveParamMapper extends BaseMapper<EveParamEntity> {

    /**
     * 列表查询：默认排除软删，支持 schemeId / nodeId / dataDate / keyword 过滤
     * JOIN prcp_coa_scheme 取方案名
     * ORDER BY data_date DESC, scheme_code, node_code
     */
    @Select("""
        SELECT
          p.id,
          p.scheme_id   AS schemeId,
          p.scheme_code AS schemeCode,
          p.node_id     AS nodeId,
          p.node_code   AS nodeCode,
          p.node_name   AS nodeName,
          p.data_date   AS dataDate,
          p.is_asset         AS isAsset,
          p.asset_type       AS assetType,
          p.asset_category   AS assetCategory,
          p.asset_operator   AS assetOperator,
          p.is_liability     AS isLiability,
          p.liability_type   AS liabilityType,
          p.liability_category AS liabilityCategory,
          p.liability_operator AS liabilityOperator,
          p.duration,
          p.current_balance AS currentBalance,
          p.rule_note       AS ruleNote,
          p.status,
          p.created_at AS createdAt,
          p.updated_at AS updatedAt,
          s.scheme_name AS schemeName
        FROM prcp_eve_param p
        LEFT JOIN prcp_coa_scheme s ON s.id = p.scheme_id AND s.is_deleted = 0
        WHERE p.is_deleted = 0
          AND (#{schemeId} IS NULL OR p.scheme_id = #{schemeId})
          AND (#{nodeId}   IS NULL OR p.node_id   = #{nodeId})
          AND (#{dataDate} IS NULL OR p.data_date = #{dataDate})
          AND (#{keyword}  IS NULL OR #{keyword}  = ''
               OR p.node_code LIKE CONCAT('%', #{keyword}, '%')
               OR p.node_name LIKE CONCAT('%', #{keyword}, '%')
               OR p.rule_note LIKE CONCAT('%', #{keyword}, '%'))
        ORDER BY p.data_date DESC, p.scheme_code, p.node_code
        LIMIT 5000
    """)
    List<Map<String, Object>> query(@Param("schemeId") Long schemeId,
                                    @Param("nodeId")   Long nodeId,
                                    @Param("dataDate") String dataDate,
                                    @Param("keyword")  String keyword);

    /**
     * 下拉选项：账户册方案 + 节点 + EVE_OPERATOR 字典 + 已用数据日期
     */
    @Select("""
        SELECT id, scheme_code AS schemeCode, scheme_name AS schemeName, status
        FROM prcp_coa_scheme
        WHERE is_deleted = 0 AND status = 'ACTIVE'
        ORDER BY scheme_code
    """)
    List<Map<String, Object>> listSchemes();

    @Select("""
        SELECT id, scheme_id AS schemeId, node_code AS nodeCode, node_name AS nodeName,
               node_level AS nodeLevel, status
        FROM prcp_coa_node
        WHERE is_deleted = 0 AND status = 'ACTIVE'
        ORDER BY scheme_id, path, sort_order
    """)
    List<Map<String, Object>> listNodes();

    @Select("""
        SELECT dict_key AS dictKey, dict_label AS dictLabel, sort_order AS sortOrder
        FROM sys_dict
        WHERE is_deleted = 0 AND status = 'ACTIVE' AND dict_type = 'EVE_OPERATOR'
        ORDER BY sort_order, dict_key
    """)
    List<Map<String, Object>> listOperators();

    @Select("""
        SELECT DISTINCT data_date AS dataDate
        FROM prcp_eve_param
        WHERE is_deleted = 0
        ORDER BY data_date DESC
        LIMIT 60
    """)
    List<Map<String, Object>> listDataDates();
}