package com.prcp.business.metric.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.prcp.business.metric.entity.MetricCoefficient;
import com.prcp.business.metric.mapper.MetricMapper;
import com.prcp.business.metric.mapper.MetricKpiMapper;
import com.prcp.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MetricService {

    private final MetricMapper metricMapper;
    private final MetricKpiMapper kpiMapper;

    public List<Map<String, Object>> query(Long schemeId, String nodeCode, String metricCode, String dataDate) {
        // 空串视为无过滤条件 → 传 null（MyBatis 预编译时 '' 无法转 DATE，会报 Incorrect DATE value: ''）
        String nc = (nodeCode == null || nodeCode.isEmpty()) ? null : nodeCode;
        String mc = (metricCode == null || metricCode.isEmpty()) ? null : metricCode;
        String dd = (dataDate == null || dataDate.isEmpty()) ? null : dataDate;
        return metricMapper.query(schemeId, nc, mc, dd);
    }

    public List<Map<String, Object>> listSchemeOptions() {
        return metricMapper.listSchemeOptions();
    }

    public List<Map<String, Object>> listNodeOptions(Long schemeId) {
        return metricMapper.listNodeOptions(schemeId);
    }

    public List<Map<String, Object>> listMetricOptions() {
        return metricMapper.listMetricOptions();
    }

    public MetricCoefficient create(MetricCoefficient mc) {
        if (mc.getSchemeId() == null) throw new BizException("schemeId 必填");
        if (mc.getNodeId() == null) throw new BizException("nodeId 必填");
        if (mc.getMetricCode() == null) throw new BizException("metricCode 必填");
        if (mc.getDataDate() == null) throw new BizException("dataDate 必填");
        if (mc.getId() == null || mc.getId().isEmpty()) mc.setId(UUID.randomUUID().toString());
        mc.setIsDeleted(0);
        mc.setStatus(mc.getStatus() == null ? "ACTIVE" : mc.getStatus());
        metricMapper.insert(mc);
        return mc;
    }

    public MetricCoefficient update(MetricCoefficient mc) {
        if (mc.getId() == null) throw new BizException("id 不能为空");
        MetricCoefficient exist = metricMapper.selectById(mc.getId());
        if (exist == null) throw new BizException("记录不存在");
        if (mc.getCurrentValue() != null) exist.setCurrentValue(mc.getCurrentValue());
        if (mc.getY1Value() != null) exist.setY1Value(mc.getY1Value());
        if (mc.getY2Value() != null) exist.setY2Value(mc.getY2Value());
        if (mc.getY3Value() != null) exist.setY3Value(mc.getY3Value());
        if (mc.getY4Value() != null) exist.setY4Value(mc.getY4Value());
        if (mc.getY5Value() != null) exist.setY5Value(mc.getY5Value());
        if (mc.getUnit() != null) exist.setUnit(mc.getUnit());
        if (mc.getDescription() != null) exist.setDescription(mc.getDescription());
        if (mc.getStatus() != null) exist.setStatus(mc.getStatus());
        metricMapper.updateById(exist);
        return exist;
    }

    public void delete(String id) {
        MetricCoefficient exist = metricMapper.selectById(id);
        if (exist == null) throw new BizException("记录不存在");
        exist.setIsDeleted(1);
        metricMapper.updateById(exist);
    }
}
