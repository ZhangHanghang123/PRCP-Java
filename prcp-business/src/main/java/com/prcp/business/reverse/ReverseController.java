package com.prcp.business.reverse;

import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>反算 Controller (18 端点, 对位 Python /reverse prefix)</p>
 *
 * <p>详细说明:
 * <ul>
 *   <li>业务背景: 反算 (Reverse Engineering) 是银行预测核心, 从目标 KPI 反推月度业务数据; 三层架构: Scheme → Target → Run</li>
 *   <li>核心端点:
 *     <ul>
     *       <li>Scheme CRUD: 5 端点 (list/create/get/update/delete)</li>
     *       <li>Target CRUD: 4 端点 (list/create/update/delete)</li>
     *       <li>Run CRUD: 8 端点 (list/create/start/cancel/delete/logs/result)</li>
     *       <li>辅助: GET /reverse/model-options、GET /reverse/kpi-options、GET /reverse/algorithms</li>
     *     </ul>
 *   </li>
 *   <li>关联模块: ReverseService</li>
 * </ul>
 * </p>
 *
 * <p>REST 前缀: {@code /reverse}</p>
 * <p>权限要求: 登录用户 (Bearer Token)</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.reverse.ReverseService
 */
@RestController
@RequestMapping("/reverse")
@RequiredArgsConstructor
public class ReverseController {

    private final ReverseService svc;

    // ========== Scheme CRUD (5) ==========
    /**
     * <p>反算方案列表</p>
     *
     * <pre>
     * GET /reverse/schemes
     * Query: keyword (String, optional) - 模糊搜索
     *        status  (String, optional) - 状态 (DRAFT/ACTIVE/ARCHIVED)
     *
     * Response: R.ok({items: [{id, scheme_code, scheme_name, status}]})
     * </pre>
     *
     * @param keyword 模糊搜索关键字 (可选)
     * @param status  状态 (可选)
     * @return R.ok(方案列表)
     */
    @GetMapping("/schemes")
    public R<Map<String, Object>> listSchemes(@RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String status) {
        return svc.listSchemes(keyword, status);
    }

    /**
     * <p>新建反算方案</p>
     *
     * <pre>
     * POST /reverse/schemes
     * Body: {scheme_code, scheme_name, status, ...}
     *
     * Response: R.ok(新建方案)
     * </pre>
     *
     * @param body 方案数据
     * @return R.ok(新建方案)
     */
    @PostMapping("/schemes")
    public R<Map<String, Object>> createScheme(@RequestBody Map<String, Object> body) {
        return svc.createScheme(body);
    }

    /**
     * <p>反算方案详情</p>
     *
     * <pre>
     * GET /reverse/schemes/{sid}
     * Path: sid (Long, required) - 方案 ID
     *
     * Response: R.ok({id, scheme_code, scheme_name, targets, ...})
     * </pre>
     *
     * @param sid 方案 ID
     * @return R.ok(方案详情)
     */
    @GetMapping("/schemes/{sid}")
    public R<Map<String, Object>> getScheme(@PathVariable Long sid) {
        return svc.getScheme(sid);
    }

    /**
     * <p>更新反算方案</p>
     *
     * <pre>
     * PUT /reverse/schemes/{sid}
     * Path: sid (Long, required) - 方案 ID
     * Body: 更新字段
     *
     * Response: R.ok(更新后方案)
     * </pre>
     *
     * @param sid  方案 ID
     * @param body 更新内容
     * @return R.ok(更新后方案)
     */
    @PutMapping("/schemes/{sid}")
    public R<Map<String, Object>> updateScheme(@PathVariable Long sid, @RequestBody Map<String, Object> body) {
        return svc.updateScheme(sid, body);
    }

    /**
     * <p>软删反算方案</p>
     *
     * <pre>
     * DELETE /reverse/schemes/{sid}
     * Path: sid (Long, required) - 方案 ID
     *
     * Response: R.ok({deleted: N})
     * </pre>
     *
     * @param sid 方案 ID
     * @return R.ok({deleted: N})
     */
    @DeleteMapping("/schemes/{sid}")
    public R<Map<String, Object>> deleteScheme(@PathVariable Long sid) {
        return svc.deleteScheme(sid);
    }

