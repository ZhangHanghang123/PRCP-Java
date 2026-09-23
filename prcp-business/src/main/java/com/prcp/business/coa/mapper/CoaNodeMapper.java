package com.prcp.business.coa.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.coa.entity.CoaNode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface CoaNodeMapper extends BaseMapper<CoaNode> {

    /**
     * 方案下所有节点（按路径排序）
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
     * 子节点数
     */
    @Select("""
        SELECT COUNT(*) AS cnt FROM prcp_coa_node
        WHERE parent_id = #{parentId} AND is_deleted = 0
    """)
    int countChildren(@Param("parentId") Long parentId);
}
