package com.prcp.business.kpi.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.kpi.entity.KpiScoreSegment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * <p>Mapper: prcp_kpi_score_segment 表的 SQL 访问层 (评分规则分段子表)</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>listByRuleId - 按 rule_id 列分段 (按 seg_order)</li>
 *   <li>softDeleteByRuleId - 按规则软删所有分段 (规则删除时级联)</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface KpiScoreSegmentMapper extends BaseMapper<KpiScoreSegment> {

    /**
     * <p>按 rule_id 列出分段 (按 seg_order 升序)</p>
     *
     * @param ruleId 评分规则 ID
     * @return KpiScoreSegment 实体列表
     */
    @Select({
        "SELECT id, rule_id, seg_order, min_value, max_value, score, segment_desc, "
        + " is_deleted, created_at, updated_at "
        + " FROM prcp_kpi_score_segment "
        + " WHERE rule_id = #{ruleId} AND is_deleted = 0 "
        + " ORDER BY seg_order"
    })
    List<KpiScoreSegment> listByRuleId(@Param("ruleId") Long ruleId);

    /**
     * <p>按规则软删所有分段 (规则删除时级联)</p>
     *
     * @param ruleId 评分规则 ID
     * @return 受影响行数
     */
    @Update({
        "UPDATE prcp_kpi_score_segment SET is_deleted = 1 "
        + " WHERE rule_id = #{ruleId} AND is_deleted = 0"
    })
    int softDeleteByRuleId(@Param("ruleId") Long ruleId);
}