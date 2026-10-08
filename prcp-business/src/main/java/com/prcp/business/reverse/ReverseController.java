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
 * 反算 API — 18 端点（对位 Python /reverse prefix）
 *
 * @author WorkBuddy Agent
 * @date 2026-09-26
 */
@RestController
@RequestMapping("/reverse")
@RequiredArgsConstructor
public class ReverseController {

    private final ReverseService svc;

    // ========== Scheme CRUD (5) ==========
    @GetMapping("/schemes")
    public R<Map<String, Object>> listSchemes(@RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String status) {
        return svc.listSchemes(keyword, status);
    }

    @PostMapping("/schemes")
    public R<Map<String, Object>> createScheme(@RequestBody Map<String, Object> body) {
        return svc.createScheme(body);
    }

    @GetMapping("/schemes/{sid}")
    public R<Map<String, Object>> getScheme(@PathVariable Long sid) {
        return svc.getScheme(sid);
    }

    @PutMapping("/schemes/{sid}")
    public R<Map<String, Object>> updateScheme(@PathVariable Long sid, @RequestBody Map<String, Object> body) {
        return svc.updateScheme(sid, body);
    }

    @DeleteMapping("/schemes/{sid}")
    public R<Map<String, Object>> deleteScheme(@PathVariable Long sid) {
        return svc.deleteScheme(sid);
    }

    @GetMapping("/model-options")
    public R<Map<String, Object>> modelOptions() {
        Map<String, Object> resp = new HashMap<>();
        // 简单返回 model 列表（key/value 给前端 option）
        List<Map<String, Object>> models = svc.listModelsForOption();
        resp.put("items", models);
        return R.ok(resp);
    }

    // ========== Target CRUD (5) ==========
    @GetMapping("/targets")
    public R<Map<String, Object>> listTargets(@RequestParam(required = false) Long schemeId) {
        return svc.listTargets(schemeId);
    }

    @PostMapping("/targets")
    public R<Map<String, Object>> createTarget(@RequestBody Map<String, Object> body) {
        return svc.createTarget(body);
    }

    @PutMapping("/targets/{tid}")
    public R<Map<String, Object>> updateTarget(@PathVariable Long tid, @RequestBody Map<String, Object> body) {
        return svc.updateTarget(tid, body);
    }

    @DeleteMapping("/targets/{tid}")
    public R<Map<String, Object>> deleteTarget(@PathVariable Long tid) {
        return svc.deleteTarget(tid);
    }

    @GetMapping("/kpi-options")
    public R<Map<String, Object>> kpiOptions() {
        return svc.kpiOptions();
    }

    // ========== Run CRUD (8) ==========
    @GetMapping("/runs")
    public R<Map<String, Object>> listRuns(@RequestParam(required = false) Long schemeId,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) Integer limit) {
        return svc.listRuns(schemeId, status, limit);
    }

    @PostMapping("/runs")
    public R<Map<String, Object>> createRun(@RequestBody Map<String, Object> body) {
        return svc.createRun(body);
    }

    @PostMapping("/runs/{rid}/start")
    public R<Map<String, Object>> startRun(@PathVariable Long rid) {
        return svc.startRun(rid);
    }

    @PostMapping("/runs/{rid}/cancel")
    public R<Map<String, Object>> cancelRun(@PathVariable Long rid) {
        return svc.cancelRun(rid);
    }

    @DeleteMapping("/runs/{rid}")
    public R<Map<String, Object>> deleteRun(@PathVariable Long rid) {
        return svc.deleteRun(rid);
    }

    @GetMapping("/runs/{rid}/logs")
    public R<Map<String, Object>> runLogs(@PathVariable Long rid,
                                           @RequestParam(required = false) Long since_id) {
        return svc.runLogs(rid, since_id);
    }

    @GetMapping("/runs/{rid}/result")
    public R<Map<String, Object>> runResult(@PathVariable Long rid) {
        return svc.runResult(rid);
    }

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
