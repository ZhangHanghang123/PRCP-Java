package com.prcp.business.engines;

import com.prcp.common.exception.BizException;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 引擎统一入口（对位 Python app.routers.engines）
 *
 * <p>API：
 * <pre>
 *   POST /sim/schemes/{sid}/run    触发引擎
 *   GET  /sim/runs/{rid}           查询单次执行
 *   GET  /sim/runs                 列出执行历史
 *   GET  /sim/results              结果快照
 * </pre>
 *
 * <p>对齐 Python：{@code backend/app/routers/engines.py}
 *
 * @author WorkBuddy Agent
 * @date 2026-09-26
 */
@Slf4j
@RestController
@RequestMapping("/sim")
@RequiredArgsConstructor
public class EngineController {

    private static final String DEFAULT_ENGINE_TYPE = "new_business";

    private final EngineRegistry registry;
    private final DataSource dataSource;

    private EngineBase engineOf(String engineType) {
        try {
            return registry.getEngine(engineType == null ? DEFAULT_ENGINE_TYPE : engineType);
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest(e.getMessage());
        }
    }

    /**
     * 1. 触发引擎执行
     * POST /sim/schemes/{sid}/run?engineType=new_business&monthCount=60
     */
    @PostMapping("/schemes/{sid}/run")
    public R<Map<String, Object>> run(
            @PathVariable Long sid,
            @RequestParam(name = "engineType", required = false, defaultValue = DEFAULT_ENGINE_TYPE) String engineType,
            @RequestParam(name = "monthCount", required = false, defaultValue = "60") Integer monthCount) {

        EngineBase engine = engineOf(engineType);
        EngineContext ctx = new EngineContext(dataSource);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("monthCount", monthCount);

        Long uid = 1L;
        try {
            Long runId = engine.run(ctx, sid, uid, params);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", true);
            resp.put("run_id", runId);
            resp.put("engine_type", engineType);
            resp.put("engine_name", engine.getEngineName());
            resp.put("month_count", monthCount);
            resp.put("status", "SUCCESS");
            return R.ok(resp);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("引擎执行失败: schemeId={}, engineType={}", sid, engineType, e);
            throw BizException.badRequest("引擎执行失败: " + e.getMessage());
        }
    }

    /**
     * 2. 查询单次执行状态
     * GET /sim/runs/{rid}?engineType=new_business
     */
    @GetMapping("/runs/{rid}")
    public R<Map<String, Object>> getRun(
            @PathVariable Long rid,
            @RequestParam(name = "engineType", required = false, defaultValue = DEFAULT_ENGINE_TYPE) String engineType) {

        EngineBase engine = engineOf(engineType);
        EngineContext ctx = new EngineContext(dataSource);
        Map<String, Object> result = engine.getRun(ctx, rid);
        if (result == null) {
            throw BizException.notFound("Run 不存在: " + rid);
        }
        return R.ok(result);
    }

    /**
     * 3. 列出执行历史
     * GET /sim/runs?simSchemeId=&simSchemeCode=&status=&limit=20
     */
    @GetMapping("/runs")
    public R<Map<String, Object>> listRuns(
            @RequestParam(name = "engineType", required = false, defaultValue = DEFAULT_ENGINE_TYPE) String engineType,
            @RequestParam(name = "simSchemeId", required = false) Long simSchemeId,
            @RequestParam(name = "simSchemeCode", required = false) String simSchemeCode,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "limit", required = false, defaultValue = "20") Integer limit) {

        EngineBase engine = engineOf(engineType);
        EngineContext ctx = new EngineContext(dataSource);

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("simSchemeId", simSchemeId);
        filters.put("simSchemeCode", simSchemeCode);
        filters.put("status", status);
        filters.put("limit", limit);

        List<Map<String, Object>> items = engine.listRuns(ctx, filters);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        resp.put("engine_type", engineType);
        return R.ok(resp);
    }

    /**
     * 4. 查询结果快照
     * GET /sim/results?simSchemeCode=&runId=&dateOffset=&coaNodeId=&category=&withBuckets=false
     */
    @GetMapping("/results")
    public R<Map<String, Object>> listResults(
            @RequestParam(name = "engineType", required = false, defaultValue = DEFAULT_ENGINE_TYPE) String engineType,
            @RequestParam(name = "simSchemeCode", required = false) String simSchemeCode,
            @RequestParam(name = "runId", required = false) Long runId,
            @RequestParam(name = "dateOffset", required = false) Integer dateOffset,
            @RequestParam(name = "coaNodeId", required = false) Long coaNodeId,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "withBuckets", required = false, defaultValue = "false") Boolean withBuckets) {

        EngineBase engine = engineOf(engineType);
        EngineContext ctx = new EngineContext(dataSource);

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("simSchemeCode", simSchemeCode);
        filters.put("runId", runId);
        filters.put("dateOffset", dateOffset);
        filters.put("coaNodeId", coaNodeId);
        filters.put("category", category);
        filters.put("withBuckets", withBuckets);

        List<Map<String, Object>> items = engine.listResults(ctx, filters);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        resp.put("engine_type", engineType);
        return R.ok(resp);
    }
}
