package com.prcp.business.sim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.sim.entity.SimTermRatio;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface SimTermRatioMapper extends BaseMapper<SimTermRatio> {

    @Select({
        "SELECT id, term_value AS termValue, term_unit AS termUnit,",
        "       business_ratio AS businessRatio, interest_rate AS interestRate,",
        "       sort_order AS sortOrder, remark",
        "  FROM prcp_sim_term_ratio",
        " WHERE config_id = #{configId} AND is_deleted = 0",
        " ORDER BY sort_order, term_value"
    })
    List<Map<String, Object>> listByConfigId(@Param("configId") Long configId);

    @Update("UPDATE prcp_sim_term_ratio SET is_deleted = 1, updated_by = #{uid}, updated_at = NOW() WHERE config_id = #{configId} AND is_deleted = 0")
    int softDeleteByConfigId(@Param("configId") Long configId, @Param("uid") Long uid);

    @Update("UPDATE prcp_sim_term_ratio SET is_deleted = 1, updated_by = #{uid}, updated_at = NOW() WHERE id = #{id} AND is_deleted = 0")
    int softDeleteById(@Param("id") Long id, @Param("uid") Long uid);

    @Update("UPDATE prcp_sim_term_ratio t"
        + "   JOIN prcp_sim_node_config c ON c.id = t.config_id"
        + "   SET t.is_deleted = 1, t.updated_by = #{uid}, t.updated_at = NOW()"
        + " WHERE c.scheme_id = #{schemeId} AND t.is_deleted = 0")
    int softDeleteByScheme(@Param("schemeId") Long schemeId, @Param("uid") Long uid);
}