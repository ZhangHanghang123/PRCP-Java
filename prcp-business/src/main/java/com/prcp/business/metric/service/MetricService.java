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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map;
import java.util.UUID;

/**
 * <p>指标系数 Service (Metric Coefficient)</p>
 *
 * <p>核心职责:
 * <ol>
 *   <li>查询 (按方案/节点/指标/日期过滤)</li>
 *   <li>下拉选项 (方案/节点/指标)</li>
 *   <li>CRUD (含 UUID 主键自动生成)</li>
 *   <li>Excel 批量导入</li>
 * </ol>
 * </p>
 *
 * <p>关键约定:
 * <ul>
 *   <li>主键: UUID 字符串 (自动生成)</li>
 *   <li>软删除: is_deleted=1, 不物理删除</li>
 *   <li>默认状态: status='ACTIVE'</li>
 *   <li>空串视为无过滤 (MySQL DATE 转换问题)</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.metric.mapper.MetricMapper
 * @see com.prcp.business.metric.entity.MetricCoefficient
 */
@Service
@RequiredArgsConstructor
public class MetricService {

    private final MetricMapper metricMapper;
    private final MetricKpiMapper kpiMapper;

    /**
     * <p>查询 (按方案/节点/指标/日期过滤, 空串视为无过滤)</p>
     *
     * @param schemeId   方案 ID (可选)
     * @param nodeCode   节点编码 (可选)
     * @param metricCode 指标编码 (可选)
     * @param dataDate   数据日期 yyyy-MM-dd (可选)
     * @return 行 Map 列表
     */
    public List<Map<String, Object>> query(Long schemeId, String nodeCode, String metricCode, String dataDate) {
        // 空串视为无过滤条件 → 传 null（MyBatis 预编译时 '' 无法转 DATE，会报 Incorrect DATE value: ''）
        String nc = (nodeCode == null || nodeCode.isEmpty()) ? null : nodeCode;
        String mc = (metricCode == null || metricCode.isEmpty()) ? null : metricCode;
        String dd = (dataDate == null || dataDate.isEmpty()) ? null : dataDate;
        return metricMapper.query(schemeId, nc, mc, dd);
    }

    /**
     * <p>方案下拉选项</p>
     *
     * @return 方案列表
     */
    public List<Map<String, Object>> listSchemeOptions() {
        return metricMapper.listSchemeOptions();
    }

    /**
     * <p>方案下节点下拉选项</p>
     *
     * @param schemeId 方案 ID (必填)
     * @return 节点列表
     */
    public List<Map<String, Object>> listNodeOptions(Long schemeId) {
        return metricMapper.listNodeOptions(schemeId);
    }

    /**
     * <p>指标下拉选项</p>
     *
     * @return 指标列表
     */
    public List<Map<String, Object>> listMetricOptions() {
        return metricMapper.listMetricOptions();
    }

    /**
     * <p>创建指标系数 (主键为空时自动生成 UUID, 默认 status='ACTIVE')</p>
     *
     * @param mc 指标系数实体 (schemeId/nodeId/metricCode/dataDate 必填)
     * @return 创建后的实体 (含 ID)
     */
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

    /**
     * <p>更新指标系数 (按字段选择性更新)</p>
     *
     * @param mc 指标系数实体 (id 必填; currentValue/y1Value..y5Value/unit/description/status 可选)
     * @return 更新后的实体
     */
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

    /**
     * <p>软删除指标系数 (is_deleted=1)</p>
     *
     * @param id 主键 ID (UUID, 必填)
     */
    public void delete(String id) {
        MetricCoefficient exist = metricMapper.selectById(id);
        if (exist == null) throw new BizException("记录不存在");
        exist.setIsDeleted(1);
        metricMapper.updateById(exist);
    }

    /**
     * <p>Excel 批量导入 (简化版: 读取 4 列 [nodeCode, metricCode, dataDate, value])</p>
     *
     * <p>对齐 Python routers/metric_coefficient.py import_excel</p>
     *
     * @param file 上传的 Excel 文件 (必填)
     * @return R.ok(Map.of("imported"/"skipped"/"errors", ...))
     */
    public Map<String, Object> importExcel(org.springframework.web.multipart.MultipartFile file) {
        Map<String, Object> resp = new LinkedHashMap<>();
        final int[] counter = {0, 0};  // [imported, skipped]
        final java.util.List<Map<String, Object>> errors = new java.util.ArrayList<>();
        try {
            com.alibaba.excel.EasyExcel.read(file.getInputStream(), new com.alibaba.excel.read.listener.ReadListener<java.util.List<Object>>() {
                @Override
                public void invoke(java.util.List<Object> row, com.alibaba.excel.context.AnalysisContext ctx) {
                    try {
                        String nodeCode = row.size() > 0 && row.get(0) != null ? row.get(0).toString() : null;
                        String metricCode = row.size() > 1 && row.get(1) != null ? row.get(1).toString() : null;
                        String dataDate = row.size() > 2 && row.get(2) != null ? row.get(2).toString() : null;
                        Double value = row.size() > 3 && row.get(3) != null ? Double.parseDouble(row.get(3).toString()) : null;
                        if (nodeCode == null || metricCode == null || dataDate == null) { counter[1]++; return; }
                        MetricCoefficient mc = new MetricCoefficient();
                        mc.setCurrentValue(java.math.BigDecimal.valueOf(value == null ? 0 : value));
                        try { mc.setDataDate(java.time.LocalDate.parse(dataDate)); } catch (Exception ignored) {}
                        try { metricMapper.insert(mc); counter[0]++; } catch (Exception e) { counter[1]++; }
                    } catch (Exception e) {
                        Map<String, Object> err = new LinkedHashMap<>();
                        err.put("message", e.getMessage());
                        errors.add(err);
                        counter[1]++;
                    }
                }
                @Override
                public void doAfterAllAnalysed(com.alibaba.excel.context.AnalysisContext ctx) { }
            }).sheet().doRead();
        } catch (Exception e) {
            throw new BizException("导入失败：" + e.getMessage());
        }
        resp.put("imported", counter[0]);
        resp.put("skipped", counter[1]);
        resp.put("errors", errors);
        return resp;
    }
}
