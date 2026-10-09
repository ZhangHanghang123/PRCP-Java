package com.prcp.business.coa.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.coa.entity.CoaNode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * <p>Mapper: prcp_coa_node 表的 SQL 访问层</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>listByScheme - 方案下所有节点 (按路径排序)</li>
 *   <li>countChildren - 子节点数 (用于删除校验)</li>
 *   <li>nodeWithScheme - 节点基础信息 + JOIN prcp_coa_scheme 取方案名 (对齐 Python sim/node-info)</li>
 *   <li>latestBalance - 节点最新一条余额 (对齐 Python prcp_data_balance 取 data_date 最大的)</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface CoaNodeMapper extends BaseMapper<CoaNode> {

    /**
     * <p>方案下所有节点 (按路径排序)</p>
     *
     * @param schemeId 方案 ID
     * @return 节点 Map 列表, 字段用 camelCase 别名
     */
    @Select("""
        SELECT id, scheme_id   AS schemeId,
               node_code      AS nodeCode,
               node_name      AS nodeName,
               parent_id      AS parentId,
               node_level     AS nodeLevel,
               path           AS path,
               sort_order     AS sortOrder,
               node_type      AS nodeType,
               status         AS status
        FROM prcp_coa_node
        WHERE scheme_id = #{schemeId} AND is_deleted = 0
        ORDER BY path, sort_order
    """)
    List<Map<String, Object>> listByScheme(@Param("schemeId") Long schemeId);

    /**
     * <p>子节点数 (删除节点前的校验)</p>
     *
     * @param parentId 父节点 ID
     * @return 未软删子节点数
     */
    @Select("""
        SELECT COUNT(*) AS cnt FROM prcp_coa_node
        WHERE parent_id = #{parentId} AND is_deleted = 0
    """)
    int countChildren(@Param("parentId") Long parentId);

    /**
     * <p>节点基础信息 + 关联方案 (JOIN prcp_coa_scheme 取 scheme_name), 对齐 Python sim/node-info</p>
     *
     * @param id 节点 ID
     * @return 单行 Map 含 coaSchemeCode/coaSchemeName, 不存在返回 null
     */
    @Select("""
        SELECT n.id, n.scheme_id   AS schemeId,
               n.node_code      AS nodeCode,
               n.node_name      AS nodeName,
               n.node_level     AS nodeLevel,
               n.node_type      AS nodeType,
               n.path           AS path,
               n.parent_id      AS parentId,
               n.status         AS status,
               s.scheme_code    AS coaSchemeCode,
               s.scheme_name    AS coaSchemeName
        FROM prcp_coa_node n
        JOIN prcp_coa_scheme s ON s.id = n.scheme_id
        WHERE n.id = #{id} AND n.is_deleted = 0
        LIMIT 1
    """)
    Map<String, Object> nodeWithScheme(@Param("id") Long id);

    /**
     * <p>节点最新一条余额 (对齐 Python prcp_data_balance 取 data_date 最大的)</p>
     *
     * @param id 节点 ID
     * @return 含 dataDate/currentAmount 的 Map, 不存在返回 null
     */
    @Select("""
        SELECT data_date    AS dataDate,
               current_amount AS currentAmount
        FROM prcp_data_balance
        WHERE coa_node_id = #{id} AND is_deleted = 0
        ORDER BY data_date DESC, id DESC
        LIMIT 1
    """)
    Map<String, Object> latestBalance(@Param("id") Long id);
}
