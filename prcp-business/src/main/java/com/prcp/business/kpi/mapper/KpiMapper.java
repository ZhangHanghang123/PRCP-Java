package com.prcp.business.kpi.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.prcp.business.kpi.entity.KpiDefinition;
import com.prcp.business.kpi.entity.KpiScheme;
import com.prcp.business.kpi.entity.KpiScoreRule;
import com.prcp.business.kpi.entity.KpiValue;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface KpiMapper extends BaseMapper<KpiDefinition> {

    /** KPI 定义列表（连 scheme 和 report 名称） */
    @Select("""
        <script>
        SELECT d.id, d.scheme_id AS schemeId, s.scheme_name AS schemeName,
               s.scheme_code AS schemeCode,
               d.indicator_type AS indicatorType,
               d.kpi_code AS kpiCode, d.kpi_name AS kpiName,
               d.rpt_id AS rptId, r.report_name AS reportName,
               d.formula, d.script_path AS scriptPath, d.script_name AS scriptName,
               d.calc_unit AS calcUnit, d.formula_desc AS formulaDesc,
               d.threshold_min AS thresholdMin, d.threshold_max AS thresholdMax,
               d.status, d.created_at AS createdAt
        FROM prcp_kpi_definition d
        LEFT JOIN prcp_kpi_scheme s ON s.id = d.scheme_id AND s.is_deleted = 0
        LEFT JOIN prcp_rpt_report r ON r.id = d.rpt_id
        WHERE d.is_deleted = 0
          <if test='schemeId != null'>AND d.scheme_id = #{schemeId}</if>
          <if test='kpiCode != null and kpiCode != ""'>AND d.kpi_code = #{kpiCode}</if>
          <if test='keyword != null and keyword != ""'>AND (d.kpi_code LIKE CONCAT('%', #{keyword}, '%') OR d.kpi_name LIKE CONCAT('%', #{keyword}, '%'))</if>
        ORDER BY d.id DESC
        </script>
    """)
    List<Map<String, Object>> listDefs(@Param("schemeId") Long schemeId,
                                        @Param("kpiCode") String kpiCode,
                                        @Param("keyword") String keyword);

    /** KPI 值列表 */
    @Select("""
        <script>
        SELECT v.id, v.kpi_id AS kpiId, d.kpi_code AS kpiCode, d.kpi_name AS kpiName,
               d.scheme_id AS schemeId,
               v.data_date AS dataDate, v.version,
               v.current_value AS currentValue, v.prev_value AS prevValue,
               v.prev_year_value AS prevYearValue, v.calc_source AS calcSource,
               v.score
        FROM prcp_kpi_value v
        JOIN prcp_kpi_definition d ON d.id = v.kpi_id AND d.is_deleted = 0
        WHERE v.is_deleted = 0
          <if test='schemeId != null'>AND d.scheme_id = #{schemeId}</if>
          <if test='kpiId != null'>AND v.kpi_id = #{kpiId}</if>
          <if test='dataDate != null and dataDate != ""'>AND v.data_date = #{dataDate}</if>
        ORDER BY v.data_date DESC, v.id DESC
        </script>
    """)
    List<Map<String, Object>> listValues(@Param("schemeId") Long schemeId,
                                           @Param("kpiId") Long kpiId,
                                           @Param("dataDate") String dataDate);

    /** 评分规则列表 */
    @Select("""
        <script>
        SELECT r.id, r.scheme_id AS schemeId, s.scheme_name AS schemeName,
               r.kpi_id AS kpiId, d.kpi_code AS kpiCode, d.kpi_name AS kpiName,
               r.rule_name AS ruleName, r.calc_method AS calcMethod,
               r.total_score AS totalScore, r.higher_is_better AS higherIsBetter,
               r.description, r.status
        FROM prcp_kpi_score_rule r
        LEFT JOIN prcp_kpi_scheme s ON s.id = r.scheme_id AND s.is_deleted = 0
        LEFT JOIN prcp_kpi_definition d ON d.id = r.kpi_id AND d.is_deleted = 0
        WHERE r.is_deleted = 0
          <if test='schemeId != null'>AND r.scheme_id = #{schemeId}</if>
          <if test='kpiId != null'>AND r.kpi_id = #{kpiId}</if>
        ORDER BY r.id DESC
        </script>
    """)
    List<Map<String, Object>> listScoreRules(@Param("schemeId") Long schemeId,
                                              @Param("kpiId") Long kpiId);

    /** 报表表项（供 KPI 公式引用） */
    @Select("""
        SELECT id, report_id AS reportId, item_code AS itemCode, item_name AS itemName,
               data_type AS dataType
        FROM prcp_rpt_item
        WHERE report_id = #{rptId} AND is_deleted = 0
        ORDER BY item_level, sort_order
    """)
    List<Map<String, Object>> listRptItems(@Param("rptId") Long rptId);

    /** KPI 方案列表 */
    @Select("""
        SELECT id, scheme_code AS schemeCode, scheme_name AS schemeName,
               description, status
        FROM prcp_kpi_scheme
        WHERE is_deleted = 0
        ORDER BY id
    """)
    List<Map<String, Object>> listKpiSchemes();

    // ====== 方案 CRUD（注解版，无 XML） ======
    @Select("SELECT * FROM prcp_kpi_scheme ${ew.customSqlSegment}")
    List<KpiScheme> selectKpiSchemeList(@Param(Constants.WRAPPER) Wrapper<KpiScheme> wrapper);

    @Select("SELECT * FROM prcp_kpi_scheme WHERE id = #{id} AND is_deleted = 0")
    KpiScheme selectKpiSchemeById(@Param("id") Long id);

    @Insert("""
        INSERT INTO prcp_kpi_scheme (scheme_code, scheme_name, description, kpi_count, status, is_deleted, created_by, updated_by)
        VALUES (#{schemeCode}, #{schemeName}, #{description}, #{kpiCount}, #{status}, 0, #{createdBy}, #{updatedBy})
    """)
    @org.apache.ibatis.annotations.Options(useGeneratedKeys = true, keyProperty = "id")
    int insertScheme(KpiScheme s);

    @Update("""
        <script>
        UPDATE prcp_kpi_scheme
        <set>
          <if test='schemeCode != null'>scheme_code = #{schemeCode},</if>
          <if test='schemeName != null'>scheme_name = #{schemeName},</if>
          <if test='description != null'>description = #{description},</if>
          <if test='kpiCount != null'>kpi_count = #{kpiCount},</if>
          <if test='status != null'>status = #{status},</if>
          <if test='isDeleted != null'>is_deleted = #{isDeleted},</if>
          updated_at = NOW()
        </set>
        WHERE id = #{id}
        </script>
    """)
    int updateKpiSchemeById(KpiScheme s);
}
