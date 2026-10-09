package com.prcp.business.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.report.entity.RptItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * <p>Mapper: prcp_rpt_item 表的 SQL 访问层 (报表表项子表)</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>softDeleteByPathPrefix - 按 path 前缀软删 (含自身 + 所有子节点)</li>
 *   <li>selectPathById - 取单个表项的 path (批量删除计算前缀)</li>
 *   <li>adjustReportItemCount - 增量调整报表 item_count (增 N 减 M)</li>
 *   <li>updateByDynamic - 动态 UPDATE (setClause 拼接任意字段)</li>
 * </ul>
 * </p>
 *
 * <p>对齐 Python batch_items 的 delete_ids 行为。</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface RptItemMapper extends BaseMapper<RptItem> {

    /**
     * <p>按 path 前缀软删除 (含自身 + 所有子节点)</p>
     * <p>对齐 Python batch_items 的 delete_ids 行为: UPDATE prcp_rpt_item SET is_deleted=1 WHERE path LIKE :pathPrefix</p>
     *
     * @param pathPrefix path 前缀 (LIKE 模式, 调用方负责加 %)
     * @param uid        操作人 ID
     * @return 受影响行数
     */
    @Update({
        "UPDATE prcp_rpt_item",
        "   SET is_deleted = 1, updated_by = #{uid}",
        " WHERE path LIKE #{pathPrefix} AND is_deleted = 0"
    })
    int softDeleteByPathPrefix(@Param("pathPrefix") String pathPrefix,
                               @Param("uid") Long uid);

    /**
     * <p>取单个表项的 path (用于批量删除时计算前缀)</p>
     *
     * @param id 表项 ID
     * @return path 字符串, 不存在返回 null
     */
    @Select("SELECT path FROM prcp_rpt_item WHERE id = #{id} AND is_deleted = 0 LIMIT 1")
    String selectPathById(@Param("id") Long id);

    /**
     * <p>增量调整报表 item_count (增 N 减 M)</p>
     * <p>对齐 Python batch_items 末尾的 UPDATE prcp_rpt_report</p>
     *
     * @param reportId 报表 ID
     * @param delta    增量 (可正可负)
     * @return 受影响行数
     */
    @Update({
        "UPDATE prcp_rpt_report",
        "   SET item_count = item_count + #{delta},",
        "       updated_at = NOW()",
        " WHERE id = #{reportId}"
    })
    int adjustReportItemCount(@Param("reportId") Long reportId,
                              @Param("delta") int delta);

    /**
     * <p>动态 UPDATE (用 setClause 拼接任意字段; values 用 #{} 绑定)</p>
     * <p>setClause 形如: "item_code = #{item_code}, formula = #{formula}"</p>
     * <p>values 是 setClause 里 #{xxx} 对应的参数 map</p>
     * <p>用于 batchItems 里 update 列表的多字段更新</p>
     *
     * @param id        表项 ID
     * @param setClause 拼接的 SET 子句 (含 #{} 占位符)
     * @param values    占位符参数 map
     * @return 受影响行数
     */
    @Update({
        "<script>",
        "UPDATE prcp_rpt_item SET ${setClause}",
        " WHERE id = #{id} AND is_deleted = 0",
        "</script>"
    })
    int updateByDynamic(@Param("id") Long id,
                        @Param("setClause") String setClause,
                        @Param("values") java.util.Map<String, Object> values);
}