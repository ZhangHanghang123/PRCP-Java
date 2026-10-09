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
 * <p>引擎统一入口 Controller (对位 Python app/routers/engines.py)</p>
 *
 * <p>详细说明:
 * <ul>
 *   <li>业务背景: 调度不同引擎 (new_business / 未来更多) 执行模拟方案, 通过 EngineRegistry 解析 + 路由</li>
 *   <li>核心端点: POST /sim/schemes/{sid}/run (触发)、GET /sim/runs/{rid} (查询单次)、GET /sim/runs (历史)、GET /sim/results (快照)</li>
 *   <li>关联模块: EngineRegistry / EngineBase / EngineContext</li>
 * </ul>
 * </p>
 *
 * <p>REST 前缀: {@code /sim} (与 SimController 共用)</p>
 * <p>权限要求: 登录用户 (Bearer Token), 详见 SecurityConfig</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.engines.EngineRegistry
 * @see com.prcp.business.engines.EngineBase
 */
@Slf4j
@RestController
@RequestMapping("/sim")
@RequiredArgsConstructor
public class EngineController {

    private static final String DEFAULT_ENGINE_TYPE = "new_business";

    private final EngineRegistry registry;
    private final DataSource dataSource;

    /**
     * <p>根据 engineType 解析引擎实例 (默认 new_business), 异常时包装为 BizException</p>
     *
     * @param engineType 引擎类型 (可为 null)
     * @return 引擎实例
     */
    private EngineBase engineOf(String engineType) {
        try {
            return registry.getEngine(engineType == null ? DEFAULT_ENGINE_TYPE : engineType);
        } catch (IllegalArgumentException e) {
            throw BizException.badRequest(e.getMessage());
        }
    }

    /**
     * <p>1. 触发引擎执行</p>
     *
     * <pre>
     * POST /sim/schemes/{sid}/run
     * Path:  sid        (Long, required) - 方案 ID
     * Query: engineType (String, optional, default "new_business") - 引擎类型
     *        monthCount (Integer, optional, default 60) - 模拟月数
     *
     * Response: R.ok({ok, run_id, engine_type, engine_name, month_count, status})
     *   run_id      - 运行 ID (用于查询状态/结果)
     *   engine_name - 引擎显示名
     *   status      - "SUCCESS" / "FAILED"
     * </pre>
     *
     * @param sid        方案 ID
     * @param engineType 引擎类型 (默认 new_business)
     * @param monthCount 模拟月数 (默认 60)
     * @return R.ok({run_id, status, ...})
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
     * <p>2. 查询单次执行状态</p>
     *
     * <pre>
     * GET /sim/runs/{rid}
     * Path:  rid        (Long, required) - 运行 ID
     * Query: engineType (String, optional, default "new_business") - 引擎类型
     *
     * Response: R.ok({run_id, sim_scheme_id, status, started_at, finished_at, ...})
     * </pre>
     *
     * @param rid        运行 ID
     * @param engineType 引擎类型 (默认 new_business)
     * @return R.ok(运行详情)
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
     * <p>3. 列出执行历史</p>
     *
     * <pre>
     * GET /sim/runs
     * Query: engineType    (String, optional, default "new_business") - 引擎类型
     *        simSchemeId   (Long, optional) - 模拟方案 ID
     *        simSchemeCode (String, optional) - 模拟方案编码
     *        status        (String, optional) - 状态过滤
     *        limit         (Integer, optional, default 20) - 最大条数
     *
     * Response: R.ok({items: [{run_id, sim_scheme_id, status, started_at}], engine_type})
     * </pre>
     *
     * @param engineType    引擎类型
     * @param simSchemeId   模拟方案 ID (可选)
     * @param simSchemeCode 模拟方案编码 (可选)
     * @param status        状态过滤 (可选)
     * @param limit         最大条数 (默认 20)
     * @return R.ok({items, engine_type})
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
     * <p>4. 查询结果快照</p>
     *
     * <pre>
     * GET /sim/results
     * Query: engineType    (String, optional, default "new_business") - 引擎类型
     *        simSchemeCode (String, optional) - 模拟方案编码
     *        runId         (Long, optional) - 运行 ID
     *        dateOffset    (Integer, optional) - 日期偏移量
     *        coaNodeId     (Long, optional) - 账户册节点 ID
     *        category      (String, optional) - 类别
     *        withBuckets   (Boolean, optional, default false) - 是否含 64 桶明细
     *
     * Response: R.ok({items: [{sim_scheme_code, run_id, date_offset, coa_node_id, category, metrics}], engine_type})
     * </pre>
     *
     * @param engineType    引擎类型
     * @param simSchemeCode 模拟方案编码 (可选)
     * @param runId         运行 ID (可选)
     * @param dateOffset    日期偏移量 (可选)
     * @param coaNodeId     账户册节点 ID (可选)
     * @param category      类别 (可选)
     * @param withBuckets   是否含 64 桶明细 (默认 false)
     * @return R.ok({items, engine_type})
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
