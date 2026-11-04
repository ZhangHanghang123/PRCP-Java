package com.prcp.business.reverse.dashboard;

import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * <p>反算 Dashboard Controller (4 端点, 对位 Python /reverse-dashboard prefix)</p>
 *
 * <p>详细说明:
 * <ul>
 *   <li>业务背景: 反算运行的驾驶舱, 提供方案/运行/日期下拉选项 + 反算快照查看</li>
 *   <li>核心端点: GET /reverse-dashboard/options (下拉)、GET /reverse-dashboard/runs (运行列表)、GET /reverse-dashboard/dates (日期列表)、GET /reverse-dashboard/snapshot (快照)</li>
 *   <li>关联模块: ReverseDashboardService</li>
 * </ul>
 * </p>
 *
 * <p>REST 前缀: {@code /reverse-dashboard}</p>
 * <p>权限要求: 登录用户 (Bearer Token)</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.reverse.dashboard.ReverseDashboardService
 */
@RestController
@RequestMapping("/reverse-dashboard")
@RequiredArgsConstructor
public class ReverseDashboardController {

    private final ReverseDashboardService svc;
    private final ReverseDashboardExportService exportSvc;

    /**
     * <p>下拉选项 (方案/算法 列表)</p>
     *
     * <pre>
     * GET /reverse-dashboard/options
     * Response: R.ok({schemes: [...], algorithms: [...]})
     * </pre>
     *
     * @return R.ok(下拉选项)
     */
    @GetMapping("/options")
    public R<Map<String, Object>> options() {
        return svc.options();
    }

    /**
     * <p>运行历史列表 (按方案)</p>
     *
     * <pre>
     * GET /reverse-dashboard/runs
     * Query: scheme_code (String, required) - 方案编码
     *
     * Response: R.ok({items: [{run_id, status, started_at, finished_at}]})
     * </pre>
     *
     * @param schemeCode 方案编码
     * @return R.ok(运行列表)
     */
    @GetMapping("/runs")
    public R<Map<String, Object>> runs(@RequestParam("scheme_code") String schemeCode) {
        return svc.runs(schemeCode);
    }

    /**
     * <p>日期偏移列表 (某次运行下的所有日期偏移)</p>
     *
     * <pre>
     * GET /reverse-dashboard/dates
     * Query: scheme_code (String, required) - 方案编码
     *        run_id      (Long, required) - 运行 ID
     *
     * Response: R.ok({items: [date_offset, ...]})
     * </pre>
     *
     * @param schemeCode 方案编码
     * @param runId      运行 ID
     * @return R.ok(日期偏移列表)
     */
    @GetMapping("/dates")
    public R<Map<String, Object>> dates(@RequestParam("scheme_code") String schemeCode,
                                         @RequestParam("run_id") Long runId) {
        return svc.dates(schemeCode, runId);
    }

    /**
     * <p>反算快照 (按方案/运行/日期偏移)</p>
     *
     * <pre>
     * GET /reverse-dashboard/snapshot
     * Query: scheme_code  (String, optional) - 方案编码
     *        run_id       (Long, optional) - 运行 ID
     *        date_offset  (Integer, optional) - 日期偏移量
     *
     * Response: R.ok({kpis, trend, categories, ...})
     * </pre>
     *
     * @param schemeCode 方案编码 (可选)
     * @param runId      运行 ID (可选)
     * @param dateOffset 日期偏移量 (可选)
     * @return R.ok(反算快照)
     */
    @GetMapping("/snapshot")
    public R<Map<String, Object>> snapshot(@RequestParam(value = "scheme_code", required = false) String schemeCode,
                                           @RequestParam(value = "run_id", required = false) Long runId,
                                           @RequestParam(value = "date_offset", required = false) Integer dateOffset) {
        return svc.snapshot(schemeCode, runId, dateOffset);
    }

    // ============ balance-trend（末级账户册余额走势）============
    /**
     * <p>末级账户册余额走势 — 按月返回大类汇总 + 节点明细</p>
     *
     * <pre>
     * GET /reverse-dashboard/balance-trend?scheme_code=&run_id=
     *
     * Response: R.ok({
     *   scheme_code, run_id,
     *   months:      [{offset, date}],
     *   categories:  [{key, values}],
     *   nodes:       [{code, name, category, values, total}]
     * })
     * </pre>
     *
     * @param schemeCode 方案编码 (可选, 默认 "2026")
     * @param runId      运行 ID (可选, 缺省取最新 SUCCESS)
     * @return R.ok(余额走势)
     */
    @GetMapping("/balance-trend")
    public R<Map<String, Object>> balanceTrend(
            @RequestParam(value = "scheme_code", required = false) String schemeCode,
            @RequestParam(value = "run_id", required = false) Long runId) {
        return svc.balanceTrend(schemeCode, runId);
    }

    // ============ balance-export（结果导出 xlsx）============
    /**
     * <p>结果导出 — 按账户册导出测算期每月预测余额 (xlsx)</p>
     *
     * <pre>
     * GET /reverse-dashboard/balance-export?scheme_code=&run_id=
     *
     * Response: 二进制流 (xlsx)，含 4 个 Sheet:
     *   1. 账户册结构       — 树形，末级=自身值，父节点=子树 roll-up
     *   2. 按层级汇总       — 大类 × 层级 + 末级合计行
     *   3. 末级节点大类汇总 — 5 大类逐月合计 + 合计行
     *   4. 口径说明         — 方案/Run/基准日/月份区间/余额口径等
     * 文件名: 组合反算结果导出_{scheme_code}_run{run_id}_{起始月}至{结束月}.xlsx
     * </pre>
     *
     * @param schemeCode 方案编码 (可选, 缺省取首个方案)
     * @param runId      运行 ID (可选, 缺省取最新 SUCCESS)
     * @param resp       HTTP 响应对象
     * @throws Exception 导出异常
     */
    @GetMapping("/balance-export")
    public void balanceExport(@RequestParam(value = "scheme_code", required = false) String schemeCode,
                               @RequestParam(value = "run_id", required = false) Long runId,
                               HttpServletResponse resp) throws Exception {
        // 先取数据再设响应头: 数据准备阶段的异常 (方案不存在 / 无 Run / 无月度数据)
        // 才能以正常 JSON 错误响应返回, 而不是被已设置的 xlsx Content-Type 吞掉
        byte[] data = exportSvc.exportBalanceXlsx(schemeCode, runId);
        String fname = "组合反算结果导出_" + (schemeCode == null ? "" : schemeCode)
                + (runId == null ? "" : "_run" + runId) + ".xlsx";
        String enc = URLEncoder.encode(fname, StandardCharsets.UTF_8).replace("+", "%20");
        resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        resp.setHeader("Content-Disposition",
                "attachment; filename=\"" + enc + "\"; filename*=UTF-8''" + enc);
        resp.setContentLength(data.length);
        resp.getOutputStream().write(data);
        resp.getOutputStream().flush();
    }
}