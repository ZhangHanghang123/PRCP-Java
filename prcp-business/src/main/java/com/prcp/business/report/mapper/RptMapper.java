package com.prcp.business.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.report.entity.RptItem;
import com.prcp.business.report.entity.RptReport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface RptMapper extends BaseMapper<RptReport> {

    /** 报表列表（连 scheme_name） */
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

    /** 报表表项列表（按 report_id） */
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

    /** 树形构建（前端展示用） */
    default List<Map<String, Object>> treeItems(Long reportId) {
        List<Map<String, Object>> flat = listItems(reportId);
        return buildTree(flat);
    }

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