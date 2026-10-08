package com.prcp.business.sim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.sim.entity.SimScheme;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface SimSchemeMapper extends BaseMapper<SimScheme> {

    @Select({
        "SELECT s.id, s.scheme_code AS schemeCode, s.scheme_name AS schemeName,",
        "       s.coa_scheme_id AS coaSchemeId, s.data_date AS dataDate,",
        "       s.description, s.config_node_count AS configNodeCount,",
        "       s.status, s.created_at AS createdAt, s.updated_at AS updatedAt,",
        "       cs.scheme_code AS coaSchemeCode, cs.scheme_name AS coaSchemeName",
        "  FROM prcp_sim_scheme s",
        "  LEFT JOIN prcp_coa_scheme cs ON cs.id = s.coa_scheme_id",
        " WHERE s.is_deleted = 0",
        "   AND (#{keyword} IS NULL OR s.scheme_code LIKE #{kw} OR s.scheme_name LIKE #{kw})",
        "   AND (#{status} IS NULL OR s.status = #{status})",
        "   AND (#{coaSchemeId} IS NULL OR s.coa_scheme_id = #{coaSchemeId})",
        " ORDER BY s.id DESC"
    })
    List<Map<String, Object>> listSchemes(@Param("keyword") String keyword,
                                          @Param("kw") String kw,
                                          @Param("status") String status,
                                          @Param("coaSchemeId") Long coaSchemeId);

    @Select("SELECT data_date FROM prcp_sim_scheme WHERE id = #{id} AND is_deleted = 0")
    String selectDataDate(@Param("id") Long id);

    @Update("UPDATE prcp_sim_scheme SET config_node_count = ("
        + " SELECT COUNT(*) FROM prcp_sim_node_config c"
        + "  WHERE c.scheme_id = #{id} AND c.is_deleted = 0"
        + "), updated_by = #{uid}, updated_at = NOW()"
        + " WHERE id = #{id}")
    int refreshConfigNodeCount(@Param("id") Long id, @Param("uid") Long uid);

    @Update("UPDATE prcp_sim_scheme SET status = #{status}, updated_by = #{uid}, updated_at = NOW() WHERE id = #{id} AND is_deleted = 0")
    int updateStatus(@Param("id") Long id, @Param("status") String status, @Param("uid") Long uid);

    @Update("UPDATE prcp_sim_scheme SET is_deleted = 1, updated_by = #{uid}, updated_at = NOW() WHERE id = #{id} AND is_deleted = 0")
    int softDeleteById(@Param("id") Long id, @Param("uid") Long uid);
}