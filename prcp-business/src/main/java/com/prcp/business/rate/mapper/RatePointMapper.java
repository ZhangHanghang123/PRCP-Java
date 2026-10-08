package com.prcp.business.rate.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.rate.entity.RatePoint;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Mapper
public interface RatePointMapper extends BaseMapper<RatePoint> {

    /**
     * 列出利率点（JOIN prcp_rate_scheme 取 curve_name）
     * 对齐 Python routers/rate.py list_points
     */
    @Select({
        "<script>",
        "SELECT p.id, p.curve_id AS curveId, p.curve_code AS curveCode,",
        "       s.curve_name AS curveName, p.data_date AS dataDate, p.ccy AS currency,",
        "       p.rate_d1, p.rate_d7, p.rate_m1, p.rate_m3, p.rate_m6,",
        "       p.rate_y1, p.rate_y2, p.rate_y3, p.rate_y5, p.rate_y10,",
        "       p.rate_y15, p.rate_y20, p.rate_y30,",
        "       p.curve_shift_bps AS curveShiftBps, p.curve_slope AS curveSlope,",
        "       p.source_date AS sourceDate, p.remark,",
        "       p.created_at AS createdAt, p.updated_at AS updatedAt",
        "  FROM prcp_rate_point p",
        "  JOIN prcp_rate_scheme s ON s.id = p.curve_id",
        " WHERE p.is_deleted = 0",
        "   <if test='curveCode != null'> AND p.curve_code = #{curveCode} </if>",
        "   <if test='dataDate != null'> AND p.data_date = #{dataDate} </if>",
        " ORDER BY p.curve_code, p.data_date DESC",
        "</script>"
    })
    List<Map<String, Object>> listPoints(@Param("curveCode") String curveCode,
                                         @Param("dataDate") String dataDate);

    /** 查上一个数据日期的 rate_y10（计算 curve_shift_bps 用） */
    @Select({
        "SELECT rate_y10 FROM prcp_rate_point",
        " WHERE curve_code = #{curveCode} AND data_date < #{dataDate} AND is_deleted = 0",
        " ORDER BY data_date DESC LIMIT 1"
    })
    java.math.BigDecimal selectPrevY10(@Param("curveCode") String curveCode,
                                       @Param("dataDate") String dataDate);

    /** 历史曲线对比（按日期升序 + 期限点） */
    @Select({
        "<script>",
        "SELECT data_date,",
        "       rate_d1, rate_d7, rate_m1, rate_m3, rate_m6,",
        "       rate_y1, rate_y2, rate_y3, rate_y5, rate_y10,",
        "       rate_y15, rate_y20, rate_y30, curve_slope",
        "  FROM prcp_rate_point",
        " WHERE curve_code = #{curveCode} AND is_deleted = 0",
        "   <if test='startDate != null'> AND data_date &gt;= #{startDate} </if>",
        "   <if test='endDate != null'> AND data_date &lt;= #{endDate} </if>",
        " ORDER BY data_date ASC",
        "</script>"
    })
    List<Map<String, Object>> listForCompare(@Param("curveCode") String curveCode,
                                              @Param("startDate") String startDate,
                                              @Param("endDate") String endDate);

    /** 按 (curve_code + data_date + term) 查单一利率 */
    @Select({
        "SELECT ${col} FROM prcp_rate_point",
        " WHERE curve_code = #{curveCode} AND data_date = #{dataDate} AND is_deleted = 0",
        " LIMIT 1"
    })
    java.math.BigDecimal lookupRate(@Param("curveCode") String curveCode,
                                    @Param("dataDate") LocalDate dataDate,
                                    @Param("col") String col);

    /** 软删利率点 */
    @Update("UPDATE prcp_rate_point SET is_deleted = 1 WHERE id = #{id} AND is_deleted = 0")
    int softDeleteById(@Param("id") Long id);

    /** 软删某曲线方案下的所有利率点（删除方案时级联） */
    @Update("UPDATE prcp_rate_point SET is_deleted = 1 WHERE curve_id = #{curveId} AND is_deleted = 0")
    int softDeleteByCurveId(@Param("curveId") Long curveId);

    /** UPSERT 单条利率点（INSERT ON DUPLICATE KEY UPDATE） */
    @Update({
        "INSERT INTO prcp_rate_point",
        "  (curve_id, curve_code, data_date, ccy,",
        "   rate_d1, rate_d7, rate_m1, rate_m3, rate_m6,",
        "   rate_y1, rate_y2, rate_y3, rate_y5, rate_y10,",
        "   rate_y15, rate_y20, rate_y30,",
        "   curve_shift_bps, curve_slope, source_date, remark,",
        "   created_by, updated_by)",
        " VALUES",
        "  (#{p.curveId}, #{p.curveCode}, #{p.dataDate}, #{p.ccy},",
        "   #{p.rateD1}, #{p.rateD7}, #{p.rateM1}, #{p.rateM3}, #{p.rateM6},",
        "   #{p.rateY1}, #{p.rateY2}, #{p.rateY3}, #{p.rateY5}, #{p.rateY10},",
        "   #{p.rateY15}, #{p.rateY20}, #{p.rateY30},",
        "   #{p.curveShiftBps}, #{p.curveSlope}, #{p.sourceDate}, #{p.remark},",
        "   #{uid}, #{uid})",
        " ON DUPLICATE KEY UPDATE",
        "   ccy = #{p.ccy},",
        "   rate_d1 = #{p.rateD1}, rate_d7 = #{p.rateD7},",
        "   rate_m1 = #{p.rateM1}, rate_m3 = #{p.rateM3}, rate_m6 = #{p.rateM6},",
        "   rate_y1 = #{p.rateY1}, rate_y2 = #{p.rateY2}, rate_y3 = #{p.rateY3},",
        "   rate_y5 = #{p.rateY5}, rate_y10 = #{p.rateY10},",
        "   rate_y15 = #{p.rateY15}, rate_y20 = #{p.rateY20}, rate_y30 = #{p.rateY30},",
        "   curve_shift_bps = #{p.curveShiftBps}, curve_slope = #{p.curveSlope},",
        "   source_date = #{p.sourceDate}, remark = #{p.remark},",
        "   updated_by = #{uid}, is_deleted = 0"
    })
    int upsertPoint(@Param("p") RatePoint p, @Param("uid") Long uid);
}