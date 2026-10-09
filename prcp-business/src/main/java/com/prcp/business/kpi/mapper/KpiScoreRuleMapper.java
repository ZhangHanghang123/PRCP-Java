package com.prcp.business.kpi.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.kpi.entity.KpiScoreRule;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>Mapper: prcp_kpi_score_rule 表的 SQL 访问层</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>继承 BaseMapper 提供标准 CRUD</li>
 *   <li>复杂列表查询 (JOIN scheme+definition) 见 {@link com.prcp.business.kpi.mapper.KpiMapper#listScoreRules}</li>
 *   <li>分段子表见 {@link com.prcp.business.kpi.mapper.KpiScoreSegmentMapper}</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface KpiScoreRuleMapper extends BaseMapper<KpiScoreRule> {
}