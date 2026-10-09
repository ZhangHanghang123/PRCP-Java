package com.prcp.business.reverse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.reverse.entity.ReverseTarget;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>Mapper: prcp_reverse_target 表的 SQL 访问层</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>继承 BaseMapper 提供标准 CRUD</li>
 * </ul>
 * </p>
 *
 * <p>说明: 反算目标约束 (期望 KPI 数值, 误差容忍度), 与 prcp_reverse_scheme 1:N 关联。</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface ReverseTargetMapper extends BaseMapper<ReverseTarget> {
}
