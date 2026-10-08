package com.prcp.business.esg.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.esg.entity.EsgCurvePoint;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Mapper
public interface EsgCurvePointMapper extends BaseMapper<EsgCurvePoint> {

    /** 列出曲线点（按 source + 时间窗） */
    @Select({
        "<script>",
        "SELECT id, curve_date AS curveDate, source,",
        "       theta0, theta1, theta2, theta3, lambda1, lambda2,",
        "       description, created_at AS createdAt",
        "  FROM prcp_esg_curve_point",
        " WHERE is_deleted = 0",
        "   <if test='source != null'> AND source = #{source} </if>",
        "   <if test='startDate != null'> AND curve_date &gt;= #{startDate} </if>",
        "   <if test='endDate != null'> AND curve_date &lt;= #{endDate} </if>",
        " ORDER BY curve_date DESC, source",
        " LIMIT #{pageSize} OFFSET #{offset}",
        "</script>"
    })
    List<Map<String, Object>> listCurves(@Param("source") String source,
                                          @Param("startDate") String startDate,
                                          @Param("endDate") String endDate,
                                          @Param("pageSize") int pageSize,
                                          @Param("offset") int offset);

    @Select({
        "<script>",
        "SELECT COUNT(*) FROM prcp_esg_curve_point WHERE is_deleted = 0",
        "   <if test='source != null'> AND source = #{source} </if>",
        "   <if test='startDate != null'> AND curve_date &gt;= #{startDate} </if>",
        "   <if test='endDate != null'> AND curve_date &lt;= #{endDate} </if>",
        "</script>"
    })
    int countCurves(@Param("source") String source,
                    @Param("startDate") String startDate,
                    @Param("endDate") String endDate);

    /** 数据源维度统计（curve_sources 端点） */
    @Select("SELECT source, COUNT(*) AS cnt, MIN(curve_date) AS minDate, MAX(curve_date) AS maxDate " +
            "FROM prcp_esg_curve_point WHERE is_deleted = 0 GROUP BY source")
    List<Map<String, Object>> groupBySource();

    /** 按 curve_date + source 查（用于单日还原 / rates 端点） */
    @Select({
        "<script>",
        "SELECT id, curve_date AS curveDate, source,",
        "       theta0, theta1, theta2, theta3, lambda1, lambda2,",
        "       raw_data_json AS rawDataJson,",
        "       description, created_at AS createdAt",
        "  FROM prcp_esg_curve_point",
        " WHERE is_deleted = 0 AND curve_date = #{curveDate}",
        "   <if test='source != null'> AND source = #{source} </if>",
        "</script>"
    })
    List<Map<String, Object>> selectByDateAndSource(@Param("curveDate") String curveDate,
                                                     @Param("source") String source);

    /** upsert 单条（ON DUPLICATE KEY UPDATE） */
    @Insert({
        "INSERT INTO prcp_esg_curve_point",
        "  (curve_date, source, theta0, theta1, theta2, theta3, lambda1, lambda2,",
        "   raw_data_json, description, created_by, updated_by)",
        "VALUES",
        "  (#{curveDate}, #{source}, #{theta0}, #{theta1}, #{theta2}, #{theta3}, #{lambda1}, #{lambda2},",
        "   #{rawDataJson}, #{description}, #{createdBy}, #{updatedBy})",
        "ON DUPLICATE KEY UPDATE",
        "  theta0 = VALUES(theta0), theta1 = VALUES(theta1), theta2 = VALUES(theta2), theta3 = VALUES(theta3),",
        "  lambda1 = VALUES(lambda1), lambda2 = VALUES(lambda2),",
        "  raw_data_json = VALUES(raw_data_json), description = VALUES(description),",
        "  updated_by = VALUES(updated_by)"
    })
    int upsertCurve(EsgCurvePoint p);

    /** 范围内曲线点列表（拟合 PCA 用，按 curve_date ASC） */
    @Select({
        "<script>",
        "SELECT curve_date AS curveDate, theta0, theta1, theta2, theta3, lambda1, lambda2",
        "  FROM prcp_esg_curve_point",
        " WHERE is_deleted = 0 AND source = #{source}",
        "   <if test='startDate != null'> AND curve_date &gt;= #{startDate} </if>",
        "   <if test='endDate != null'> AND curve_date &lt;= #{endDate} </if>",
        " ORDER BY curve_date ASC",
        "</script>"
    })
    List<Map<String, Object>> rangePoints(@Param("source") String source,
                                          @Param("startDate") String startDate,
                                          @Param("endDate") String endDate);
}