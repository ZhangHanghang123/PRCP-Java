package com.prcp.business.data.reverse.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

import java.util.List;
import java.util.Map;

@Mapper
public interface ReverseDataMapper {

    @Select("""
        <script>
        SELECT
          id, record_id AS recordId, scheme_code AS schemeCode, run_id AS runId,
          coa_scheme_id AS coaSchemeId,
          data_date AS dataDate, date_offset AS dateOffset, offset_unit AS offsetUnit,
          coa_node_id AS coaNodeId, node_code AS nodeCode, node_name AS nodeName,
          node_level AS nodeLevel, parent_code AS parentCode, is_leaf AS isLeaf, category,
          orig_m1 AS origM1, orig_m3 AS origM3, orig_m6 AS origM6,
          orig_m12 AS origM12,
          orig_y10 AS origY10, orig_y15 AS origY15, orig_y20 AS origY20, orig_y30 AS origY30,
          rem_m1 AS remM1, rem_m3 AS remM3, rem_m6 AS remM6,
          rem_m12 AS remM12,
          rem_y10 AS remY10, rem_y15 AS remY15, rem_y20 AS remY20, rem_y30 AS remY30
        FROM prcp_data_reverse
        WHERE 1=1
          <if test='schemeCode != null and schemeCode != ""'>AND scheme_code = #{schemeCode}</if>
          <if test='dataDate != null and dataDate != ""'>AND data_date = #{dataDate}</if>
          <if test='category != null and category != ""'>AND category = #{category}</if>
          <if test='nodeKw != null and nodeKw != ""'>AND (node_code LIKE CONCAT('%', #{nodeKw}, '%') OR node_name LIKE CONCAT('%', #{nodeKw}, '%'))</if>
        ORDER BY data_date DESC, coa_node_id
        LIMIT 1000
        </script>
    """)
    List<Map<String, Object>> list(@Param("schemeCode") String schemeCode,
                                   @Param("dataDate") String dataDate,
                                   @Param("category") String category,
                                   @Param("nodeKw") String nodeKw);

    /** 列出已有 scheme_code */
    @Select("""
        SELECT DISTINCT scheme_code AS code FROM prcp_data_reverse
        WHERE scheme_code IS NOT NULL ORDER BY code
    """)
    List<Map<String, Object>> schemeCodes();

    @Select("""
        SELECT DISTINCT data_date AS d FROM prcp_data_reverse
        WHERE (#{schemeCode} IS NULL OR scheme_code = #{schemeCode})
        ORDER BY d DESC LIMIT 60
    """)
    List<String> dates(@Param("schemeCode") String schemeCode);

    /** 完整 64+64 桶（matrix 用） */
    @Select("""
        <script>
        SELECT
          record_id AS recordId, scheme_code AS schemeCode, run_id AS runId,
          data_date AS dataDate, date_offset AS dateOffset, offset_unit AS offsetUnit,
          coa_node_id AS coaNodeId, node_code AS nodeCode, node_name AS nodeName,
          node_level AS nodeLevel, parent_code AS parentCode, is_leaf AS isLeaf, category,
          orig_m1 AS origM1, orig_m3 AS origM3, orig_m6 AS origM6,
          orig_m12 AS origM12,
          orig_y10 AS origY10, orig_y15 AS origY15, orig_y20 AS origY20, orig_y30 AS origY30,
          rem_m1 AS remM1, rem_m3 AS remM3, rem_m6 AS remM6,
          rem_m12 AS remM12,
          rem_y10 AS remY10, rem_y15 AS remY15, rem_y20 AS remY20, rem_y30 AS remY30
        FROM prcp_data_reverse
        WHERE 1=1
          <if test='schemeCode != null and schemeCode != ""'>AND scheme_code = #{schemeCode}</if>
          <if test='dataDate != null and dataDate != ""'>AND data_date = #{dataDate}</if>
        ORDER BY node_code, data_date
        LIMIT 500
        </script>
    """)
    List<Map<String, Object>> matrix(@Param("schemeCode") String schemeCode,
                                      @Param("dataDate") String dataDate);

    @Select("SELECT id FROM prcp_data_reverse WHERE coa_node_id = #{nodeId} AND data_date = #{dataDate} AND scheme_code = #{schemeCode} LIMIT 1")
    Long findId(@Param("nodeId") Long nodeId,
                @Param("dataDate") String dataDate,
                @Param("schemeCode") String schemeCode);

    /** 动态 update */
    void updateByDynamic(@Param("id") Long id, @Param("columns") String columns, @Param("values") List<Object> values);

    /** 动态 insert */
    void insertByDynamic(@Param("recordId") String recordId,
                          @Param("schemeCode") String schemeCode,
                          @Param("runId") Long runId,
                          @Param("coaSchemeId") Long coaSchemeId,
                          @Param("dataDate") String dataDate,
                          @Param("dateOffset") Integer dateOffset,
                          @Param("offsetUnit") String offsetUnit,
                          @Param("nodeId") Long nodeId,
                          @Param("nodeCode") String nodeCode,
                          @Param("nodeName") String nodeName,
                          @Param("nodeLevel") Integer nodeLevel,
                          @Param("parentCode") String parentCode,
                          @Param("isLeaf") Integer isLeaf,
                          @Param("category") String category,
                          @Param("columns") String columns,
                          @Param("values") List<Object> values);

    @Delete("DELETE FROM prcp_data_reverse WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
