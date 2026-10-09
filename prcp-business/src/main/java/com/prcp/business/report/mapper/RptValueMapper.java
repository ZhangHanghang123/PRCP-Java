package com.prcp.business.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.report.entity.RptValue;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * <p>Mapper: prcp_rpt_value 表的 SQL 访问层 (报表值)</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>listByItem - 按 item_id + data_date 查值 (对齐 Python /data-maint/values)</li>
 *   <li>findIdByItemAndDate - 按 (item_id, data_date) 查 id (upsert 判断)</li>
 *   <li>listByReportAndDate - 按 (report_id, data_date) 列表 (试算结果预览)</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface RptValueMapper extends BaseMapper<RptValue> {

    /**
     * <p>按 item_id + data_date 查值 (对齐 Python /data-maint/values)</p>
     *
     * @param itemId   表项 ID
     * @param dataDate 数据日期 (可选, null 取所有日期)
     * @return 报表值 Map 列表, 按 data_date DESC
     */
    @Select("""
        SELECT id, item_id AS itemId, DATE_FORMAT(data_date, '%Y-%m-%d') AS dataDate,
               value, source, calc_log AS calcLog, created_at AS createdAt, updated_at AS updatedAt
        FROM prcp_rpt_value
        WHERE item_id = #{itemId}
          AND (#{dataDate} IS NULL OR data_date = #{dataDate})
          AND is_deleted = 0
        ORDER BY data_date DESC
    """)
    List<Map<String, Object>> listByItem(@Param("itemId") Long itemId,
                                          @Param("dataDate") String dataDate);

    /**
     * <p>按 (item_id, data_date) 查 id (upsert 判断)</p>
     *
     * @param itemId   表项 ID
     * @param dataDate 数据日期 yyyy-MM-dd
     * @return 主键 ID, 不存在返回 null
     */
    @Select("""
        SELECT id FROM prcp_rpt_value
        WHERE item_id = #{itemId} AND data_date = #{dataDate} AND is_deleted = 0
        LIMIT 1
    """)
    Long findIdByItemAndDate(@Param("itemId") Long itemId, @Param("dataDate") String dataDate);

    /**
     * <p>按 (report_id, data_date) 列表 (试算结果预览)</p>
     *
     * @param reportId 报表 ID
     * @param dataDate 数据日期 yyyy-MM-dd
     * @return 报表值 Map 列表 (JOIN prcp_rpt_item 取 itemCode/itemName), 按 i.path, i.sort_order
     */
    @Select("""
        SELECT v.id, v.item_id AS itemId, v.data_date AS dataDate, v.value, v.source,
               i.item_code AS itemCode, i.item_name AS itemName, i.category, i.parent_id AS parentId
        FROM prcp_rpt_value v
        JOIN prcp_rpt_item i ON i.id = v.item_id AND i.is_deleted = 0
        WHERE i.report_id = #{reportId}
          AND v.data_date = #{dataDate}
          AND v.is_deleted = 0
        ORDER BY i.path, i.sort_order
    """)
    List<Map<String, Object>> listByReportAndDate(@Param("reportId") Long reportId,
                                                   @Param("dataDate") String dataDate);
}