package com.prcp.business.params.nim;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface NimParamMapper extends BaseMapper<NimParamEntity> {

    /**
     * 列表查询（JOIN prcp_coa_scheme 取 scheme_name），按 data_date DESC + scheme_code + node_code 排序
     * 默认排除已软删（p.is_deleted=0）
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
     * 下拉选项：账户册方案 + 该方案下的节点 + NIM_OPERATOR 字典 + 历史数据日期
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
