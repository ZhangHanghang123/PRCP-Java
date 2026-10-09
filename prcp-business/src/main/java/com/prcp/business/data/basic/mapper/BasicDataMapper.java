package com.prcp.business.data.basic.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * <p>Mapper: prcp_data_basic 表的 SQL 访问层 (基础数据 64+64 桶结构)</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>list - 列表查询 (带方案/日期/类别/关键词过滤, 全 64 桶 + 7 度量)</li>
 *   <li>dates - 可用日期 (带方案过滤)</li>
 *   <li>matrix - 矩阵查询 (节点 × 桶 + 7 度量)</li>
 *   <li>findId - upsert 前检查 4 元组是否存在</li>
 *   <li>selectNodeMeta - 取账户册节点元数据 (upsert 自动补全)</li>
 *   <li>listBySchemeMatrix - by-scheme-matrix 数据查询</li>
 *   <li>listNodesByScheme - 取方案下所有节点 (按 path, sort_order 排序)</li>
 *   <li>updateByDynamic / insertByDynamic - 动态 update / insert (XML 实现)</li>
 *   <li>softDeleteById / softDeleteByIds - 逻辑删除</li>
 * </ul>
 * </p>
 *
 * <p>桶结构: orig_m1..m60 + orig_y10/y15/y20/y30 + rem_m1..m60 + rem_y10/y15/y20/y30 (共 128 桶)</p>
 * <p>列由 BasicDataBuckets 动态生成, 与 Python 版 buckets.py 完全对齐</p>
 * <p>7 度量字段: asf_rsf / hqla_factor / current_balance / avg_balance / weighted_rate / interest_amount / risk_weight</p>
 * <p>4 元组唯一键: (coa_node_id, data_date, date_offset, offset_unit)</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface BasicDataMapper {

    /**
     * <p>列表查询 (带方案/日期/类别/关键词过滤)</p>
     * <p>SELECT 全 64 桶 orig + 64 桶 rem + 7 度量字段</p>
     */
    String LIST_COLUMNS = "d.id, d.data_date AS dataDate, d.coa_node_id AS coaNodeId,"
        + " d.node_code AS nodeCode, d.node_name AS nodeName,"
        + " d.node_level AS nodeLevel, d.parent_code AS parentCode,"
        + " d.is_leaf AS isLeaf, d.category,"
        + " d.date_offset AS dateOffset, d.offset_unit AS offsetUnit,"
        + " d.asf_rsf AS asfRsf, d.hqla_factor AS hqlaFactor,"
        + " d.current_balance AS currentBalance, d.avg_balance AS avgBalance,"
        + " d.weighted_rate AS weightedRate, d.interest_amount AS interestAmount,"
        + " d.risk_weight AS riskWeight, d.calc_note AS calcNote";

    /**
     * <p>列表查询 (带方案/日期/类别/关键词过滤)</p>
     *
     * @param schemeId 方案 ID (可选)
     * @param coaNodeId 节点 ID (可选)
     * @param startDate 起始日期 yyyy-MM-dd (可选)
     * @param endDate 结束日期 yyyy-MM-dd (可选)
     * @param dataDate 精确日期 yyyy-MM-dd (可选)
     * @param category 类别 ASSET/LIABILITY/EQUITY/OFF_BALANCE (可选)
     * @param nodeKw 节点编码/名称模糊搜索 (可选)
     * @param buckets 桶列表 (例 ["m1","m3","y10"])
     * @return 数据行列表 (含全 128 桶别名), 最多 1000 条
     */
    @Select({
        "<script>",
        "SELECT ", LIST_COLUMNS,
        "<foreach collection=\"buckets\" item=\"b\" separator=\"\">",
        ", d.orig_${b} AS orig${b.substring(0,1).toUpperCase()}${b.substring(1)}",
        ", d.rem_${b} AS rem${b.substring(0,1).toUpperCase()}${b.substring(1)}",
        "</foreach>",
        " FROM prcp_data_basic d",
        " LEFT JOIN prcp_coa_node n ON n.id = d.coa_node_id",
        " WHERE d.is_deleted = 0",
        "   <if test='schemeId != null'>AND n.scheme_id = #{schemeId}</if>",
        "   <if test='coaNodeId != null'>AND d.coa_node_id = #{coaNodeId}</if>",
        "   <if test='startDate != null and startDate != \"\"'>AND d.data_date &gt;= #{startDate}</if>",
        "   <if test='endDate != null and endDate != \"\"'>AND d.data_date &lt;= #{endDate}</if>",
        "   <if test='dataDate != null and dataDate != \"\"'>AND d.data_date = #{dataDate}</if>",
        "   <if test='category != null and category != \"\"'>AND d.category = #{category}</if>",
        "   <if test='nodeKw != null and nodeKw != \"\"'>AND (d.node_code LIKE CONCAT('%', #{nodeKw}, '%') OR d.node_name LIKE CONCAT('%', #{nodeKw}, '%'))</if>",
        " ORDER BY d.data_date DESC, n.path, d.coa_node_id, d.date_offset",
        " LIMIT 1000",
        "</script>"
    })
    List<Map<String, Object>> list(@Param("schemeId") Long schemeId,
                                   @Param("coaNodeId") Long coaNodeId,
                                   @Param("startDate") String startDate,
                                   @Param("endDate") String endDate,
                                   @Param("dataDate") String dataDate,
                                   @Param("category") String category,
                                   @Param("nodeKw") String nodeKw,
                                   @Param("buckets") List<String> buckets);

    /**
     * <p>可用日期 (带方案过滤, 近 60 条, 按日期 DESC)</p>
     *
     * @param schemeId 方案 ID (可选, null 取全方案)
     * @return data_date 字符串列表 (yyyy-MM-dd)
     */
    @Select({
        "<script>",
        "SELECT DISTINCT CAST(d.data_date AS CHAR) AS d",
        " FROM prcp_data_basic d",
        " LEFT JOIN prcp_coa_node n ON n.id = d.coa_node_id",
        " WHERE d.is_deleted = 0",
        "   <if test='schemeId != null'>AND n.scheme_id = #{schemeId}</if>",
        " ORDER BY d DESC",
        " LIMIT 60",
        "</script>"
    })
    List<String> dates(@Param("schemeId") Long schemeId);

    /**
     * <p>矩阵查询常量: 节点 × 桶 (8 代表桶) + 7 度量</p>
     * <p>返回 { dates, buckets, rows: [{nodeCode, nodeName, cells: [{date, bucket, value}]}] }</p>
     */
    String MATRIX_COLUMNS = "d.coa_node_id AS coaNodeId, n.node_code AS nodeCode, n.node_name AS nodeName,"
        + " CAST(d.data_date AS CHAR) AS dataDate, d.category, d.node_level AS nodeLevel,"
        + " d.date_offset AS dateOffset, d.offset_unit AS offsetUnit,"
        + " d.asf_rsf AS asfRsf, d.hqla_factor AS hqlaFactor,"
        + " d.current_balance AS currentBalance, d.avg_balance AS avgBalance,"
        + " d.weighted_rate AS weightedRate, d.interest_amount AS interestAmount,"
        + " d.risk_weight AS riskWeight";

    /**
     * <p>矩阵查询 (节点 × 桶 + 7 度量)</p>
     * <p>返回 { dates, buckets, rows: [{nodeCode, nodeName, cells: [{date, bucket, value}]}] }</p>
     *
     * @param schemeId      方案 ID (可选)
     * @param dataDate      数据日期 yyyy-MM-dd (可选)
     * @param displayBuckets 显示桶列表 (例 ["m1","m3","y10"])
     * @return 矩阵数据行, 最多 1000 条
     */
    @Select({
        "<script>",
        "SELECT ", MATRIX_COLUMNS,
        "<foreach collection=\"displayBuckets\" item=\"b\" separator=\"\">",
        ", d.orig_${b} AS orig${b.substring(0,1).toUpperCase()}${b.substring(1)}",
        ", d.rem_${b} AS rem${b.substring(0,1).toUpperCase()}${b.substring(1)}",
        "</foreach>",
        " FROM prcp_data_basic d",
        " LEFT JOIN prcp_coa_node n ON n.id = d.coa_node_id",
        " WHERE d.is_deleted = 0",
        "   <if test='schemeId != null'>AND n.scheme_id = #{schemeId}</if>",
        "   <if test='dataDate != null and dataDate != \"\"'>AND d.data_date = #{dataDate}</if>",
        " ORDER BY d.data_date DESC, n.id, d.date_offset",
        " LIMIT 1000",
        "</script>"
    })
    List<Map<String, Object>> matrix(@Param("schemeId") Long schemeId,
                                      @Param("dataDate") String dataDate,
                                      @Param("displayBuckets") List<String> displayBuckets);

    /**
     * <p>upsert 前检查是否存在 (4 元组: coa_node_id + data_date + date_offset + offset_unit)</p>
     *
     * @param nodeId     节点 ID
     * @param dataDate   数据日期
     * @param dateOffset 日期偏移
     * @param offsetUnit 偏移单位 (D/M/Y)
     * @return 主键 ID, 不存在返回 null
     */
    @Select({
        "SELECT id FROM prcp_data_basic WHERE coa_node_id = #{nodeId}"
        + " AND data_date = #{dataDate}"
        + " AND date_offset = #{dateOffset}"
        + " AND offset_unit = #{offsetUnit} AND is_deleted = 0 LIMIT 1"
    })
    Long findId(@Param("nodeId") Long nodeId,
                @Param("dataDate") String dataDate,
                @Param("dateOffset") Integer dateOffset,
                @Param("offsetUnit") String offsetUnit);

    /**
     * <p>取账户册节点元数据 (用于 upsert 自动补全 nodeCode/nodeName/nodeLevel/parentCode/isLeaf/category)</p>
     *
     * @param nodeId 节点 ID
     * @return 单行 Map, 不存在返回 null
     */
    @Select({
        "SELECT node_code AS nodeCode, node_name AS nodeName, node_level AS nodeLevel,"
        + " parent_code AS parentCode, is_leaf AS isLeaf, category"
        + " FROM prcp_coa_node WHERE id = #{nodeId} LIMIT 1"
    })
    Map<String, Object> selectNodeMeta(@Param("nodeId") Long nodeId);

    /**
     * <p>by-scheme-matrix 数据查询常量: coa_node_id + 7 度量 + 全 64 桶 orig + 全 64 桶 rem</p>
     */
    String BSM_COLUMNS = "d.coa_node_id AS coaNodeId,"
        + " d.asf_rsf AS asfRsf, d.hqla_factor AS hqlaFactor,"
        + " d.current_balance AS currentBalance, d.avg_balance AS avgBalance,"
        + " d.weighted_rate AS weightedRate, d.interest_amount AS interestAmount,"
        + " d.risk_weight AS riskWeight";

    @Select({
        "<script>",
        "SELECT ", BSM_COLUMNS,
        "<foreach collection=\"buckets\" item=\"b\" separator=\"\">",
        ", d.orig_${b} AS orig${b.substring(0,1).toUpperCase()}${b.substring(1)}",
        ", d.rem_${b} AS rem${b.substring(0,1).toUpperCase()}${b.substring(1)}",
        "</foreach>",
        " FROM prcp_data_basic d",
        " JOIN prcp_coa_node n ON n.id = d.coa_node_id",
        " WHERE d.is_deleted = 0 AND n.scheme_id = #{schemeId}",
        "   <if test='dataDate != null and dataDate != \"\"'>AND d.data_date = #{dataDate}</if>",
        "   <if test='dateOffset != null'>AND d.date_offset = #{dateOffset}</if>",
        "   <if test='offsetUnit != null and offsetUnit != \"\"'>AND d.offset_unit = #{offsetUnit}</if>",
        " ORDER BY d.coa_node_id",
        "</script>"
    })
    List<Map<String, Object>> listBySchemeMatrix(@Param("schemeId") Long schemeId,
                                                  @Param("dataDate") String dataDate,
                                                  @Param("dateOffset") Integer dateOffset,
                                                  @Param("offsetUnit") String offsetUnit,
                                                  @Param("buckets") List<String> buckets);

    /**
     * <p>取方案下所有节点 (按 path, sort_order 排序), 用于 by-scheme-matrix 视图</p>
     * <p>注: 服务器 prcp_coa_node 没有 category 字段, 从 node_type 推断 (ASSET/LIAB/EQUITY/OTHER)</p>
     *
     * @param schemeId 方案 ID
     * @return 节点 Map 列表, 字段含 coaNodeId/nodeCode/nodeName/parentId/nodeLevel/nodeType/path/sortOrder/description/category
     */
    @Select({
        "SELECT n.id AS coaNodeId, n.node_code AS nodeCode, n.node_name AS nodeName,"
        + " n.parent_id AS parentId, n.node_level AS nodeLevel, n.node_type AS nodeType,"
        + " n.path, n.sort_order AS sortOrder, n.description,"
        + " (CASE WHEN n.node_type IS NOT NULL AND n.node_type != ''"
        + "    THEN SUBSTRING_INDEX(n.node_type, '_', 1) ELSE '' END) AS category"
        + " FROM prcp_coa_node n"
        + " WHERE n.scheme_id = #{schemeId} AND n.is_deleted = 0"
        + " ORDER BY n.path, n.sort_order"
    })
    List<Map<String, Object>> listNodesByScheme(@Param("schemeId") Long schemeId);

    /** 动态 update（XML 实现） */
    void updateByDynamic(@Param("id") Long id, @Param("setClause") String setClause, @Param("values") List<Object> values);

    /** 动态 insert（XML 实现） */
    void insertByDynamic(@Param("dataDate") String dataDate,
                          @Param("nodeId") Long nodeId,
                          @Param("dateOffset") Integer dateOffset,
                          @Param("offsetUnit") String offsetUnit,
                          @Param("columns") String columns,
                          @Param("values") List<Object> values,
                          @Param("nodeCode") String nodeCode,
                          @Param("nodeName") String nodeName,
                          @Param("nodeLevel") Integer nodeLevel,
                          @Param("parentCode") String parentCode,
                          @Param("isLeaf") Integer isLeaf,
                          @Param("category") String category);

    /** 逻辑删除：is_deleted=1 */
    void softDeleteById(@Param("id") Long id);

    /** 批量逻辑删除：is_deleted=1 */
    int softDeleteByIds(@Param("ids") List<Long> ids);
}