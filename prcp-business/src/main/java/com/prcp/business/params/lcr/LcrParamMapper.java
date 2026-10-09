package com.prcp.business.params.lcr;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * <p>Mapper: prcp_lcr_param 表的 SQL 访问层</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>query(...) - 列表查询 (JOIN prcp_coa_scheme 取 scheme_name)</li>
 *   <li>listOptionsRaw - 下拉选项 (UNION ALL: schemes/nodes/operators/data_dates)</li>
 *   <li>nodeNameById - 按 node_id 查节点名称 (新增时自动补全)</li>
 * </ul>
 * </p>
 *
 * <p>对位 Python app/routers/lcr_param.py, 默认过滤 is_deleted=0。</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface LcrParamMapper extends BaseMapper<LcrParamEntity> {

    /**
     * <p>列表查询 (JOIN prcp_coa_scheme 取 scheme_name)</p>
     * <p>对应 Python GET /lcr-param/, 默认过滤 is_deleted=0</p>
     *
     * @param schemeId 方案 ID (可选)
     * @param nodeId   节点 ID (可选)
     * @param dataDate 数据日期 yyyy-MM-dd (可选)
     * @param keyword  模糊搜索关键字 (节点编码/名称/规则说明)
     * @return 行 Map 列表, 按 data_date DESC 排序, 最多 5000 条
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
     * <p>下拉选项: UNION ALL 一把取齐 4 类 (对应 Python GET /lcr-param/options)</p>
     * <ul>
     *   <li>bucket='schemes' - 账户册方案 ACTIVE</li>
     *   <li>bucket='nodes' - 全部 ACTIVE 节点</li>
     *   <li>bucket='operators' - LCR_OPERATOR 字典</li>
     *   <li>bucket='data_dates' - 已存在补录日期 (近 60 条)</li>
     * </ul>
     *
     * @return 统一字段格式的合并结果, 用 bucket 区分类型
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
     * <p>按 node_id 取节点名称 (创建时自动补 node_name)</p>
     *
     * @param nodeId 节点 ID
     * @return node_name 字符串, 不存在返回 null
     */
    @Select("""
        SELECT node_name FROM prcp_coa_node
        WHERE id = #{nodeId} AND is_deleted = 0
        LIMIT 1
    """)
    String nodeNameById(@Param("nodeId") Long nodeId);
}
