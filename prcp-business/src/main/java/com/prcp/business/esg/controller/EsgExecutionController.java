package com.prcp.business.esg.controller;

import com.prcp.business.esg.service.EsgExecutionService;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * <p>ESG 执行 Controller (5 端点, 对齐 Python routers/esg.py)</p>
 *
 * <p>详细说明:
 * <ul>
 *   <li>业务背景: ESG (经济情景生成) 的核心执行流: PCA 拟合 → HJM 模型生成 → 情景生成 → 一键全跑; case/run 是单条情景的快速跑</li>
 *   <li>核心端点: POST /esg/schemes/{id}/fit-pca、POST /esg/schemes/{id}/generate-hjm、POST /esg/schemes/{id}/generate、POST /esg/schemes/{id}/run-all、POST /esg/case/run</li>
 *   <li>关联模块: EsgExecutionService</li>
 * </ul>
 * </p>
 *
 * <p>REST 前缀: {@code /esg} (与 EsgSchemeController / EsgRunController 共用)</p>
 * <p>权限要求: 登录用户 (Bearer Token)</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.esg.service.EsgExecutionService
 */
@RestController
@RequestMapping("/esg")
@RequiredArgsConstructor
public class EsgExecutionController {

    private final EsgExecutionService executionService;

    /**
     * <p>PCA 拟合 (从历史曲线中提取主成分)</p>
     *
     * <pre>
     * POST /esg/schemes/{id}/fit-pca
     * Path: id (Long, required) - 方案 ID
     * Body: 可选 {n_components, curve_source, ...}
     *
     * Response: R.ok({pca_id, n_components, explained_variance, ...})
     * </pre>
     *
     * @param id   方案 ID
     * @param body 拟合参数 (可选)
     * @return R.ok(PCA 拟合结果)
     */
    @PostMapping("/schemes/{id}/fit-pca")
    public R<Map<String, Object>> fitPca(@PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
        return executionService.fitPca(id, body != null ? body : Map.of());
    }

    /**
     * <p>HJM 模型生成 (基于 PCA 生成 Heath-Jarrow-Morton 利率模型参数)</p>
     *
     * <pre>
     * POST /esg/schemes/{id}/generate-hjm
     * Path: id (Long, required) - 方案 ID
     * Body: 可选 {volatility_method, mean_reversion, ...}
     *
     * Response: R.ok({hjm_id, factors: [...]})
     * </pre>
     *
     * @param id   方案 ID
     * @param body HJM 参数 (可选)
     * @return R.ok(HJM 生成结果)
     */
    @PostMapping("/schemes/{id}/generate-hjm")
    public R<Map<String, Object>> generateHjm(@PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
        return executionService.generateHjm(id, body != null ? body : Map.of());
    }

    /**
     * <p>情景生成 (基于 HJM 生成 N 条未来利率路径)</p>
     *
     * <pre>
     * POST /esg/schemes/{id}/generate
     * Path: id (Long, required) - 方案 ID
     * Body: 可选 {n_scenarios, horizon_months, seed, ...}
     *
     * Response: R.ok({scenario_codes: [...], count})
     * </pre>
     *
     * @param id   方案 ID
     * @param body 情景生成参数 (可选)
     * @return R.ok(情景生成结果)
     */
    @PostMapping("/schemes/{id}/generate")
    public R<Map<String, Object>> generateScenarios(@PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
        return executionService.generateScenarios(id, body != null ? body : Map.of());
    }

    /**
     * <p>一键全跑 (fit-pca + generate-hjm + generate 串联)</p>
     *
     * <pre>
     * POST /esg/schemes/{id}/run-all
     * Path: id (Long, required) - 方案 ID
     *
     * Response: R.ok({run_id, steps: [fit-pca, generate-hjm, generate], status})
     * </pre>
     *
     * @param id 方案 ID
     * @return R.ok(全流程结果)
     */
    @PostMapping("/schemes/{id}/run-all")
    public R<Map<String, Object>> runAll(@PathVariable("id") Long id) {
        return executionService.runAll(id);
    }

    /**
     * <p>单条情景快速跑 (case/run)</p>
     *
     * <pre>
     * POST /esg/case/run
     * Body: {scheme_id, scenario_code, ...}
     *
     * Response: R.ok({case_id, scenario_code, status})
     * </pre>
     *
     * @param body 跑情景参数
     * @return R.ok(单条情景结果)
     */
    @PostMapping("/case/run")
    public R<Map<String, Object>> caseRun(@RequestBody(required = false) Map<String, Object> body) {
        return executionService.caseRun(body != null ? body : Map.of());
    }
}