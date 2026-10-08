package com.prcp.business.rate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.rate.entity.RateScheme;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface RateSchemeMapper extends BaseMapper<RateScheme> {

    /**
     * 列出曲线方案（含 point_count / latest_date / latest_y10 统计子查询）
     * 对齐 Python routers/rate.py list_schemes
     */
    @Select({
        "<script>",
        "SELECT s.id, s.curve_code AS curveCode, s.curve_name AS curveName,",
        "       s.curve_type AS curveType, s.ccy AS currency, s.data_source AS dataSource,",
        "       s.description, s.status, s.created_at AS createdAt, s.updated_at AS updatedAt,",
        "       (SELECT COUNT(*) FROM prcp_rate_point p",
        "         WHERE p.curve_id = s.id AND p.is_deleted = 0) AS pointCount,",
        "       (SELECT MAX(data_date) FROM prcp_rate_point",
        "         WHERE curve_id = s.id AND is_deleted = 0) AS latestDate,",
        "       (SELECT rate_y10 FROM prcp_rate_point",
        "         WHERE curve_id = s.id AND is_deleted = 0",
        "         ORDER BY data_date DESC LIMIT 1) AS latestY10",
        "  FROM prcp_rate_scheme s",
        " WHERE s.is_deleted = 0",
        "   <if test='curveType != null'> AND s.curve_type = #{curveType} </if>",
        "   <if test='status != null'> AND s.status = #{status} </if>",
        " ORDER BY s.curve_type, s.curve_code",
        "</script>"
    })
    List<Map<String, Object>> listSchemes(@Param("curveType") String curveType,
                                           @Param("status") String status);

    /** 按 curve_code 查（upsert_point 时用） */
    @Select("SELECT id FROM prcp_rate_scheme WHERE curve_code = #{curveCode} AND is_deleted = 0 LIMIT 1")
    Long selectIdByCurveCode(@Param("curveCode") String curveCode);

    /** 按 curve_code 查名称 */
    @Select("SELECT curve_name FROM prcp_rate_scheme WHERE curve_code = #{curveCode} AND is_deleted = 0 LIMIT 1")
    String selectCurveName(@Param("curveCode") String curveCode);

    /** 软删曲线方案 */
    @Update("UPDATE prcp_rate_scheme SET is_deleted = 1, updated_by = #{uid} WHERE id = #{id} AND is_deleted = 0")
    int softDeleteById(@Param("id") Long id, @Param("uid") Long uid);
}