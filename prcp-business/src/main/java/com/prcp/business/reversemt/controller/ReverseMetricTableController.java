package com.prcp.business.reversemt.controller;

import com.prcp.business.reversemt.service.ReverseMetricTableService;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * <p>反算度量表 Controller (Reverse Metric Table)</p>
 *
 * <p>详细说明:
 * <ul>
 *   <li>业务背景: 反算输出的度量 (metric) × 时间序列查询, 用于趋势图/对比图</li>
 *   <li>核心端点: GET /reverse-metric-table (查询)、GET /reverse-metric-table/options (下拉选项)</li>
 *   <li>关联模块: ReverseMetricTableService</li>
 * </ul>
 * </p>
 *
 * <p>REST 前缀: {@code /reverse-metric-table}</p>
 * <p>权限要求: 登录用户 (Bearer Token)</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.reversemt.service.ReverseMetricTableService
 */
@RestController
@RequestMapping("/reverse-metric-table")
@RequiredArgsConstructor
public class ReverseMetricTableController {

    private final ReverseMetricTableService service;

    /**
     * <p>查询反算度量表 (按方案/度量/日期范围)</p>
     *
     * <pre>
     * GET /reverse-metric-table
     * Query: schemeCode (String, optional, default "ZXCOA_V1") - 方案编码
     *        metricCode (String, optional) - 度量编码
     *        fromDate   (yyyy-MM-dd, optional) - 起期
     *        toDate     (yyyy-MM-dd, optional) - 止期
     *
     * Response: R.ok({items: [{date, value, ...}], series: {metric_code: [...]}})
     * </pre>
     *
     * @param schemeCode 方案编码 (默认 ZXCOA_V1)
     * @param metricCode 度量编码 (可选)
     * @param fromDate   起期 yyyy-MM-dd (可选)
     * @param toDate     止期 yyyy-MM-dd (可选)
     * @return R.ok({items, series})
     */
    @GetMapping("")
    public R<Map<String, Object>> query(@RequestParam(required = false, defaultValue = "ZXCOA_V1") String schemeCode,
                                        @RequestParam(required = false) String metricCode,
                                        @RequestParam(required = false) String fromDate,
                                        @RequestParam(required = false) String toDate) {
        return R.ok(service.query(schemeCode, metricCode, fromDate, toDate));
    }

    /**
     * <p>下拉选项 (方案 + 度量)</p>
     *
     * <pre>
     * GET /reverse-metric-table/options
     * Response: R.ok({schemes: [...], metrics: [...]})
     * </pre>
     *
     * @return R.ok({schemes, metrics})
     */
    @GetMapping("/options")
    public R<Map<String, Object>> options() {
        return R.ok(Map.of(
                "schemes", service.listSchemes(),
                "metrics", service.listMetrics()
        ));
    }
}
