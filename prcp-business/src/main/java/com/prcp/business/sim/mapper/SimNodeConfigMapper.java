package com.prcp.business.sim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.sim.entity.SimNodeConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface SimNodeConfigMapper extends BaseMapper<SimNodeConfig> {

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

    @Select("SELECT id, is_deleted FROM prcp_sim_node_config WHERE scheme_id = #{schemeId} AND coa_node_id = #{coaNodeId}")
    Map<String, Object> findIncludingDeleted(@Param("schemeId") Long schemeId,
                                             @Param("coaNodeId") Long coaNodeId);

    @Update("UPDATE prcp_sim_node_config SET is_deleted = 1, updated_by = #{uid}, updated_at = NOW() WHERE id = #{id} AND is_deleted = 0")
    int softDeleteById(@Param("id") Long id, @Param("uid") Long uid);

    @Update("UPDATE prcp_sim_node_config SET is_deleted = 1, updated_by = #{uid}, updated_at = NOW() WHERE scheme_id = #{schemeId} AND is_deleted = 0")
    int softDeleteByScheme(@Param("schemeId") Long schemeId, @Param("uid") Long uid);

    /** 列出某方案下所有 active node_config（带 node_code/name，对齐 Python JOIN） */
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