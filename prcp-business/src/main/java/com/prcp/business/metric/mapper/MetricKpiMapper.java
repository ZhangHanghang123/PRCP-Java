package com.prcp.business.metric.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.metric.entity.MetricOption;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MetricKpiMapper extends BaseMapper<MetricOption> {}