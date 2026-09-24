package com.prcp.business.data.basic.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;

import java.util.List;
import java.util.Map;

/**
 * 基础数据 mapper
 * - 64+64 桶结构（orig_m1..m60 + orig_y10/y15/y20/y30 + rem_m1..m60 + rem_y10/y15/y20/y30）
 */
@Mapper
public interface BasicDataMapper {

    /**
     * 列表查询（带方案/日期/类别/关键词过滤）
     * 注意：schemeId 需通过 prcp_coa_node 关联过滤
     */
    @Select("""
        <script>
        SELECT
          d.id, d.data_date AS dataDate, d.coa_node_id AS coaNodeId,
          d.node_code AS nodeCode, d.node_name AS nodeName,
          d.node_level AS nodeLevel, d.parent_code AS parentCode,
          d.is_leaf AS isLeaf, d.category, d.date_offset AS dateOffset,
          d.offset_unit AS offsetUnit,
          d.orig_m1 AS origM1, d.orig_m2 AS origM2, d.orig_m3 AS origM3,
          d.orig_m4 AS origM4, d.orig_m5 AS origM5, d.orig_m6 AS origM6,
          d.orig_m7 AS origM7, d.orig_m8 AS origM8, d.orig_m9 AS origM9,
          d.orig_m10 AS origM10, d.orig_m11 AS origM11, d.orig_m12 AS origM12,
          d.orig_y10 AS origY10, d.orig_y15 AS origY15, d.orig_y20 AS origY20, d.orig_y30 AS origY30,
          d.rem_m1 AS remM1, d.rem_m2 AS remM2, d.rem_m3 AS remM3,
          d.rem_m4 AS remM4, d.rem_m5 AS remM5, d.rem_m6 AS remM6,
          d.rem_m7 AS remM7, d.rem_m8 AS remM8, d.rem_m9 AS remM9,
          d.rem_m10 AS remM10, d.rem_m11 AS remM11, d.rem_m12 AS remM12,
          d.rem_y10 AS remY10, d.rem_y15 AS remY15, d.rem_y20 AS remY20, d.rem_y30 AS remY30
        FROM prcp_data_basic d
        LEFT JOIN prcp_coa_node n ON n.id = d.coa_node_id AND n.is_deleted = 0
        WHERE 1=1
          <if test='schemeId != null'>AND n.scheme_id = #{schemeId}</if>
          <if test='dataDate != null and dataDate != ""'>AND d.data_date = #{dataDate}</if>
          <if test='category != null and category != ""'>AND d.category = #{category}</if>
          <if test='nodeKw != null and nodeKw != ""'>AND (d.node_code LIKE CONCAT('%', #{nodeKw}, '%') OR d.node_name LIKE CONCAT('%', #{nodeKw}, '%'))</if>
        ORDER BY d.data_date DESC, n.path, d.coa_node_id
        LIMIT 1000
        </script>
    """)
    List<Map<String, Object>> list(@Param("schemeId") Long schemeId,
                                   @Param("dataDate") String dataDate,
                                   @Param("category") String category,
                                   @Param("nodeKw") String nodeKw);

    /** 可用日期 */
    @Select("""
        SELECT DISTINCT d.data_date AS d
        FROM prcp_data_basic d
        LEFT JOIN prcp_coa_node n ON n.id = d.coa_node_id
        WHERE 1=1
          <if test='schemeId != null'>AND n.scheme_id = #{schemeId}</if>
        ORDER BY d DESC
        LIMIT 60
    """)
    List<String> dates(@Param("schemeId") Long schemeId);

    /**
     * 透视：节点 × 桶
     * 返回 { dates: [...], buckets: [...], rows: [{nodeCode, nodeName, cells: [{date, bucket, value}]}] }
     * 这里只返回最长 24 月 × 8 个代表桶
     */
    @Select("""
        <script>
        SELECT
          d.coa_node_id AS coaNodeId, d.node_code AS nodeCode, d.node_name AS nodeName,
          d.data_date AS dataDate, d.category, d.node_level AS nodeLevel,
          -- 代表 8 个桶（orig_m1/m3/m6/m12/y10/y15/y20/y30）
          d.orig_m1 AS origM1, d.orig_m3 AS origM3, d.orig_m6 AS origM6,
          d.orig_m12 AS origM12,
          d.orig_y10 AS origY10, d.orig_y15 AS origY15, d.orig_y20 AS origY20, d.orig_y30 AS origY30,
          d.rem_m1 AS remM1, d.rem_m3 AS remM3, d.rem_m6 AS remM6,
          d.rem_m12 AS remM12,
          d.rem_y10 AS remY10, d.rem_y15 AS remY15, d.rem_y20 AS remY20, d.rem_y30 AS remY30
        FROM prcp_data_basic d
        LEFT JOIN prcp_coa_node n ON n.id = d.coa_node_id AND n.is_deleted = 0
        WHERE 1=1
          <if test='schemeId != null'>AND n.scheme_id = #{schemeId}</if>
          <if test='dataDate != null and dataDate != ""'>AND d.data_date = #{dataDate}</if>
        ORDER BY n.path, d.data_date DESC
        LIMIT 500
        </script>
    """)
    List<Map<String, Object>> matrix(@Param("schemeId") Long schemeId,
                                      @Param("dataDate") String dataDate);

    /** upsert 前先检查是否存在 */
    @Select("SELECT id FROM prcp_data_basic WHERE coa_node_id = #{nodeId} AND data_date = #{dataDate} LIMIT 1")
    Long findId(@Param("nodeId") Long nodeId, @Param("dataDate") String dataDate);

    /** 动态 update（XML 实现） */
    void updateByDynamic(@Param("id") Long id, @Param("columns") String columns, @Param("values") List<Object> values);

    /** 动态 insert（XML 实现） */
    void insertByDynamic(@Param("dataDate") String dataDate,
                          @Param("nodeId") Long nodeId,
                          @Param("columns") String columns,
                          @Param("values") List<Object> values);

    /** 物理删除 */
    @Delete("DELETE FROM prcp_data_basic WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}