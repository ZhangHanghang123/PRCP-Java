package com.prcp.business.sim.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Map;

/**
 * <p>Mapper: prcp_sim_growth_config 表 (新业务增长配置: 增长方式 + 5 年参数)</p>
 *
 * <p>对应 Python {@code routers/sim.py} 的 3 个增长端点:
 * <ul>
 *   <li>GET    /sim/growth/{cfgId} → findByConfigId</li>
 *   <li>PUT    /sim/growth/{cfgId} → upsert</li>
 *   <li>DELETE /sim/growth/{cfgId} → softDelete</li>
 * </ul>
 * </p>
 *
 * <p>growth_type 语义:
 * <ol>
 *   <li>1 目标增长百分比 (单位 %)</li>
 *   <li>2 目标增长绝对值 (单位 金额: 万元/亿元)</li>
 *   <li>3 增长百分比 (单位 %)</li>
 *   <li>4 增长值 (单位 金额: 万元/亿元)</li>
 * </ol>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-27
 */
@Mapper
public interface SimGrowthConfigMapper {

    /**
     * <p>按 config_id 查增长配置</p>
     *
     * @param configId 节点配置 ID
     * @return 增长配置 Map, 不存在返回 null
     */
    @Select({
        "SELECT id, config_id AS configId,",
        // ⚠️ tinyint(1) 会被 Connector/J 映射成 Boolean, 用 CAST 强制转 int
        "       CAST(growth_type AS SIGNED) AS growthType,",
        "       year1_value AS year1Value, year2_value AS year2Value,",
        "       year3_value AS year3Value, year4_value AS year4Value,",
        "       year5_value AS year5Value, unit, amount_unit AS amountUnit, remark",
        "  FROM prcp_sim_growth_config",
        " WHERE config_id = #{configId} AND is_deleted = 0",
        " LIMIT 1"
    })
    Map<String, Object> findByConfigId(@Param("configId") Long configId);

    /**
     * <p>按 config_id 查含已软删 (用于复活复用)</p>
     *
     * @param configId 节点配置 ID
     * @return {id, is_deleted} 或 null
     */
    @Select("SELECT id, is_deleted FROM prcp_sim_growth_config WHERE config_id = #{configId}")
    Map<String, Object> findIncludingDeleted(@Param("configId") Long configId);

    /**
     * <p>新增增长配置</p>
     *
     * @param configId   节点配置 ID
     * @param schemeId   方案 ID
     * @param schemeCode 方案编码
     * @param schemeName 方案名称
     * @param coaNodeId  节点 ID
     * @param coaNodeCode 节点编码
     * @param coaNodeName 节点名称
     * @param growthType 增长方式 1..4
     * @param y1 第 1 年参数
     * @param y2 第 2 年参数
     * @param y3 第 3 年参数
     * @param y4 第 4 年参数
     * @param y5 第 5 年参数
     * @param unit       单位 (%, 或金额单位)
     * @param amountUnit 金额单位 (WAN/YI), growth_type=2/4 生效
     * @param remark     备注
     * @param uid        操作人
     * @return 受影响行数
     */
    @Insert({
        "INSERT INTO prcp_sim_growth_config",
        " (config_id, scheme_id, scheme_code, scheme_name, coa_node_id, coa_node_code, coa_node_name,",
        "  growth_type, year1_value, year2_value, year3_value, year4_value, year5_value,",
        "  unit, amount_unit, remark, is_deleted, created_by, updated_by)",
        " VALUES (#{configId}, #{schemeId}, #{schemeCode}, #{schemeName}, #{coaNodeId}, #{coaNodeCode}, #{coaNodeName},",
        "         #{growthType}, #{y1}, #{y2}, #{y3}, #{y4}, #{y5},",
        "         #{unit}, #{amountUnit}, #{remark}, 0, #{uid}, #{uid})"
    })
    int insertOne(@Param("configId") Long configId, @Param("schemeId") Long schemeId,
                  @Param("schemeCode") String schemeCode, @Param("schemeName") String schemeName,
                  @Param("coaNodeId") Long coaNodeId, @Param("coaNodeCode") String coaNodeCode,
                  @Param("coaNodeName") String coaNodeName, @Param("growthType") int growthType,
                  @Param("y1") java.math.BigDecimal y1, @Param("y2") java.math.BigDecimal y2,
                  @Param("y3") java.math.BigDecimal y3, @Param("y4") java.math.BigDecimal y4,
                  @Param("y5") java.math.BigDecimal y5, @Param("unit") String unit,
                  @Param("amountUnit") String amountUnit, @Param("remark") String remark,
                  @Param("uid") Long uid);

    /**
     * <p>更新增长配置</p>
     *
     * @param id         记录 ID
     * @param growthType 增长方式
     * @param y1..y5     5 年参数
     * @param unit       单位
     * @param amountUnit 金额单位
     * @param remark     备注
     * @param uid        操作人
     * @return 受影响行数
     */
    @Update({
        "UPDATE prcp_sim_growth_config SET growth_type = #{growthType},",
        "       year1_value = #{y1}, year2_value = #{y2}, year3_value = #{y3},",
        "       year4_value = #{y4}, year5_value = #{y5},",
        "       unit = #{unit}, amount_unit = #{amountUnit}, remark = #{remark},",
        "       is_deleted = 0, updated_by = #{uid}, updated_at = NOW()",
        " WHERE id = #{id}"
    })
    int updateOne(@Param("id") Long id, @Param("growthType") int growthType,
                  @Param("y1") java.math.BigDecimal y1, @Param("y2") java.math.BigDecimal y2,
                  @Param("y3") java.math.BigDecimal y3, @Param("y4") java.math.BigDecimal y4,
                  @Param("y5") java.math.BigDecimal y5, @Param("unit") String unit,
                  @Param("amountUnit") String amountUnit, @Param("remark") String remark,
                  @Param("uid") Long uid);

    /**
     * <p>软删增长配置</p>
     *
     * @param configId 节点配置 ID
     * @param uid      操作人
     * @return 受影响行数
     */
    @Delete("UPDATE prcp_sim_growth_config SET is_deleted = 1, updated_by = #{uid}, updated_at = NOW() WHERE config_id = #{configId} AND is_deleted = 0")
    int softDeleteByConfigId(@Param("configId") Long configId, @Param("uid") Long uid);
}
