package com.prcp.business.rate.controller;

import com.prcp.business.rate.entity.RateScheme;
import com.prcp.business.rate.service.RateService;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * <p>利率曲线 Controller (Rate Schemes + Points + Fitting)</p>
 *
 * <p>详细说明:
 * <ul>
 *   <li>业务背景: 维护利率曲线方案 (Scheme) 和利率点 (Point), 支持 Svensson/NSS/NS 拟合、历史对比、按期限查询、CSV 导出</li>
 *   <li>核心端点:
 *     <ul>
 *       <li>Scheme CRUD: GET/POST/PUT/DELETE /rate/schemes</li>
 *       <li>Point CRUD: GET/POST/DELETE /rate/points</li>
 *       <li>对比: GET /rate/compare</li>
 *       <li>查点: GET /rate/lookup</li>
 *       <li>拟合: POST /rate/svensson、/rate/ns、/rate/nss</li>
 *       <li>导出: GET /rate/export</li>
 *     </ul>
 *   </li>
 *   <li>关联模块: RateService、RateScheme 实体</li>
 * </ul>
 * </p>
 *
 * <p>REST 前缀: {@code /rate}</p>
 * <p>权限要求: 登录用户 (Bearer Token)</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.rate.service.RateService
 */
@RestController
@RequestMapping("/rate")
@RequiredArgsConstructor
public class RateController {

    private final RateService rateService;

    // ============ 曲线方案 ============
    /**
     * <p>曲线方案列表</p>
     *
     * <pre>
     * GET /rate/schemes
     * Query: curveType (String, optional) - 曲线类型 (SVENSSON/NS/NSS)
     *        status    (String, optional) - 状态 (ACTIVE/INACTIVE)
     *
     * Response: R.ok({items: [{id, curve_code, curve_name, curve_type, status}, ...]})
     * </pre>
     *
     * @param curveType 曲线类型 (可选)
     * @param status    状态 (可选)
     * @return R.ok(曲线方案列表)
     */
    @GetMapping("/schemes")
    public R<Map<String, Object>> listSchemes(@RequestParam(required = false) String curveType,
                                               @RequestParam(required = false) String status) {
        return rateService.listSchemes(curveType, status);
    }

    /**
     * <p>新建曲线方案</p>
     *
     * <pre>
     * POST /rate/schemes
     * Body: RateScheme (curve_code, curve_name, curve_type, status, ...)
     *
     * Response: R.ok(新建方案)
     * </pre>
     *
     * @param s RateScheme 实体
     * @return R.ok(新建方案)
     */
    @PostMapping("/schemes")
    public R<Map<String, Object>> createScheme(@RequestBody RateScheme s) {
        return rateService.createScheme(s);
    }

    /**
     * <p>更新曲线方案</p>
     *
     * <pre>
     * PUT /rate/schemes/{id}
     * Path: id (Long, required) - 方案 ID
     * Body: RateScheme (更新字段)
     *
     * Response: R.ok(更新后方案)
     * </pre>
     *
     * @param id 方案 ID
     * @param s  RateScheme 实体
     * @return R.ok(更新后方案)
     */
    @PutMapping("/schemes/{id}")
    public R<?> updateScheme(@PathVariable Long id, @RequestBody RateScheme s) {
        return rateService.updateScheme(id, s);
    }

    /**
     * <p>软删曲线方案</p>
     *
     * <pre>
     * DELETE /rate/schemes/{id}
     * Path: id (Long, required) - 方案 ID
     *
     * Response: R.ok({deleted: N})
     * </pre>
     *
     * @param id 方案 ID
     * @return R.ok({deleted: N})
     */
    @DeleteMapping("/schemes/{id}")
    public R<?> deleteScheme(@PathVariable Long id) {
        return rateService.deleteScheme(id);
    }

    // ============ 利率点 ============
    /**
     * <p>利率点列表 (按曲线编码/数据日期过滤)</p>
     *
     * <pre>
     * GET /rate/points
     * Query: curveCode (String, optional) - 曲线编码
     *        dataDate  (yyyy-MM-dd, optional) - 数据日期
     *
     * Response: R.ok({items: [{id, curve_code, data_date, term, rate}, ...]})
     * </pre>
     *
     * @param curveCode 曲线编码 (可选)
     * @param dataDate  数据日期 yyyy-MM-dd (可选)
     * @return R.ok(利率点列表)
     */
    @GetMapping("/points")
    public R<Map<String, Object>> listPoints(@RequestParam(required = false) String curveCode,
                                              @RequestParam(required = false) String dataDate) {
        return rateService.listPoints(curveCode, dataDate);
    }

    /**
     * <p>upsert 单个利率点</p>
     *
     * <pre>
     * POST /rate/points
     * Body: {curve_code, data_date, term, rate, source}
     *
     * Response: R.ok(持久化结果)
     * </pre>
     *
     * @param body 利率点数据
     * @return R.ok(upsert 结果)
     */
    @PostMapping("/points")
    public R<Map<String, Object>> upsertPoint(@RequestBody Map<String, Object> body) {
        return rateService.upsertPoint(body);
    }