    /**
     * <p>模型下拉选项 (key/value 给前端 option)</p>
     *
     * <pre>
     * GET /reverse/model-options
     * Response: R.ok({items: [{key, value, model_name, ...}, ...]})
     * </pre>
     *
     * @return R.ok(模型选项)
     */
    @GetMapping("/model-options")
    public R<Map<String, Object>> modelOptions() {
        Map<String, Object> resp = new HashMap<>();
        // 简单返回 model 列表（key/value 给前端 option）
        List<Map<String, Object>> models = svc.listModelsForOption();
        resp.put("items", models);
        return R.ok(resp);
    }

    // ========== Target CRUD (5) ==========
    /**
     * <p>目标列表 (KPI 目标值, 用于反算)</p>
     *
     * <pre>
     * GET /reverse/targets
     * Query: schemeId (Long, optional) - 方案 ID
     *
     * Response: R.ok({items: [{id, scheme_id, kpi_code, target_value, ...}]})
     * </pre>
     *
     * @param schemeId 方案 ID (可选)
     * @return R.ok(目标列表)
     */
    @GetMapping("/targets")
    public R<Map<String, Object>> listTargets(@RequestParam(required = false) Long schemeId) {
        return svc.listTargets(schemeId);
    }

    /**
     * <p>新增目标</p>
     *
     * <pre>
     * POST /reverse/targets
     * Body: {scheme_id, kpi_code, target_value, ...}
     *
     * Response: R.ok(新建目标)
     * </pre>
     *
     * @param body 目标数据
     * @return R.ok(新建目标)
     */
    @PostMapping("/targets")
    public R<Map<String, Object>> createTarget(@RequestBody Map<String, Object> body) {
        return svc.createTarget(body);
    }

    /**
     * <p>更新目标</p>
     *
     * <pre>
     * PUT /reverse/targets/{tid}
     * Path: tid (Long, required) - 目标 ID
     * Body: 更新字段
     *
     * Response: R.ok(更新后目标)
     * </pre>
     *
     * @param tid  目标 ID
     * @param body 更新内容
     * @return R.ok(更新后目标)
     */
    @PutMapping("/targets/{tid}")
    public R<Map<String, Object>> updateTarget(@PathVariable Long tid, @RequestBody Map<String, Object> body) {
        return svc.updateTarget(tid, body);
    }

    /**
     * <p>软删目标</p>
     *
     * <pre>
     * DELETE /reverse/targets/{tid}
     * Path: tid (Long, required) - 目标 ID
     *
     * Response: R.ok({deleted: N})
     * </pre>
     *
     * @param tid 目标 ID
     * @return R.ok({deleted: N})
     */
    @DeleteMapping("/targets/{tid}")
    public R<Map<String, Object>> deleteTarget(@PathVariable Long tid) {
        return svc.deleteTarget(tid);
    }

    /**
     * <p>KPI 下拉选项 (用于 target 选择)</p>
     *
     * <pre>
     * GET /reverse/kpi-options
     * Response: R.ok({items: [{kpi_code, kpi_name, unit}]})
     * </pre>
     *
     * @return R.ok(KPI 选项)
     */
    @GetMapping("/kpi-options")
    public R<Map<String, Object>> kpiOptions() {
        return svc.kpiOptions();
    }

