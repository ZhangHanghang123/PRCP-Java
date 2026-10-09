package com.prcp.business.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.report.entity.RptItem;
import com.prcp.business.report.entity.RptReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * <p>Mapper: prcp_rpt_report 表的 SQL 访问层 (报表主表)</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>listReports - 报表列表 (LEFT JOIN coa_scheme 取 scheme_name)</li>
 *   <li>listItems - 报表表项列表 (按 report_id)</li>
 *   <li>treeItems - 树形构建 (前端展示, 调 listItems + buildTree)</li>
 *   <li>buildTree - 静态方法: 扁平列表构建 parentId 树形</li>
 * </ul>
 * </p>
 *
 * <p>表项子表见 {@link RptItemMapper}。</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface RptMapper extends BaseMapper<RptReport> {

    /**
     * <p>报表列表 (LEFT JOIN prcp_coa_scheme 取 scheme_name)</p>
     *
     * @param reportType 报表类型 (当前 SQL 未使用, 由调用方过滤)
     * @param schemeId   方案 ID (当前 SQL 未使用)
     * @param keyword    关键字 (当前 SQL 未使用)
     * @return 报表 Map 列表, 按 ID DESC
     */
    @Select("""
        SELECT r.id, r.report_code AS reportCode, r.report_name AS reportName,
               r.report_type AS reportType, r.scheme_id AS schemeId,
               s.scheme_name AS schemeName, r.description, r.item_count AS itemCount,
               r.status, r.created_at AS createdAt, r.updated_at AS updatedAt
        FROM prcp_rpt_report r
        LEFT JOIN prcp_coa_scheme s ON s.id = r.scheme_id AND s.is_deleted = 0
        WHERE r.is_deleted = 0
        ORDER BY r.id DESC
    """)
    List<Map<String, Object>> listReports(@Param("reportType") String reportType,
                                            @Param("schemeId") Long schemeId,
                                            @Param("keyword") String keyword);

    /**
     * <p>报表表项列表 (按 report_id, 按 path, sort_order 排序)</p>
     *
     * @param reportId 报表 ID
     * @return 表项 Map 列表 (含 id/parentId/path/children)
     */
    @Select("""
        SELECT id, report_id AS reportId, category, item_code AS itemCode, item_name AS itemName,
               parent_id AS parentId, item_level AS itemLevel, data_type AS dataType,
               formula, coa_node_ids AS coaNodeIds, path,
               sort_order AS sortOrder, status, description
        FROM prcp_rpt_item
        WHERE report_id = #{reportId} AND is_deleted = 0
        ORDER BY path, sort_order
    """)
    List<Map<String, Object>> listItems(@Param("reportId") Long reportId);

    /**
     * <p>树形构建 (前端展示用, 调 listItems + buildTree)</p>
     *
     * @param reportId 报表 ID
     * @return 根节点列表 (含 children 字段递归嵌套)
     */
    default List<Map<String, Object>> treeItems(Long reportId) {
        List<Map<String, Object>> flat = listItems(reportId);
        return buildTree(flat);
    }

    /**
     * <p>静态方法: 扁平列表按 parentId 构建树形</p>
     *
     * @param flat 扁平表项列表 (需含 id + parentId 字段)
     * @return 根节点列表 (children 字段递归)
     */
    static List<Map<String, Object>> buildTree(List<Map<String, Object>> flat) {
        java.util.Map<Long, Map<String, Object>> map = new java.util.HashMap<>();
        for (Map<String, Object> n : flat) {
            n.put("children", new java.util.ArrayList<>());
            map.put(((Number) n.get("id")).longValue(), n);
        }
        java.util.List<Map<String, Object>> roots = new java.util.ArrayList<>();
        for (Map<String, Object> n : flat) {
            Object pid = n.get("parentId");
            if (pid != null && map.containsKey(((Number) pid).longValue())) {
                @SuppressWarnings("unchecked")
                java.util.List<Map<String, Object>> ch =
                    (java.util.List<Map<String, Object>>) map.get(((Number) pid).longValue()).get("children");
                ch.add(n);
            } else {
                roots.add(n);
            }
        }
        return roots;
    }
}