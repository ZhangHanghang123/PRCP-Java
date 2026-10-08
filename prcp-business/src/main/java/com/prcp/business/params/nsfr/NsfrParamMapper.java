package com.prcp.business.params.nsfr;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * NSFR 参数补录 Mapper
 * <p>对齐 Python routers/nsfr_param.py</p>
 */
@Mapper
public interface NsfrParamMapper extends BaseMapper<NsfrParamEntity> {

    /**
     * 列表查询（默认排除已软删）
     *
     * @param schemeId 方案ID（可选）
     * @param nodeId   节点ID（可选）
     * @param dataDate 数据日期 yyyy-MM-dd（可选）
     * @param keyword  关键词（节点编码 / 节点名称 / 规则说明模糊匹配）
     */
    @Select("""
        SELECT
          p.id,
          p.scheme_id AS schemeId,
          p.scheme_code AS schemeCode,
          p.node_id AS nodeId,
          p.node_code AS nodeCode,
          p.node_name AS nodeName,
          p.data_date AS dataDate,
          p.is_asf AS isAsf,
          p.asf_factor AS asfFactor,
          p.asf_operator AS asfOperator,
          p.is_rsf AS isRsf,
          p.rsf_factor AS rsfFactor,
          p.rsf_operator AS rsfOperator,
          p.rule_note AS ruleNote,
          p.status,
          p.created_at AS createdAt,
          p.updated_at AS updatedAt,
          s.scheme_name AS schemeName
        FROM prcp_nsfr_param p
        LEFT JOIN prcp_coa_scheme s ON s.id = p.scheme_id AND s.is_deleted = 0
        WHERE p.is_deleted = 0
          AND (#{schemeId} IS NULL OR p.scheme_id = #{schemeId})
          AND (#{nodeId} IS NULL OR p.node_id = #{nodeId})
          AND (#{dataDate} IS NULL OR p.data_date = #{dataDate})
          AND (#{keyword} IS NULL OR #{keyword} = ''
               OR p.node_code LIKE CONCAT('%', #{keyword}, '%')
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
     * 下拉选项：
     * <ul>
     *   <li>schemes — 账户册方案（ACTIVE）</li>
     *   <li>nodes — 全部 ACTIVE 节点（前端按 schemeId 过滤）</li>
     *   <li>operators — NSFR_OPERATOR 字典（+/-）</li>
     *   <li>data_dates — 已存在的补录日期（最多 60 条，DESC）</li>
     * </ul>
     */
    @Select("""
        SELECT id, scheme_code AS schemeCode, scheme_name AS schemeName, status
        FROM prcp_coa_scheme
        WHERE is_deleted = 0 AND status = 'ACTIVE'
        ORDER BY scheme_code
    """)
    List<Map<String, Object>> listSchemes();

    @Select("""
        SELECT id, scheme_id AS scheme_id, node_code AS nodeCode, node_name AS nodeName,
               node_level AS nodeLevel, status
        FROM prcp_coa_node
        WHERE is_deleted = 0 AND status = 'ACTIVE'
        ORDER BY scheme_id, path, sort_order
    """)
    List<Map<String, Object>> listNodes();

    @Select("""
        SELECT dict_key AS dictKey, dict_label AS dictLabel, sort_order AS sortOrder
        FROM sys_dict
        WHERE is_deleted = 0 AND status = 'ACTIVE' AND dict_type = 'NSFR_OPERATOR'
        ORDER BY sort_order, dict_key
    """)
    List<Map<String, Object>> listOperators();

    @Select("""
        SELECT DISTINCT data_date AS dataDate
        FROM prcp_nsfr_param
        WHERE is_deleted = 0
        ORDER BY data_date DESC
        LIMIT 60
    """)
    List<Map<String, Object>> listDataDates();

    /**
     * 按 node_id 查节点名称（新增时自动补全 node_name）
     */
    @Select("""
        SELECT node_name AS nodeName FROM prcp_coa_node
        WHERE id = #{nodeId} AND is_deleted = 0 LIMIT 1
    """)
    String selectNodeName(@Param("nodeId") Long nodeId);
}