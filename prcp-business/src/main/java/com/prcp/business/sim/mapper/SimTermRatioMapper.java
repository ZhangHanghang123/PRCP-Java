package com.prcp.business.sim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.sim.entity.SimTermRatio;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * <p>Mapper: prcp_sim_term_ratio 表的 SQL 访问层 (期限/利率分配比例)</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>listByConfigId - 按 config_id 列出所有分配比例 (按 sort_order, term_value)</li>
 *   <li>softDeleteByConfigId - 按 config_id 软删 (删除配置时级联)</li>
 *   <li>softDeleteById - 按 ID 软删</li>
 *   <li>softDeleteByScheme - 按 scheme_id 软删 (JOIN node_config, 删除方案时级联)</li>
 * </ul>
 * </p>
 *
 * <p>业务: 每节点的资金按 (term_value, term_unit, business_ratio, interest_rate) 拆分到不同期限。</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface SimTermRatioMapper extends BaseMapper<SimTermRatio> {

    /**
     * <p>按 config_id 列出所有分配比例 (按 sort_order, term_value)</p>
     *
     * @param configId 节点配置 ID
     * @return 比例 Map 列表, 含 termValue/termUnit/businessRatio/interestRate
     */
    @Select({
        "SELECT id, term_value AS termValue, term_unit AS termUnit,",
        "       business_ratio AS businessRatio, interest_rate AS interestRate,",
        "       sort_order AS sortOrder, remark",
        "  FROM prcp_sim_term_ratio",
        " WHERE config_id = #{configId} AND is_deleted = 0",
        " ORDER BY sort_order, term_value"
    })
    List<Map<String, Object>> listByConfigId(@Param("configId") Long configId);

    /**
     * <p>按 config_id 软删所有比例 (删除配置时级联)</p>
     *
     * @param configId 节点配置 ID
     * @param uid      操作人 ID
     * @return 受影响行数
     */
    @Update("UPDATE prcp_sim_term_ratio SET is_deleted = 1, updated_by = #{uid}, updated_at = NOW() WHERE config_id = #{configId} AND is_deleted = 0")
    int softDeleteByConfigId(@Param("configId") Long configId, @Param("uid") Long uid);

    /**
     * <p>按 ID 软删单条比例</p>
     *
     * @param id  比例 ID
     * @param uid 操作人 ID
     * @return 受影响行数
     */
    @Update("UPDATE prcp_sim_term_ratio SET is_deleted = 1, updated_by = #{uid}, updated_at = NOW() WHERE id = #{id} AND is_deleted = 0")
    int softDeleteById(@Param("id") Long id, @Param("uid") Long uid);

    /**
     * <p>按 scheme_id 软删所有比例 (JOIN sim_node_config, 删除方案时级联)</p>
     *
     * @param schemeId 方案 ID
     * @param uid      操作人 ID
     * @return 受影响行数
     */
    @Update("UPDATE prcp_sim_term_ratio t"
        + "   JOIN prcp_sim_node_config c ON c.id = t.config_id"
        + "   SET t.is_deleted = 1, t.updated_by = #{uid}, t.updated_at = NOW()"
        + " WHERE c.scheme_id = #{schemeId} AND t.is_deleted = 0")
    int softDeleteByScheme(@Param("schemeId") Long schemeId, @Param("uid") Long uid);
}