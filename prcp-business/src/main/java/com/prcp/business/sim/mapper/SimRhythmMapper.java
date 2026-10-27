package com.prcp.business.sim.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * <p>Mapper: prcp_sim_rhythm 表 (12 个月投放节奏权重)</p>
 *
 * <p>对应 Python {@code routers/sim.py} 的 3 个节奏端点:
 * <ul>
 *   <li>GET  /sim/rhythm/{cfgId}              → listByConfigId</li>
 *   <li>PUT  /sim/rhythm/{cfgId}              → softDeleteByConfigId + insertOne</li>
 *   <li>POST /sim/rhythm/{cfgId}/reset-linear → softDeleteByConfigId + 批量插入 8.3333</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-27
 */
@Mapper
public interface SimRhythmMapper {

    /**
     * <p>按 config_id 列出 12 个月权重 (按 month_no 升序)</p>
     *
     * @param configId 节点配置 ID
     * @return [{month_no, month_label, weight, remark}, ...]
     */
    @Select({
        "SELECT month_no AS monthNo, month_label AS monthLabel, weight, remark",
        "  FROM prcp_sim_rhythm",
        " WHERE config_id = #{configId} AND is_deleted = 0",
        " ORDER BY month_no ASC"
    })
    List<Map<String, Object>> listByConfigId(@Param("configId") Long configId);

    /**
     * <p>软删该 config 下所有权重 (整段替换前置)</p>
     *
     * @param configId 节点配置 ID
     * @return 受影响行数
     */
    @Delete("UPDATE prcp_sim_rhythm SET is_deleted = 1 WHERE config_id = #{configId} AND is_deleted = 0")
    int softDeleteByConfigId(@Param("configId") Long configId);

    /**
     * <p>按 (config_id, month_no) 查含已软删的记录 (唯一键 uk_config_month)</p>
     *
     * <p>⚠️ 唯一键是 {@code (config_id, month_no)}, <b>不含 is_deleted</b>,
     * 所以整段替换必须「软删复活优先」, 否则会报 Duplicate entry。</p>
     *
     * @param configId 节点配置 ID
     * @param monthNo  月份序号
     * @return {id, is_deleted} 或 null
     */
    @Select("SELECT id, is_deleted FROM prcp_sim_rhythm WHERE config_id = #{configId} AND month_no = #{monthNo} LIMIT 1")
    Map<String, Object> findByConfigAndMonth(@Param("configId") Long configId,
                                             @Param("monthNo") int monthNo);

    /**
     * <p>复活并更新单月权重 (is_deleted=0)</p>
     *
     * @param id         记录 ID
     * @param monthLabel 月份标签
     * @param weight    权重 (%)
     * @param remark    备注
     * @param uid       操作人
     * @return 受影响行数
     */
    @Update({
        "UPDATE prcp_sim_rhythm SET is_deleted = 0, month_label = #{monthLabel},",
        "       weight = #{weight}, sort_order = #{monthNo}, remark = #{remark},",
        "       updated_by = #{uid}, updated_at = NOW()",
        " WHERE id = #{id}"
    })
    int reviveById(@Param("id") Long id, @Param("monthNo") int monthNo,
                   @Param("monthLabel") String monthLabel,
                   @Param("weight") java.math.BigDecimal weight,
                   @Param("remark") String remark, @Param("uid") Long uid);

    /**
     * <p>插入单月权重</p>
     *
     * @param configId  节点配置 ID
     * @param schemeId  方案 ID
     * @param coaNodeId 节点 ID
     * @param monthNo   月份序号 1~12
     * @param monthLabel 月份标签
     * @param weight    权重 (%)
     * @param remark    备注
     * @param uid       操作人
     * @return 受影响行数
     */
    @Insert({
        "INSERT INTO prcp_sim_rhythm",
        " (config_id, scheme_id, coa_node_id, month_no, month_label, weight, sort_order, remark, is_deleted, created_by, updated_by)",
        " VALUES (#{configId}, #{schemeId}, #{coaNodeId}, #{monthNo}, #{monthLabel}, #{weight}, #{monthNo}, #{remark}, 0, #{uid}, #{uid})"
    })
    int insertOne(@Param("configId") Long configId, @Param("schemeId") Long schemeId,
                  @Param("coaNodeId") Long coaNodeId, @Param("monthNo") int monthNo,
                  @Param("monthLabel") String monthLabel, @Param("weight") java.math.BigDecimal weight,
                  @Param("remark") String remark, @Param("uid") Long uid);
}
