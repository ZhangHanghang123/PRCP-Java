package com.prcp.business.params.roe;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * <p>Mapper: prcp_roe_param 表的 SQL 访问层</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>query(...) - 列表查询 (带筛选), 对齐 Python list_roe_params()</li>
 *   <li>listSchemes/listNodes/listOperators/listDataDates - 下拉选项, 对齐 Python list_options()</li>
 *   <li>selectNodeName - 自动补充 node_name</li>
 *   <li>softDeleteById - 软删</li>
 * </ul>
 * </p>
 *
 * <p>字段命名: net_profit_* (净利润 · 分子) + net_asset_* (净资产 · 分母)。</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface RoeParamMapper extends BaseMapper<RoeParamEntity> {

    /**
     * <p>列表查询 (带筛选)</p>
     *
     * @param schemeId 方案 ID (可选)
     * @param nodeId   节点 ID (可选)
     * @param dataDate 数据日期 yyyy-MM-dd (可选)
     * @param keyword  模糊搜索关键字 (节点编码/名称/规则说明)
     * @return 行 Map 列表, 按 data_date DESC 排序, 最多 5000 条
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
     * <p>下拉选项: 账户册方案 (ACTIVE)</p>
     *
     * @return 方案列表, 按 scheme_code 升序
     */
    @Select("SELECT id, scheme_code AS schemeCode, scheme_name AS schemeName, status FROM prcp_coa_scheme WHERE is_deleted = 0 AND status = 'ACTIVE' ORDER BY scheme_code")
    List<Map<String, Object>> listSchemes();

    /**
     * <p>下拉选项: 全部 ACTIVE 节点 (额外 label = node_code|node_name 便于展示)</p>
     *
     * @return 节点列表, 按 scheme_id, path, sort_order 排序
     */
    @Select("SELECT id, scheme_id AS schemeId, node_code AS nodeCode, node_name AS nodeName, node_level AS nodeLevel, status, CONCAT(node_code, '|', node_name) AS label FROM prcp_coa_node WHERE is_deleted = 0 AND status = 'ACTIVE' ORDER BY scheme_id, path, sort_order")
    List<Map<String, Object>> listNodes();

    /**
     * <p>下拉选项: ROE_OPERATOR 字典</p>
     *
     * @return 字典项, 含 dictKey/dictLabel/sortOrder
     */
    @Select("SELECT dict_key AS dictKey, dict_label AS dictLabel, sort_order AS sortOrder FROM sys_dict WHERE is_deleted = 0 AND status = 'ACTIVE' AND dict_type = 'ROE_OPERATOR' ORDER BY sort_order, dict_key")
    List<Map<String, Object>> listOperators();

    /**
     * <p>下拉选项: 已存在补录日期 (近 60 条, DESC)</p>
     *
     * @return data_date 列表
     */
    @Select("SELECT DISTINCT DATE_FORMAT(data_date, '%Y-%m-%d') AS dataDate FROM prcp_roe_param WHERE is_deleted = 0 ORDER BY data_date DESC LIMIT 60")
    List<Map<String, Object>> listDataDates();

    /**
     * <p>自动补充 node_name (前端未传时)</p>
     *
     * @param nodeId 节点 ID
     * @return node_name 字符串, 不存在返回 null
     */
    @Select("SELECT node_name FROM prcp_coa_node WHERE id = #{nodeId} AND is_deleted = 0 LIMIT 1")
    String selectNodeName(@Param("nodeId") Long nodeId);

    /**
     * <p>软删 ROE 参数 (置 is_deleted=1)</p>
     *
     * @param id  参数主键 (复合字符串 ID)
     * @param uid 操作人 ID
     * @return 受影响行数 (0 表示已被删过, 1 表示删除成功)
     */
    @Update("UPDATE prcp_roe_param SET is_deleted = 1, updated_by = #{uid} WHERE id = #{id} AND is_deleted = 0")
    int softDeleteById(@Param("id") String id, @Param("uid") Long uid);
}