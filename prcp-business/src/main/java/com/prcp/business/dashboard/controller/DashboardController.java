package com.prcp.business.dashboard.controller;

import com.prcp.business.dashboard.service.DashboardService;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * <p>主驾驶舱 Controller (PRD 风格 Dashboard)</p>
 *
 * <p>详细说明:
 * <ul>
 *   <li>业务背景: 系统首页仪表盘, 概览基础数据量、KPI 趋势、方案分布、TOP KPI 等核心指标</li>
 *   <li>核心端点: overview (概览)、kpi-trend (KPI 趋势)、scheme-distribution (方案分布)、top-kpis (TOP KPI)、reverse-overview (反算驾驶舱)</li>
 *   <li>关联模块: DashboardService</li>
 * </ul>
 * </p>
 *
 * <p>REST 前缀: {@code /dashboard}</p>
 * <p>权限要求: 登录用户 (Bearer Token)</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.dashboard.service.DashboardService
 */
@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * <p>首页总览 (数据总量、方案数量、节点数量、最近运行)</p>
     *
     * <pre>
     * GET /dashboard/overview
     * Response: R.ok({data_total, scheme_count, node_count, last_run, ...})
     * </pre>
     *
     * @return R.ok(总览指标)
     */
    @GetMapping("/overview")
    public R<Map<String, Object>> overview() { return dashboardService.overview(); }

    /**
     * <p>KPI 趋势 (近 N 天)</p>
     *
     * <pre>
     * GET /dashboard/kpi-trend?days=14
     * Query: days (int, default 14) - 天数
     *
     * Response: R.ok({dates: [...], series: {kpi_code: [values]}})
     * </pre>
     *
     * @param days 天数 (默认 14)
     * @return R.ok(KPI 趋势数据)
     */
    @GetMapping("/kpi-trend")
    public R<Map<String, Object>> kpiTrend(@RequestParam(defaultValue = "14") int days) {
        return dashboardService.kpiTrend(days);
    }

    /**
     * <p>方案分布 (按方案汇总数据量/节点数)</p>
     *
     * <pre>
     * GET /dashboard/scheme-distribution
     * Response: R.ok({items: [{scheme_id, scheme_name, data_count, node_count}, ...]})
     * </pre>
     *
     * @return R.ok(方案分布)
     */
    @GetMapping("/scheme-distribution")
    public R<Map<String, Object>> schemeDistribution() { return dashboardService.schemeDistribution(); }

    /**
     * <p>TOP KPI (按权重/曝光排序的指标卡片)</p>
     *
     * <pre>
     * GET /dashboard/top-kpis
     * Response: R.ok({items: [{kpi_code, kpi_name, value, change}, ...]})
     * </pre>
     *
     * @return R.ok(TOP KPI 列表)
     */
    @GetMapping("/top-kpis")
    public R<Map<String, Object>> topKpis() { return dashboardService.topKpis(); }

    /**
     * <p>反算驾驶舱 (PRD 风格 9 KPI + 24 月趋势 + 大类分布)</p>
     *
     * <pre>
     * GET /dashboard/reverse-overview
     * Query: data_date (yyyy-MM-dd, optional) - 数据日期
     *        scheme_id (Long, optional) - 方案 ID
     *        run_id    (Long, optional) - 运行 ID
     *
     * Response: R.ok({kpis: [9个KPI], trend: [24月], categories: ...})
     * </pre>
     *
     * @param data_date 数据日期 yyyy-MM-dd (可选)
     * @param scheme_id 方案 ID (可选)
     * @param run_id    运行 ID (可选)
     * @return R.ok(反算驾驶舱数据)
     */
    @GetMapping("/reverse-overview")
    public R<Map<String, Object>> reverseOverview(
            @RequestParam(required = false) String data_date,
            @RequestParam(required = false) Long scheme_id,
            @RequestParam(required = false) Long run_id) {
        return dashboardService.reverseOverview(data_date);
    }
}