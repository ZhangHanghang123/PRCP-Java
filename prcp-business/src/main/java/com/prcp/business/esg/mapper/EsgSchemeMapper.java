package com.prcp.business.esg.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.esg.entity.EsgScheme;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface EsgSchemeMapper extends BaseMapper<EsgScheme> {

    /**
     * 列出方案（含 run_count + last_run_at 子查询）
     * 对齐 Python routers/esg.py list_schemes
     */
    @Select({
        "<script>",
        "SELECT s.id, s.scheme_code AS schemeCode, s.scheme_name AS schemeName,",
        "       s.description, s.data_source AS dataSource,",
        "       s.start_date AS startDate, s.end_date AS endDate,",
        "       s.n_factors AS nFactors, s.maturities_json AS maturitiesJson,",
        "       s.n_scenarios AS nScenarios, s.n_steps AS nSteps,",
        "       s.seed, s.initial_yields_json AS initialYieldsJson,",
        "       s.status, s.created_at AS createdAt, s.updated_at AS updatedAt,",
        "       (SELECT COUNT(*) FROM prcp_esg_run r WHERE r.scheme_id = s.id) AS runCount,",
        "       (SELECT MAX(created_at) FROM prcp_esg_run r WHERE r.scheme_id = s.id) AS lastRunAt",
        "  FROM prcp_esg_scheme s",
        " WHERE s.is_deleted = 0",
        "   <if test='keyword != null'> AND (s.scheme_code LIKE CONCAT('%', #{keyword}, '%') OR s.scheme_name LIKE CONCAT('%', #{keyword}, '%')) </if>",
        "   <if test='status != null'> AND s.status = #{status} </if>",
        " ORDER BY s.id DESC",
        " LIMIT #{pageSize} OFFSET #{offset}",
        "</script>"
    })
    List<Map<String, Object>> listSchemes(@Param("keyword") String keyword,
                                          @Param("status") String status,
                                          @Param("pageSize") int pageSize,
                                          @Param("offset") int offset);

    @Select({
        "<script>",
        "SELECT COUNT(*) FROM prcp_esg_scheme s WHERE s.is_deleted = 0",
        "   <if test='keyword != null'> AND (s.scheme_code LIKE CONCAT('%', #{keyword}, '%') OR s.scheme_name LIKE CONCAT('%', #{keyword}, '%')) </if>",
        "   <if test='status != null'> AND s.status = #{status} </if>",
        "</script>"
    })
    int countSchemes(@Param("keyword") String keyword,
                     @Param("status") String status);

    /** 按 scheme_code 查 id（用于唯一性校验） */
    @Select("SELECT id FROM prcp_esg_scheme WHERE scheme_code = #{code} AND is_deleted = 0 LIMIT 1")
    Long selectIdByCode(@Param("code") String code);

    /** 软删方案 */
    @Select("UPDATE prcp_esg_scheme SET is_deleted = 1, updated_by = #{uid} WHERE id = #{id} AND is_deleted = 0")
    int softDeleteById(@Param("id") Long id, @Param("uid") Long uid);

    /** 加载完整方案（用于 update / execution） */
    @Select("SELECT * FROM prcp_esg_scheme WHERE id = #{id} AND is_deleted = 0")
    EsgScheme selectByIdActive(@Param("id") Long id);
}