package com.prcp.business.kpi.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.kpi.entity.KpiScoreSegment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface KpiScoreSegmentMapper extends BaseMapper<KpiScoreSegment> {

    @Select({
        "SELECT id, rule_id, seg_order, min_value, max_value, score, segment_desc, "
        + " is_deleted, created_at, updated_at "
        + " FROM prcp_kpi_score_segment "
        + " WHERE rule_id = #{ruleId} AND is_deleted = 0 "
        + " ORDER BY seg_order"
    })
    List<KpiScoreSegment> listByRuleId(@Param("ruleId") Long ruleId);

    @Update({
        "UPDATE prcp_kpi_score_segment SET is_deleted = 1 "
        + " WHERE rule_id = #{ruleId} AND is_deleted = 0"
    })
    int softDeleteByRuleId(@Param("ruleId") Long ruleId);
}