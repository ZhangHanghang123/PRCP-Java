package com.prcp.business.params.nsfr;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * <p>Mapper: prcp_nsfr_param 表的 SQL 访问层</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>query(...) - 列表查询 (JOIN prcp_coa_scheme 取 scheme_name)</li>
 *   <li>listSchemes/listNodes/listOperators/listDataDates - 下拉选项</li>
 *   <li>selectNodeName - 按 node_id 查节点名称 (新增自动补全)</li>
 * </ul>
 * </p>
 *
 * <p>字段特点: isAsf/asfFactor/asfOperator (可用稳定资金) + isRsf/rsfFactor/rsfOperator (所需稳定资金)。</p>
 *
 * <p>对齐 Python routers/nsfr_param.py。</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface NsfrParamMapper extends BaseMapper<NsfrParamEntity> {

    /**
     * <p>列表查询 (默认排除已软删)</p>
     *
     * @param schemeId 方案 ID (可选)
     * @param nodeId   节点 ID (可选)
     * @param dataDate 数据日期 yyyy-MM-dd (可选)
     * @param keyword  关键词 (节点编码/名称/规则说明模糊匹配)
     * @return 行 Map 列表, 按 data_date DESC 排序, 最多 5000 条
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
     * <p>下拉选项: 账户册方案 (ACTIVE)</p>
     *
     * @return 方案列表, 按 scheme_code 升序
     */
    @Select("""
        SELECT id, scheme_code AS schemeCode, scheme_name AS schemeName, status
        FROM prcp_coa_scheme
        WHERE is_deleted = 0 AND status = 'ACTIVE'
        ORDER BY scheme_code
    """)
    List<Map<String, Object>> listSchemes();

    /**
     * <p>下拉选项: 全部 ACTIVE 节点 (前端按 schemeId 过滤)</p>
     *
     * @return 节点列表, 按 scheme_id, path, sort_order 排序
     */
    @Select("""
        SELECT id, scheme_id AS scheme_id, node_code AS nodeCode, node_name AS nodeName,
               node_level AS nodeLevel, status
        FROM prcp_coa_node
        WHERE is_deleted = 0 AND status = 'ACTIVE'
        ORDER BY scheme_id, path, sort_order
    """)
    List<Map<String, Object>> listNodes();

    /**
     * <p>下拉选项: NSFR_OPERATOR 字典 (+/-)</p>
     *
     * @return 字典项, 含 dictKey/dictLabel/sortOrder
     */
    @Select("""
        SELECT dict_key AS dictKey, dict_label AS dictLabel, sort_order AS sortOrder
        FROM sys_dict
        WHERE is_deleted = 0 AND status = 'ACTIVE' AND dict_type = 'NSFR_OPERATOR'
        ORDER BY sort_order, dict_key
    """)
    List<Map<String, Object>> listOperators();

    /**
     * <p>下拉选项: 已存在的补录数据日期 (最多 60 条, DESC)</p>
     *
     * @return data_date 列表
     */
    @Select("""
        SELECT DISTINCT data_date AS dataDate
        FROM prcp_nsfr_param
        WHERE is_deleted = 0
        ORDER BY data_date DESC
        LIMIT 60
    """)
    List<Map<String, Object>> listDataDates();

    /**
     * <p>按 node_id 查节点名称 (新增时自动补全 node_name)</p>
     *
     * @param nodeId 节点 ID
     * @return nodeName 字符串, 不存在返回 null
     */
    @Select("""
        SELECT node_name AS nodeName FROM prcp_coa_node
        WHERE id = #{nodeId} AND is_deleted = 0 LIMIT 1
    """)
    String selectNodeName(@Param("nodeId") Long nodeId);
}