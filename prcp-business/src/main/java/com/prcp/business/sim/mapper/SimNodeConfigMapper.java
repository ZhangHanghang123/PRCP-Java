package com.prcp.business.sim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.sim.entity.SimNodeConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * <p>Mapper: prcp_sim_node_config 表的 SQL 访问层 (新业务模拟节点配置)</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>findBySchemeAndNode - 按 (scheme_id, coa_node_id) 查 active 配置</li>
 *   <li>findIncludingDeleted - 按 (scheme_id, coa_node_id) 查含已软删 (用于复活复用)</li>
 *   <li>softDeleteById / softDeleteByScheme - 软删单条 / 按方案级联</li>
 *   <li>listByScheme - 列出某方案下所有 active 配置 (LEFT JOIN coa_node)</li>
 * </ul>
 * </p>
 *
 * <p>配套表: {@link SimTermRatioMapper} (期限/利率分配比例)。</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface SimNodeConfigMapper extends BaseMapper<SimNodeConfig> {

    /**
     * <p>按 (scheme_id, coa_node_id) 查 active 配置</p>
     *
     * @param schemeId 方案 ID
     * @param coaNodeId 节点 ID
     * @return 配置 Map (annual_growth_rate/term_unit/term_count/remark), 不存在返回 null
     */
    @Select({
        "SELECT id, scheme_id AS schemeId, coa_node_id AS coaNodeId, coa_node_code AS coaNodeCode,",
        "       annual_growth_rate AS annualGrowthRate, term_unit AS termUnit,",
        "       term_count AS termCount, remark",
        "  FROM prcp_sim_node_config",
        " WHERE scheme_id = #{schemeId} AND coa_node_id = #{coaNodeId} AND is_deleted = 0",
        " LIMIT 1"
    })
    Map<String, Object> findBySchemeAndNode(@Param("schemeId") Long schemeId,
                                            @Param("coaNodeId") Long coaNodeId);

    /**
     * <p>按 (scheme_id, coa_node_id) 查含已软删 (用于复活复用)</p>
     *
     * @param schemeId 方案 ID
     * @param coaNodeId 节点 ID
     * @return [{id, is_deleted}, ...] 含 is_deleted 标记
     */
    @Select("SELECT id, is_deleted FROM prcp_sim_node_config WHERE scheme_id = #{schemeId} AND coa_node_id = #{coaNodeId}")
    Map<String, Object> findIncludingDeleted(@Param("schemeId") Long schemeId,
                                             @Param("coaNodeId") Long coaNodeId);

    /**
     * <p>软删单条配置</p>
     *
     * @param id  配置 ID
     * @param uid 操作人 ID
     * @return 受影响行数
     */
    @Update("UPDATE prcp_sim_node_config SET is_deleted = 1, updated_by = #{uid}, updated_at = NOW() WHERE id = #{id} AND is_deleted = 0")
    int softDeleteById(@Param("id") Long id, @Param("uid") Long uid);

    /**
     * <p>按方案软删所有配置 (级联, 调用方需触发 term_ratio 同名方法)</p>
     *
     * @param schemeId 方案 ID
     * @param uid      操作人 ID
     * @return 受影响行数
     */
    @Update("UPDATE prcp_sim_node_config SET is_deleted = 1, updated_by = #{uid}, updated_at = NOW() WHERE scheme_id = #{schemeId} AND is_deleted = 0")
    int softDeleteByScheme(@Param("schemeId") Long schemeId, @Param("uid") Long uid);

    /**
     * <p>列出某方案下所有 active 配置 (LEFT JOIN prcp_coa_node 取 node_code/name, 对齐 Python JOIN)</p>
     *
     * @param schemeId 方案 ID
     * @return 配置 Map 列表, 按 ID 升序
     */
    @Select({
        "SELECT c.id, c.scheme_id AS schemeId, c.coa_node_id AS coaNodeId,",
        "       n.node_code AS coaNodeCode, n.node_name AS coaNodeName,",
        "       c.annual_growth_rate AS annualGrowthRate, c.term_unit AS termUnit,",
        "       c.term_count AS termCount, c.remark",
        "  FROM prcp_sim_node_config c",
        "  LEFT JOIN prcp_coa_node n ON n.id = c.coa_node_id",
        " WHERE c.scheme_id = #{schemeId} AND c.is_deleted = 0",
        " ORDER BY c.id ASC"
    })
    List<Map<String, Object>> listByScheme(@Param("schemeId") Long schemeId);
}