    // ========== Run CRUD (8) ==========
    /**
     * <p>运行历史列表</p>
     *
     * <pre>
     * GET /reverse/runs
     * Query: schemeId (Long, optional) - 方案 ID
     *        status   (String, optional) - 状态
     *        limit    (Integer, optional) - 最大条数
     *
     * Response: R.ok({items: [{run_id, scheme_id, status, started_at}]})
     * </pre>
     *
     * @param schemeId 方案 ID (可选)
     * @param status   状态 (可选)
     * @param limit    最大条数 (可选)
     * @return R.ok(运行列表)
     */
    @GetMapping("/runs")
    public R<Map<String, Object>> listRuns(@RequestParam(required = false) Long schemeId,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) Integer limit) {
        return svc.listRuns(schemeId, status, limit);
    }

    /**
     * <p>新建运行任务</p>
     *
     * <pre>
     * POST /reverse/runs
     * Body: {scheme_id, algorithm_code, params, ...}
     *
     * Response: R.ok({run_id, status: "PENDING"})
     * </pre>
     *
     * @param body 运行参数
     * @return R.ok(新建运行)
     */
    @PostMapping("/runs")
    public R<Map<String, Object>> createRun(@RequestBody Map<String, Object> body) {
        return svc.createRun(body);
    }

    /**
     * <p>启动运行</p>
     *
     * <pre>
     * POST /reverse/runs/{rid}/start
     * Path: rid (Long, required) - 运行 ID
     *
     * Response: R.ok({status: "RUNNING"})
     * </pre>
     *
     * @param rid 运行 ID
     * @return R.ok({status})
     */
    @PostMapping("/runs/{rid}/start")
    public R<Map<String, Object>> startRun(@PathVariable Long rid) {
        return svc.startRun(rid);
    }

    /**
     * <p>取消运行</p>
     *
     * <pre>
     * POST /reverse/runs/{rid}/cancel
     * Path: rid (Long, required) - 运行 ID
     *
     * Response: R.ok({status: "CANCELLED"})
     * </pre>
     *
     * @param rid 运行 ID
     * @return R.ok({status})
     */
    @PostMapping("/runs/{rid}/cancel")
    public R<Map<String, Object>> cancelRun(@PathVariable Long rid) {
        return svc.cancelRun(rid);
    }

    /**
     * <p>软删运行</p>
     *
     * <pre>
     * DELETE /reverse/runs/{rid}
     * Path: rid (Long, required) - 运行 ID
     *
     * Response: R.ok({deleted: N})
     * </pre>
     *
     * @param rid 运行 ID
     * @return R.ok({deleted: N})
     */
    @DeleteMapping("/runs/{rid}")
    public R<Map<String, Object>> deleteRun(@PathVariable Long rid) {
        return svc.deleteRun(rid);
    }

    /**
     * <p>运行日志 (轮询, since_id 增量)</p>
     *
     * <pre>
     * GET /reverse/runs/{rid}/logs
     * Path: rid (Long, required) - 运行 ID
     * Query: since_id (Long, optional) - 增量起点 (上次最大 ID)
     *
     * Response: R.ok({lines: [{id, ts, level, msg}], cursor})
     * </pre>
     *
     * @param rid     运行 ID
     * @param since_id 增量起点 (可选)
     * @return R.ok({lines, cursor})
     */
    @GetMapping("/runs/{rid}/logs")
    public R<Map<String, Object>> runLogs(@PathVariable Long rid,
                                           @RequestParam(required = false) Long since_id) {
        return svc.runLogs(rid, since_id);
    }

    /**
     * <p>运行结果 (反算输出的月度业务数据)</p>
     *
     * <pre>
     * GET /reverse/runs/{rid}/result
     * Path: rid (Long, required) - 运行 ID
     *
     * Response: R.ok({run_id, status, results: [...]})
     * </pre>
     *
     * @param rid 运行 ID
     * @return R.ok(运行结果)
     */
    @GetMapping("/runs/{rid}/result")
    public R<Map<String, Object>> runResult(@PathVariable Long rid) {
        return svc.runResult(rid);
    }

    /**
     * <p>算法下拉选项 (HEURISTIC / CVXPY_QP / CVXPY_LP)</p>
     *
     * <pre>
     * GET /reverse/algorithms
     * Response: R.ok({items: [{code, name, description}]})
     * </pre>
     *
     * @return R.ok(算法选项)
     */
    @GetMapping("/algorithms")
    public R<Map<String, Object>> listAlgorithms() {
        Map<String, Object> resp = new HashMap<>();
        List<Map<String, Object>> algos = new java.util.ArrayList<>();
        algos.add(algo("HEURISTIC", "启发式（推荐）", "基于历史均值+趋势外推的快速预测"));
        algos.add(algo("CVXPY_QP", "二次规划（演示）", "最小化二阶差分 + KPI 约束"));
        algos.add(algo("CVXPY_LP", "线性规划（演示）", "同 QP 但不含二次惩罚"));
        resp.put("items", algos);
        return R.ok(resp);
    }

    private static Map<String, Object> algo(String code, String name, String desc) {
        Map<String, Object> m = new HashMap<>();
        m.put("code", code);
        m.put("name", name);
        m.put("description", desc);
        return m;
    }
}
