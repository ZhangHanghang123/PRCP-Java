package com.prcp.business.esg.controller;

import com.prcp.business.esg.service.EsgCurveService;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * <p>ESG Svensson 曲线 Controller (6 端点, 对齐 Python routers/esg.py curves 相关端点)</p>
 *
 * <p>详细说明:
 * <ul>
 *   <li>业务背景: 维护 Svensson/NSS 利率曲线的输入/输出数据 (curve_date + source + Svensson 参数), 用于 ESG 模拟的贴现/复利计算</li>
 *   <li>核心端点: GET /esg/curves (列表)、GET /esg/curves/sources (数据源下拉)、GET /esg/curves/{date} (单日)、POST /esg/curves (upsert)、POST /esg/curves/bulk (批量)、POST /esg/curves/{date}/rates (Svensson 还原)</li>
 *   <li>关联模块: EsgCurveService</li>
 * </ul>
 * </p>
 *
 * <p>REST 前缀: {@code /esg/curves}</p>
 * <p>权限要求: 登录用户 (Bearer Token)</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.esg.service.EsgCurveService
 */
@RestController
@RequestMapping("/esg/curves")
@RequiredArgsConstructor
public class EsgCurveController {

    private final EsgCurveService curveService;

    /**
     * <p>曲线列表查询 (分页 + 数据源/日期范围过滤)</p>
     *
     * <pre>
     * GET /esg/curves
     * Query: source    (String, optional) - 数据源 (ECB/FED/...)
     *        startDate (yyyy-MM-dd, optional) - 起期
     *        endDate   (yyyy-MM-dd, optional) - 止期
     *        page      (int, default 1) - 页码
     *        pageSize  (int, default 50, max 200) - 页大小
     *
     * Response: R.ok({items: [{id, curve_date, source, beta0..3, tau1, tau2}], total})
     * </pre>
     *
     * @param source    数据源 (可选)
     * @param startDate 起期 yyyy-MM-dd (可选)
     * @param endDate   止期 yyyy-MM-dd (可选)
     * @param page      页码 (默认 1)
     * @param pageSize  页大小 (默认 50, 最大 200)
     * @return R.ok({items, total})
     */
    @GetMapping
    public R<Map<String, Object>> listCurves(@RequestParam(required = false) String source,
                                              @RequestParam(required = false) String startDate,
                                              @RequestParam(required = false) String endDate,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "50") int pageSize) {
        return curveService.listCurves(source, startDate, endDate, page, Math.min(pageSize, 200));
    }

    /**
     * <p>数据源下拉 (curve sources, 用于前端 filter)</p>
     *
     * <pre>
     * GET /esg/curves/sources
     * Response: R.ok({items: ["ECB", "FED", "BOE", ...]})
     * </pre>
     *
     * @return R.ok(数据源列表)
     */
    @GetMapping("/sources")
    public R<Map<String, Object>> curveSources() {
        return curveService.curveSources();
    }

    /**
     * <p>单日曲线 (含 Svensson 6 参数 + 散点)</p>
     *
     * <pre>
     * GET /esg/curves/{curveDate}?source=ECB
     * Path:  curveDate (yyyy-MM-dd, required) - 曲线日期
     * Query: source    (String, optional) - 数据源 (默认 ECB)
     *
     * Response: R.ok({curve_date, source, beta0, beta1, beta2, beta3, tau1, tau2, points: [{term, rate}]})
     * </pre>
     *
     * @param curveDate 曲线日期 yyyy-MM-dd
     * @param source    数据源 (可选)
     * @return R.ok(单日曲线)
     */
    @GetMapping("/{curveDate}")
    public R<Map<String, Object>> getCurve(@PathVariable("curveDate") String curveDate,
                                             @RequestParam(required = false) String source) {
        return curveService.getCurve(curveDate, source);
    }

    /**
     * <p>upsert 单条曲线</p>
     *
     * <pre>
     * POST /esg/curves
     * Body: {curve_date, source, beta0, beta1, beta2, beta3, tau1, tau2, points}
     *
     * Response: R.ok(持久化结果)
     * </pre>
     *
     * @param body 曲线数据
     * @return R.ok(upsert 结果)
     */
    @PostMapping
    public R<Map<String, Object>> upsertCurve(@RequestBody Map<String, Object> body) {
        return curveService.upsertCurve(body);
    }

    /**
     * <p>批量 upsert 曲线</p>
     *
     * <pre>
     * POST /esg/curves/bulk
     * Body: {points: [{curve_date, source, beta0..3, tau1, tau2}, ...]}
     *
     * Response: R.ok({imported: N, failed: M})
     * </pre>
     *
     * @param body 含 points 列表
     * @return R.ok(批量 upsert 结果)
     */
    @PostMapping("/bulk")
    public R<Map<String, Object>> bulkUpsert(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> points = (List<Map<String, Object>>) body.get("points");
        return curveService.bulkUpsert(points);
    }

    /**
     * <p>单日还原利率 (Svensson 公式按期限还原利率点)</p>
     *
     * <pre>
     * POST /esg/curves/{curveDate}/rates
     * Path:  curveDate (yyyy-MM-dd, required) - 曲线日期
     * Body: {terms: [0.25, 0.5, 1, 2, 5, 10, 30]}
     *
     * Response: R.ok({rates: [{term, rate}, ...]})
     * </pre>
     *
     * @param curveDate 曲线日期 yyyy-MM-dd
     * @param body      含 terms 期限列表
     * @return R.ok(还原利率列表)
     */
    @PostMapping("/{curveDate}/rates")
    public R<Map<String, Object>> svenssonRates(@PathVariable("curveDate") String curveDate,
                                                  @RequestBody Map<String, Object> body) {
        return curveService.svenssonRates(curveDate, body);
    }
}