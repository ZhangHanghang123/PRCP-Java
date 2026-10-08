package com.prcp.business.params.roe;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * ROE 参数 Mapper
 * <p>对齐 Python routers/roe_param.py 的 query() / list_options()</p>
 *
 * @author PRCP WorkBuddy Agent
 * @date 2026-10-08
 */
@Mapper
public interface RoeParamMapper extends BaseMapper<RoeParamEntity> {

    /**
     * 列表查询（带筛选）
     * 对齐 Python list_roe_params()
     */
    @Select({
        "<script>",
        "SELECT p.id, p.scheme_id AS schemeId, p.scheme_code AS schemeCode,",
        "       s.scheme_name AS schemeName,",
        "       p.node_id AS nodeId, p.node_code AS nodeCode, p.node_name AS nodeName,",
        "       DATE_FORMAT(p.data_date, '%Y-%m-%d') AS dataDate,",
        "       p.is_net_profit AS isNetProfit, p.net_profit_factor AS netProfitFactor, p.net_profit_symbol AS netProfitSymbol,",
        "       p.is_net_asset AS isNetAsset, p.net_asset_factor AS netAssetFactor, p.net_asset_symbol AS netAssetSymbol,",
        "       p.current_balance AS currentBalance, p.rule_note AS ruleNote, p.status,",
        "       DATE_FORMAT(p.created_at, '%Y-%m-%d %H:%i:%s') AS createdAt,",
        "       DATE_FORMAT(p.updated_at, '%Y-%m-%d %H:%i:%s') AS updatedAt",
        "  FROM prcp_roe_param p LEFT JOIN prcp_coa_scheme s ON s.id = p.scheme_id AND s.is_deleted = 0",
        " WHERE p.is_deleted = 0",
        "   <if test='schemeId != null'> AND p.scheme_id = #{schemeId} </if>",
        "   <if test='nodeId != null'> AND p.node_id = #{nodeId} </if>",
        "   <if test='dataDate != null and dataDate != \"\"'> AND p.data_date = #{dataDate} </if>",
        "   <if test='keyword != null and keyword != \"\"'> AND (p.node_code LIKE CONCAT('%', #{keyword}, '%') OR p.node_name LIKE CONCAT('%', #{keyword}, '%') OR p.rule_note LIKE CONCAT('%', #{keyword}, '%')) </if>",
        " ORDER BY p.data_date DESC, p.scheme_code, p.node_code",
        " LIMIT 5000",
        "</script>"
    })
    List<Map<String, Object>> query(@Param("schemeId") Long schemeId,
                                     @Param("nodeId") Long nodeId,
                                     @Param("dataDate") String dataDate,
                                     @Param("keyword") String keyword);

    /**
     * 下拉选项：方案 + 节点 + ROE_OPERATOR 字典 + 可用数据日期
     * 对齐 Python list_options()
     */
    @Select("SELECT id, scheme_code AS schemeCode, scheme_name AS schemeName, status FROM prcp_coa_scheme WHERE is_deleted = 0 AND status = 'ACTIVE' ORDER BY scheme_code")
    List<Map<String, Object>> listSchemes();

    @Select("SELECT id, scheme_id AS schemeId, node_code AS nodeCode, node_name AS nodeName, node_level AS nodeLevel, status, CONCAT(node_code, '|', node_name) AS label FROM prcp_coa_node WHERE is_deleted = 0 AND status = 'ACTIVE' ORDER BY scheme_id, path, sort_order")
    List<Map<String, Object>> listNodes();

    @Select("SELECT dict_key AS dictKey, dict_label AS dictLabel, sort_order AS sortOrder FROM sys_dict WHERE is_deleted = 0 AND status = 'ACTIVE' AND dict_type = 'ROE_OPERATOR' ORDER BY sort_order, dict_key")
    List<Map<String, Object>> listOperators();

    @Select("SELECT DISTINCT DATE_FORMAT(data_date, '%Y-%m-%d') AS dataDate FROM prcp_roe_param WHERE is_deleted = 0 ORDER BY data_date DESC LIMIT 60")
    List<Map<String, Object>> listDataDates();

    /**
     * 自动补充 node_name（前端未传时）
     */
    @Select("SELECT node_name FROM prcp_coa_node WHERE id = #{nodeId} AND is_deleted = 0 LIMIT 1")
    String selectNodeName(@Param("nodeId") Long nodeId);

    /**
     * 软删
     */
    @Update("UPDATE prcp_roe_param SET is_deleted = 1, updated_by = #{uid} WHERE id = #{id} AND is_deleted = 0")
    int softDeleteById(@Param("id") String id, @Param("uid") Long uid);
}