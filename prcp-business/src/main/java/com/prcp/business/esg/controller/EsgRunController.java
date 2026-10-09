package com.prcp.business.esg.controller;

import com.prcp.business.esg.service.EsgRunService;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * <p>ESG 运行历史 Controller (3 端点)</p>
 *
 * <p>详细说明:
 * <ul>
 *   <li>业务背景: ESG 方案/全局的执行历史查询, 用于排查跑批失败/查进度</li>
 *   <li>核心端点: GET /esg/schemes/{schemeId}/runs (方案级历史)、GET /esg/runs (全局历史)、GET /esg/runs/{id} (单条详情)</li>
 *   <li>关联模块: EsgRunService</li>
 * </ul>
 * </p>
 *
 * <p>REST 前缀: {@code /esg} (与 EsgSchemeController / EsgExecutionController 共用)</p>
 * <p>权限要求: 登录用户 (Bearer Token)</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.esg.service.EsgRunService
 */
@RestController
@RequestMapping("/esg")
@RequiredArgsConstructor
public class EsgRunController {

    private final EsgRunService runService;

    /**
     * <p>方案级 run 历史</p>
     *
     * <pre>
     * GET /esg/schemes/{schemeId}/runs
     * Path:  schemeId (Long, required) - 方案 ID
     * Query: runType   (String, optional) - 运行类型 (FIT_PCA/GENERATE_HJM/GENERATE/RUN_ALL)
     *        status    (String, optional) - 状态 (RUNNING/SUCCESS/FAILED)
     *        limit     (int, default 50) - 最大条数
     *
     * Response: R.ok({items: [{id, run_type, status, started_at, finished_at}]})
     * </pre>
     *
     * @param schemeId 方案 ID
     * @param runType  运行类型 (可选)
     * @param status   状态 (可选)
     * @param limit    最大条数 (默认 50)
     * @return R.ok(方案级 run 历史)
     */
    @GetMapping("/schemes/{schemeId}/runs")
    public R<Map<String, Object>> listByScheme(@PathVariable("schemeId") Long schemeId,
                                                 @RequestParam(required = false) String runType,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(defaultValue = "50") int limit) {
        return runService.listByScheme(schemeId, runType, status, limit);
    }

    /**
     * <p>全局 run 历史 (含分页)</p>
     *
     * <pre>
     * GET /esg/runs
     * Query: schemeId (Long, optional) - 方案 ID
     *        runType  (String, optional) - 运行类型
     *        status   (String, optional) - 状态
     *        page     (int, default 1) - 页码
     *        pageSize (int, default 50, max 200) - 页大小
     *
     * Response: R.ok({items, total, page, pageSize})
     * </pre>
     *
     * @param schemeId 方案 ID (可选)
     * @param runType  运行类型 (可选)
     * @param status   状态 (可选)
     * @param page     页码 (默认 1)
     * @param pageSize 页大小 (默认 50, 最大 200)
     * @return R.ok(全局 run 历史)
     */
    @GetMapping("/runs")
    public R<Map<String, Object>> listAll(@RequestParam(required = false) Long schemeId,
                                           @RequestParam(required = false) String runType,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "50") int pageSize) {
        return runService.listAll(schemeId, runType, status, page, Math.min(pageSize, 200));
    }

    /**
     * <p>单 run 详情</p>
     *
     * <pre>
     * GET /esg/runs/{id}
     * Path: id (Long, required) - run ID
     *
     * Response: R.ok({id, scheme_id, run_type, status, started_at, finished_at, params, result_summary})
     * </pre>
     *
     * @param id run ID
     * @return R.ok(run 详情)
     */
    @GetMapping("/runs/{id}")
    public R<Map<String, Object>> getRun(@PathVariable("id") Long id) {
        return runService.getRun(id);
    }
}