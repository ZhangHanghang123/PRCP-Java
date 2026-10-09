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

/**
 * <p>Mapper: prcp_esg_curve_point 表的 SQL 访问层 (ESG 利率曲线点)</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>listCurves / countCurves - 列出曲线点 (按 source + 时间窗, 分页)</li>
 *   <li>groupBySource - 数据源维度统计 (curve_sources 端点)</li>
 *   <li>selectByDateAndSource - 按 curve_date + source 查 (单日还原 / rates 端点)</li>
 *   <li>upsertCurve - upsert 单条 (ON DUPLICATE KEY UPDATE)</li>
 *   <li>rangePoints - 范围内曲线点列表 (拟合 PCA 用, 按 curve_date ASC)</li>
 * </ul>
 * </p>
 *
 * <p>关键字段: theta0/theta1/theta2/theta3 (PCA 因子载荷) + lambda1/lambda2 (特征值)。</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface EsgCurvePointMapper extends BaseMapper<EsgCurvePoint> {

    /**
     * <p>列出曲线点 (按 source + 时间窗, 分页)</p>
     *
     * @param source   数据源 (可选)
     * @param startDate 起始日期 yyyy-MM-dd (可选)
     * @param endDate   结束日期 yyyy-MM-dd (可选)
     * @param pageSize  每页条数
     * @param offset    偏移量
     * @return 曲线点 Map 列表, 按 curve_date DESC
     */
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

    /**
     * <p>曲线点总数 (配合 listCurves 分页用)</p>
     *
     * @param source    数据源 (可选)
     * @param startDate 起始日期 (可选)
     * @param endDate   结束日期 (可选)
     * @return 满足条件的曲线点总数
     */
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

    /**
     * <p>数据源维度统计 (curve_sources 端点)</p>
     *
     * @return 每源一行: source/cnt/minDate/maxDate
     */
    @Select("SELECT source, COUNT(*) AS cnt, MIN(curve_date) AS minDate, MAX(curve_date) AS maxDate " +
            "FROM prcp_esg_curve_point WHERE is_deleted = 0 GROUP BY source")
    List<Map<String, Object>> groupBySource();

    /**
     * <p>按 curve_date + source 查 (用于单日还原 / rates 端点)</p>
     *
     * @param curveDate 曲线日期 yyyy-MM-dd
     * @param source    数据源 (可选, null 查所有)
     * @return 曲线点列表, 含 rawDataJson
     */
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

    /**
     * <p>upsert 单条曲线点 (ON DUPLICATE KEY UPDATE)</p>
     *
     * @param p 曲线点实体 (含 theta0..theta3, lambda1, lambda2, raw_data_json)
     * @return 受影响行数 (1 新增 / 2 更新)
     */
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

    /**
     * <p>范围内曲线点列表 (拟合 PCA 用, 按 curve_date ASC)</p>
     *
     * @param source    数据源
     * @param startDate 起始日期 (可选)
     * @param endDate   结束日期 (可选)
     * @return 曲线点列表, 含 theta0/theta1/theta2/theta3/lambda1/lambda2
     */
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