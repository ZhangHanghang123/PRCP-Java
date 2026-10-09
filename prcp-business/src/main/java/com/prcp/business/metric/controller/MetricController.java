package com.prcp.business.metric.controller;

import com.prcp.business.metric.entity.MetricCoefficient;
import com.prcp.business.metric.service.MetricService;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * <p>度量系数 Controller (Metric Coefficient)</p>
 *
 * <p>详细说明:
 * <ul>
 *   <li>业务背景: 度量 (KPI / 比率 / 转化因子) 在不同节点/方案/日期的系数维护, 用于新业务模拟和报表计算</li>
 *   <li>核心端点:
 *     <ul>
 *       <li>GET /metric-coefficient — 列表 (scheme/node/metric/date 过滤)</li>
 *       <li>GET /metric-coefficient/options — 下拉选项</li>
 *       <li>POST/PUT/DELETE /metric-coefficient — CRUD</li>
 *       <li>POST /metric-coefficient/import — Excel 批量导入</li>
 *     </ul>
 *   </li>
 *   <li>关联模块: MetricService、MetricCoefficient 实体</li>
 * </ul>
 * </p>
 *
 * <p>REST 前缀: {@code /metric-coefficient}</p>
 * <p>权限要求: 登录用户 (Bearer Token)</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.metric.service.MetricService
 */
@RestController
@RequestMapping("/metric-coefficient")
@RequiredArgsConstructor
public class MetricController {

    private final MetricService metricService;

    /**
     * <p>度量系数列表</p>
     *
     * <pre>
     * GET /metric-coefficient
     * Query: schemeId   (Long, optional) - 方案 ID
     *        nodeCode   (String, optional) - 节点编码
     *        metricCode (String, optional) - 度量编码
     *        dataDate   (yyyy-MM-dd, optional) - 数据日期
     *
     * Response: R.ok(List&lt;Map&gt;) 度量系数列表
     * </pre>
     *
     * @param schemeId   方案 ID (可选)
     * @param nodeCode   节点编码 (可选)
     * @param metricCode 度量编码 (可选)
     * @param dataDate   数据日期 yyyy-MM-dd (可选)
     * @return R.ok(度量系数列表)
     */
    @GetMapping("")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) Long schemeId,
                                              @RequestParam(required = false) String nodeCode,
                                              @RequestParam(required = false) String metricCode,
                                              @RequestParam(required = false) String dataDate) {
        return R.ok(metricService.query(schemeId, nodeCode, metricCode, dataDate));
    }

    /**
     * <p>下拉选项 (方案 + 节点 + 度量)</p>
     *
     * <pre>
     * GET /metric-coefficient/options
     * Query: schemeId (String, optional) - 方案 ID (兼容 "null" 字符串)
     *
     * Response: R.ok({schemes: [...], nodes: [...], metrics: [...]})
     * </pre>
     *
     * @param schemeId 方案 ID 字符串 (可选, 兼容 "null")
     * @return R.ok({schemes, nodes, metrics})
     */
    @GetMapping("/options")
    public R<Map<String, Object>> options(@RequestParam(required = false) String schemeId) {
        // 兼容 axios 把 null 序列化成 "null" 的情况
        Long sid = null;
        if (schemeId != null && !"null".equalsIgnoreCase(schemeId) && !schemeId.isEmpty()) {
            try { sid = Long.parseLong(schemeId); } catch (NumberFormatException ignore) {}
        }
        return R.ok(Map.of(
                "schemes", metricService.listSchemeOptions(),
                "nodes", metricService.listNodeOptions(sid),
                "metrics", metricService.listMetricOptions()
        ));
    }

    /**
     * <p>新增度量系数</p>
     *
     * <pre>
     * POST /metric-coefficient
     * Body: MetricCoefficient (schemeId, nodeCode, metricCode, dataDate, value, ...)
     *
     * Response: R.ok(MetricCoefficient) 新建的度量系数
     * </pre>
     *
     * @param mc 度量系数实体
     * @return R.ok(新建的 MetricCoefficient)
     */
    @PostMapping("")
    public R<MetricCoefficient> create(@RequestBody MetricCoefficient mc) {
        return R.ok(metricService.create(mc));
    }

    /**
     * <p>更新度量系数</p>
     *
     * <pre>
     * PUT /metric-coefficient/{id}
     * Path: id (String, required) - 复合主键 {scheme_code}_{node_code}_{YYYYMMDD}_{metric_code}
     * Body: MetricCoefficient (更新字段)
     *
     * Response: R.ok(MetricCoefficient) 更新后度量系数
     * </pre>
     *
     * @param id 复合主键 ID
     * @param mc 度量系数实体
     * @return R.ok(更新后 MetricCoefficient)
     */
    @PutMapping("/{id}")
    public R<MetricCoefficient> update(@PathVariable String id, @RequestBody MetricCoefficient mc) {
        mc.setId(id);
        return R.ok(metricService.update(mc));
    }

    /**
     * <p>软删度量系数</p>
     *
     * <pre>
     * DELETE /metric-coefficient/{id}
     * Path: id (String, required) - 复合主键 ID
     *
     * Response: R.ok()
     * </pre>
     *
     * @param id 复合主键 ID
     * @return R.ok()
     */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        metricService.delete(id);
        return R.ok();
    }

    /**
     * <p>Excel 批量导入度量系数 (对齐 Python routers/metric_coefficient.py import_excel)</p>
     *
     * <pre>
     * POST /metric-coefficient/import
     * Form: file (MultipartFile) - xlsx 文件
     *
     * Response: R.ok({ok, imported, skipped, errors: [{row, message}]})
     * </pre>
     *
     * @param file 上传的 xlsx 文件
     * @return R.ok({ok, imported, skipped, errors})
     */
    @PostMapping("/import")
    public R<Map<String, Object>> importExcel(@RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        return R.ok(metricService.importExcel(file));
    }
}
