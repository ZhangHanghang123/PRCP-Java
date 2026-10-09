package com.prcp.business.esg.controller;

import com.prcp.business.esg.service.EsgScenarioService;
import com.prcp.common.exception.BizException;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * <p>ESG 情景集 Controller (4 端点)</p>
 *
 * <p>详细说明:
 * <ul>
 *   <li>业务背景: 查询已生成的利率情景集, 支持分页、详情、统计、numpy 文件下载 (npz 格式)</li>
 *   <li>核心端点: GET /esg/scenarios (列表)、GET /esg/scenarios/{code} (详情)、GET /esg/scenarios/{code}/stats (统计)、GET /esg/scenarios/{code}/download (下载 npz)</li>
 *   <li>关联模块: EsgScenarioService</li>
 * </ul>
 * </p>
 *
 * <p>REST 前缀: {@code /esg/scenarios}</p>
 * <p>权限要求: 登录用户 (Bearer Token)</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.esg.service.EsgScenarioService
 */
@RestController
@RequestMapping("/esg/scenarios")
@RequiredArgsConstructor
public class EsgScenarioController {

    private final EsgScenarioService scenarioService;

    /**
     * <p>情景集列表 (分页, 按方案过滤)</p>
     *
     * <pre>
     * GET /esg/scenarios
     * Query: schemeId (Long, optional) - 方案 ID
     *        page     (int, default 1) - 页码
     *        pageSize (int, default 20) - 页大小
     *
     * Response: R.ok({items: [{scenario_code, scheme_id, n_scenarios, horizon_months, created_at}], total})
     * </pre>
     *
     * @param schemeId 方案 ID (可选)
     * @param page     页码 (默认 1)
     * @param pageSize 页大小 (默认 20)
     * @return R.ok(情景集列表)
     */
    @GetMapping
    public R<Map<String, Object>> listScenarios(@RequestParam(required = false) Long schemeId,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int pageSize) {
        return scenarioService.listScenarios(schemeId, page, pageSize);
    }

    /**
     * <p>单条情景详情</p>
     *
     * <pre>
     * GET /esg/scenarios/{scenarioCode}
     * Path: scenarioCode (String, required) - 情景编码
     *
     * Response: R.ok({scenario_code, scheme_id, n_scenarios, horizon_months, factors: [...]})
     * </pre>
     *
     * @param scenarioCode 情景编码
     * @return R.ok(情景详情)
     */
    @GetMapping("/{scenarioCode}")
    public R<Map<String, Object>> getScenario(@PathVariable("scenarioCode") String scenarioCode) {
        return scenarioService.getScenario(scenarioCode);
    }

    /**
     * <p>情景统计 (均值/分位数/最大最小)</p>
     *
     * <pre>
     * GET /esg/scenarios/{scenarioCode}/stats
     * Path: scenarioCode (String, required) - 情景编码
     *
     * Response: R.ok({mean, std, min, max, percentiles: {p5, p25, p50, p75, p95}})
     * </pre>
     *
     * @param scenarioCode 情景编码
     * @return R.ok(情景统计)
     */
    @GetMapping("/{scenarioCode}/stats")
    public R<Map<String, Object>> getStats(@PathVariable("scenarioCode") String scenarioCode) {
        return scenarioService.getStats(scenarioCode);
    }

    /**
     * <p>下载情景 numpy 文件 (.npz)</p>
     *
     * <pre>
     * GET /esg/scenarios/{scenarioCode}/download
     * Path: scenarioCode (String, required) - 情景编码
     *
     * Response: 二进制流 (Content-Type: application/octet-stream)
     *           文件名: scenario_{scenarioCode}.npz
     * </pre>
     *
     * @param scenarioCode 情景编码
     * @return ResponseEntity 含 .npz 二进制流
     * @throws com.prcp.common.exception.BizException 数据为空
     */
    @GetMapping("/{scenarioCode}/download")
    public ResponseEntity<byte[]> download(@PathVariable("scenarioCode") String scenarioCode) {
        byte[] data = scenarioService.downloadNumpy(scenarioCode);
        if (data == null || data.length == 0) {
            throw BizException.badRequest("scenario " + scenarioCode + " 数据为空");
        }
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        h.setContentDispositionFormData("attachment", "scenario_" + scenarioCode + ".npz");
        h.setContentLength(data.length);
        return ResponseEntity.ok().headers(h).body(data);
    }
}