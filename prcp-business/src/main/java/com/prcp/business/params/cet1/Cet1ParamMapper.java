package com.prcp.business.params.cet1;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * <p>Mapper: prcp_cet1_param 表的 SQL 访问层</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>query(...) - 列表查询 (带筛选), JOIN 账户册方案补 schemeName</li>
 *   <li>listSchemes/listNodes/listOperators/listDataDates - 下拉选项</li>
 *   <li>getNodeNameById - 新增时按 node_id 自动补 node_name</li>
 * </ul>
 * </p>
 *
 * <p>命名遵循驼峰别名, 前端可直接用 schemeId / nodeCode 等字段名取值。</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface Cet1ParamMapper extends BaseMapper<Cet1ParamEntity> {

    /**
     * <p>列表查询 (JOIN prcp_coa_scheme 取 scheme_name)</p>
     *
     * @param schemeId 方案 ID (可选, null 不过滤)
     * @param nodeId   节点 ID (可选)
     * @param dataDate 数据日期 yyyy-MM-dd (可选)
     * @param keyword  模糊搜索关键字 (节点编码/名称/规则说明)
     * @return 行 Map 列表 (key 为 camelCase), 已过滤 is_deleted=0, 按 data_date DESC 排序
     */
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
     * <p>下拉选项: 账户册节点 (ACTIVE)</p>
     *
     * @return 节点列表, 按 scheme_id, path, sort_order 排序
     */
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

    /**
     * <p>下拉选项: CET1_OPERATOR 字典 (分子运算符)</p>
     *
     * @return 字典项, 含 dictKey/dictLabel/sortOrder
     */
    @Select("""
        SELECT dict_key   AS dictKey,
               dict_label AS dictLabel,
               sort_order AS sortOrder
        FROM sys_dict
        WHERE is_deleted = 0 AND status = 'ACTIVE' AND dict_type = 'CET1_OPERATOR'
        ORDER BY sort_order, dict_key
    """)
    List<Map<String, Object>> listOperators();

    /**
     * <p>下拉选项: 已存在的补录数据日期 (近 60 条)</p>
     *
     * @return data_date 列表, 按日期 DESC
     */
    @Select("""
        SELECT DISTINCT data_date AS dataDate
        FROM prcp_cet1_param
        WHERE is_deleted = 0
        ORDER BY data_date DESC
        LIMIT 60
    """)
    List<Map<String, Object>> listDataDates();

    /**
     * <p>按 node_id 自动补 node_name (前端新增时未传时使用)</p>
     *
     * @param nodeId 节点 ID
     * @return node_name 字符串, 不存在返回 null
     */
    @Select("""
        SELECT node_name FROM prcp_coa_node
        WHERE id = #{nodeId} AND is_deleted = 0 LIMIT 1
    """)
    String getNodeNameById(@Param("nodeId") Long nodeId);
}