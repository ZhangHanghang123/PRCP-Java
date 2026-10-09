package com.prcp.business.metric.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.metric.entity.MetricOption;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>Mapper: 指标域下的 MetricOption 实体 CRUD 访问层</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>继承 BaseMapper 提供标准 CRUD</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface MetricKpiMapper extends BaseMapper<MetricOption> {}