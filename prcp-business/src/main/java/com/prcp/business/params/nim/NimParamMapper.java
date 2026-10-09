package com.prcp.business.params.nim;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * <p>Mapper: prcp_nim_param 表的 SQL 访问层</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>query(...) - 列表查询 (带筛选), JOIN prcp_coa_scheme 取 scheme_name</li>
 *   <li>listOptionsRaw - 下拉选项 (UNION ALL: scheme/node/operator/date)</li>
 * </ul>
 * </p>
 *
 * <p>字段特点: isInterestAsset + assetRate + assetOperator + assetCategory, isInterestLiability 对称结构。</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface NimParamMapper extends BaseMapper<NimParamEntity> {

    /**
     * <p>列表查询 (JOIN prcp_coa_scheme 取 scheme_name), 按 data_date DESC + scheme_code + node_code 排序</p>
     * <p>默认排除已软删 (p.is_deleted=0)</p>
     *
     * @param schemeId 方案 ID (可选, 类型 Integer)
     * @param nodeId   节点 ID (可选, 类型 Integer)
     * @param dataDate 数据日期 yyyy-MM-dd (可选)
     * @param keyword  模糊搜索关键字 (节点编码/名称/规则说明)
     * @return 行 Map 列表, 最多 5000 条
     */
    @Select({
        "<script>",
        "SELECT p.id, p.scheme_id AS schemeId, p.scheme_code AS schemeCode,",
        "       p.node_id   AS nodeId,   p.node_code   AS nodeCode, p.node_name AS nodeName,",
        "       p.data_date AS dataDate,",
        "       p.is_interest_asset AS isInterestAsset,",
        "       p.asset_rate  AS assetRate,  p.asset_operator  AS assetOperator,",
        "       p.is_interest_liability AS isInterestLiability,",
        "       p.liability_rate AS liabilityRate, p.liability_operator AS liabilityOperator,",
        "       p.current_balance AS currentBalance, p.rule_note AS ruleNote, p.status,",
        "       p.created_at AS createdAt, p.updated_at AS updatedAt,",
        "       s.scheme_name AS schemeName",
        "  FROM prcp_nim_param p",
        "  LEFT JOIN prcp_coa_scheme s ON s.id = p.scheme_id",
        " WHERE p.is_deleted = 0",
        "   <if test='schemeId != null'> AND p.scheme_id = #{schemeId} </if>",
        "   <if test='nodeId != null'>   AND p.node_id   = #{nodeId} </if>",
        "   <if test='dataDate != null and dataDate != \"\"'> AND p.data_date = #{dataDate} </if>",
        "   <if test='keyword != null and keyword != \"\"'>",
        "     AND (p.node_code LIKE CONCAT('%', #{keyword}, '%')",
        "       OR p.node_name LIKE CONCAT('%', #{keyword}, '%')",
        "       OR p.rule_note LIKE CONCAT('%', #{keyword}, '%'))",
        "   </if>",
        " ORDER BY p.data_date DESC, p.scheme_code, p.node_code",
        " LIMIT 5000",
        "</script>"
    })
    List<Map<String, Object>> query(@Param("schemeId") Integer schemeId,
                                    @Param("nodeId") Integer nodeId,
                                    @Param("dataDate") String dataDate,
                                    @Param("keyword") String keyword);

    /**
     * <p>下拉选项: UNION ALL 一把取齐 4 类</p>
     * <ul>
     *   <li>_type='scheme' - 账户册方案 ACTIVE</li>
     *   <li>_type='node' - 全部 ACTIVE 节点</li>
     *   <li>_type='operator' - NIM_OPERATOR 字典</li>
     *   <li>_type='date' - 已存在补录日期 (近 60 条)</li>
     * </ul>
     *
     * @return 统一字段格式的合并结果, 用 _type 区分类型
     */
    @Select({
        "<script>",
        "SELECT 'scheme' AS _type, id, scheme_code AS code, scheme_name AS name, NULL AS scheme_id,",
        "       NULL AS node_code, NULL AS node_name, NULL AS dict_key, NULL AS dict_label, NULL AS sort_order, NULL AS data_date",
        "  FROM prcp_coa_scheme",
        " WHERE is_deleted = 0 AND status = 'ACTIVE'",
        " UNION ALL",
        "SELECT 'node' AS _type, id, NULL AS code, NULL AS name, scheme_id,",
        "       node_code, node_name, NULL AS dict_key, NULL AS dict_label, NULL AS sort_order, NULL AS data_date",
        "  FROM prcp_coa_node",
        " WHERE is_deleted = 0 AND status = 'ACTIVE'",
        " UNION ALL",
        "SELECT 'operator' AS _type, NULL AS id, NULL AS code, NULL AS name, NULL AS scheme_id,",
        "       NULL AS node_code, NULL AS node_name,",
        "       dict_key, dict_label, sort_order, NULL AS data_date",
        "  FROM sys_dict",
        " WHERE is_deleted = 0 AND status = 'ACTIVE' AND dict_type = 'NIM_OPERATOR'",
        " UNION ALL",
        "SELECT 'date' AS _type, NULL AS id, NULL AS code, NULL AS name, NULL AS scheme_id,",
        "       NULL AS node_code, NULL AS node_name,",
        "       NULL AS dict_key, NULL AS dict_label, NULL AS sort_order, data_date",
        "  FROM (SELECT DISTINCT data_date FROM prcp_nim_param WHERE is_deleted = 0",
        "         ORDER BY data_date DESC LIMIT 60) t",
        "</script>"
    })
    List<Map<String, Object>> listOptionsRaw();
}