    /**
     * <p>删除单个利率点</p>
     *
     * <pre>
     * DELETE /rate/points/{id}
     * Path: id (Long, required) - 利率点 ID
     *
     * Response: R.ok({deleted: N})
     * </pre>
     *
     * @param id 利率点 ID
     * @return R.ok({deleted: N})
     */
    @DeleteMapping("/points/{id}")
    public R<?> deletePoint(@PathVariable Long id) {
        return rateService.deletePoint(id);
    }

    // ============ 历史曲线对比 ============
    /**
     * <p>历史曲线对比 (同曲线不同日期的多条利率点)</p>
     *
     * <pre>
     * GET /rate/compare
     * Query: curve_code (String, required) - 曲线编码
     *        startDate   (yyyy-MM-dd, optional) - 起期
     *        endDate     (yyyy-MM-dd, optional) - 止期
     *
     * Response: R.ok({curve_code, dates: [...], points: [{date, term, rate}]})
     * </pre>
     *
     * @param curveCode 曲线编码
     * @param startDate 起期 yyyy-MM-dd (可选)
     * @param endDate   止期 yyyy-MM-dd (可选)
     * @return R.ok(历史对比数据)
     */
    @GetMapping("/compare")
    public R<Map<String, Object>> compareCurves(@RequestParam("curve_code") String curveCode,
                                                 @RequestParam(required = false) String startDate,
                                                 @RequestParam(required = false) String endDate) {
        return rateService.compareCurves(curveCode, startDate, endDate);
    }

    // ============ 按期限点查单一利率 ============
    /**
     * <p>按期限点查单一利率</p>
     *
     * <pre>
     * GET /rate/lookup
     * Query: curve_code (String, required) - 曲线编码
     *        data_date   (yyyy-MM-dd, required) - 数据日期
     *        term        (String, required) - 期限 (如 "5Y"/"6M"/"30D")
     *
     * Response: R.ok({curve_code, data_date, term, rate})
     * </pre>
     *
     * @param curveCode 曲线编码
     * @param dataDate  数据日期 yyyy-MM-dd
     * @param term      期限
     * @return R.ok(单一利率)
     */
    @GetMapping("/lookup")
    public R<Map<String, Object>> lookupRate(@RequestParam("curve_code") String curveCode,
                                              @RequestParam("data_date") String dataDate,
                                              @RequestParam String term) {
        return rateService.lookupRate(curveCode, dataDate, term);
    }

    // ============ Svensson 拟合（6 参数：beta0~3, tau1, tau2） ============
    /**
     * <p>Svensson 拟合 (6 参数: beta0~3, tau1, tau2)</p>
     *
     * <pre>
     * POST /rate/svensson
     * Body: {curve_code, data_date, points: [{term, rate}, ...]}
     *
     * Response: R.ok({beta0, beta1, beta2, beta3, tau1, tau2, rmse, ...})
     * </pre>
     *
     * @param body 拟合输入 (曲线/日期/散点)
     * @return R.ok(Svensson 拟合结果)
     */
    @PostMapping("/svensson")
    public R<Map<String, Object>> fitSvensson(@RequestBody Map<String, Object> body) {
        return rateService.fitSvensson(body);
    }

    // ============ Nelson-Siegel 拟合（4 参数：beta0~3, tau） ============
    /**
     * <p>Nelson-Siegel 拟合 (4 参数: beta0~3, tau)</p>
     *
     * <pre>
     * POST /rate/ns
     * Body: {curve_code, data_date, points}
     *
     * Response: R.ok({beta0, beta1, beta2, beta3, tau, rmse})
     * </pre>
     *
     * @param body 拟合输入
     * @return R.ok(NS 拟合结果)
     */
    @PostMapping("/ns")
    public R<Map<String, Object>> fitNS(@RequestBody Map<String, Object> body) {
        return rateService.fitNS(body);
    }

    // ============ Nelson-Siegel-Svensson 拟合（6 参数，svensson 别名） ============
    /**
     * <p>NSS 拟合 (6 参数, svensson 的别名)</p>
     *
     * <pre>
     * POST /rate/nss
     * Body: 同 /rate/svensson
     *
     * Response: 同 /rate/svensson
     * </pre>
     *
     * @param body 拟合输入
     * @return R.ok(NSS 拟合结果)
     */
    @PostMapping("/nss")
    public R<Map<String, Object>> fitNSS(@RequestBody Map<String, Object> body) {
        return rateService.fitSvensson(body);
    }

    // ============ CSV 导出 ============
    /**
     * <p>导出利率点到 CSV</p>
     *
     * <pre>
     * GET /rate/export
     * Query: curve_code (String, required) - 曲线编码
     *        startDate   (yyyy-MM-dd, optional) - 起期
     *        endDate     (yyyy-MM-dd, optional) - 止期
     *
     * Response: 二进制流 (text/csv)
     * </pre>
     *
     * @param curveCode 曲线编码
     * @param startDate 起期 yyyy-MM-dd (可选)
     * @param endDate   止期 yyyy-MM-dd (可选)
     * @param resp      HTTP 响应对象
     * @throws java.io.IOException IO 异常
     */
    @GetMapping("/export")
    public void exportRates(@RequestParam("curve_code") String curveCode,
                             @RequestParam(required = false) String startDate,
                             @RequestParam(required = false) String endDate,
                             javax.servlet.http.HttpServletResponse resp) throws java.io.IOException {
        rateService.exportRates(curveCode, startDate, endDate, resp);
    }
}