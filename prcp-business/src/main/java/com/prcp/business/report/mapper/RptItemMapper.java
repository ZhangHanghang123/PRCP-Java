package com.prcp.business.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.report.entity.RptItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RptItemMapper extends BaseMapper<RptItem> {

    /**
     * 按 path 前缀软删除（含自身 + 所有子节点）
     * 对齐 Python batch_items 的 delete_ids 行为：
     *   UPDATE prcp_rpt_item SET is_deleted=1 WHERE path LIKE :pathPrefix
     */
    @Update({
        "UPDATE prcp_rpt_item",
        "   SET is_deleted = 1, updated_by = #{uid}",
        " WHERE path LIKE #{pathPrefix} AND is_deleted = 0"
    })
    int softDeleteByPathPrefix(@Param("pathPrefix") String pathPrefix,
                               @Param("uid") Long uid);

    /** 取单个表项的 path（用于批量删除时计算前缀） */
    @Select("SELECT path FROM prcp_rpt_item WHERE id = #{id} AND is_deleted = 0 LIMIT 1")
    String selectPathById(@Param("id") Long id);

    /**
     * 增量调整报表 item_count（增 N 减 M）
     * 对齐 Python batch_items 末尾的 UPDATE prcp_rpt_report
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
     * 动态 UPDATE（用 setClause 拼接任意字段；values 用 #{} 绑定）
     * setClause 形如："item_code = #{item_code}, formula = #{formula}"
     * values 是 setClause 里 #{xxx} 对应的参数 map
     * 用于 batchItems 里 update 列表的多字段更新
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