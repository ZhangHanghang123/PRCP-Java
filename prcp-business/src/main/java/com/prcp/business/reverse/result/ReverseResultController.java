package com.prcp.business.reverse.result;

import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * <p>反算结果查询 Controller (对位 Python /data-reverse 路由)</p>
 *
 * <p>详细说明:
 * <ul>
 *   <li>业务背景: 反算结果数据的查询, 支持方案/运行/日期维度, 含大类汇总、节点×期限矩阵、xlsx 导出</li>
 *   <li>核心端点: GET /reverse-result/schemes、GET /reverse-result/runs、GET /reverse-result/dates、GET /reverse-result/by-scheme-matrix、GET /reverse-result/category-summary、GET /reverse-result/export-xlsx</li>
 *   <li>关联模块: ReverseResultService</li>
 * </ul>
 * </p>
 *
 * <p>REST 前缀: {@code /reverse-result}</p>
 * <p>权限要求: 登录用户 (Bearer Token)</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.reverse.result.ReverseResultService
 */
@RestController
@RequestMapping("/reverse-result")
@RequiredArgsConstructor
public class ReverseResultController {

    private final ReverseResultService svc;

    /**
     * <p>方案列表</p>
     *
     * <pre>
     * GET /reverse-result/schemes
     * Response: R.ok({items: [{scheme_code, scheme_name, ...}]})
     * </pre>
     *
     * @return R.ok(方案列表)
     */
    @GetMapping("/schemes")
    public R<Map<String, Object>> listSchemes() {
        return svc.listSchemes();
    }

    /**
     * <p>运行列表</p>
     *
     * <pre>
     * GET /reverse-result/runs
     * Query: scheme_code (String, optional) - 方案编码
     *
     * Response: R.ok({items: [{run_id, scheme_code, status, started_at}]})
     * </pre>
     *
     * @param scheme_code 方案编码 (可选)
     * @return R.ok(运行列表)
     */
    @GetMapping("/runs")
    public R<Map<String, Object>> listRuns(@RequestParam(required = false) String scheme_code) {
        return svc.listRuns(scheme_code);
    }

    /**
     * <p>日期列表</p>
     *
     * <pre>
     * GET /reverse-result/dates
     * Query: scheme_code (String, optional) - 方案编码
     *        run_id      (Long, optional) - 运行 ID
     *
     * Response: R.ok({items: [{date_offset, data_date}]})
     * </pre>
     *
     * @param scheme_code 方案编码 (可选)
     * @param run_id      运行 ID (可选)
     * @return R.ok(日期列表)
     */
    @GetMapping("/dates")
    public R<Map<String, Object>> listDates(@RequestParam(required = false) String scheme_code,
                                             @RequestParam(required = false) Long run_id) {
        return svc.listDates(scheme_code, run_id);
    }

    /**
     * <p>节点 × 期限矩阵 (按方案/运行/日期)</p>
     *
     * <pre>
     * GET /reverse-result/by-scheme-matrix
     * Query: scheme_code (String, optional) - 方案编码
     *        run_id      (Long, optional) - 运行 ID
     *        data_date   (yyyy-MM-dd, optional) - 数据日期
     *        date_offset (Integer, optional) - 日期偏移量
     *
     * Response: R.ok({scheme_code, run_id, data_date, nodes, matrix, buckets, categories})
     * </pre>
     *
     * @param scheme_code 方案编码 (可选)
     * @param run_id      运行 ID (可选)
     * @param data_date   数据日期 yyyy-MM-dd (可选)
     * @param date_offset 日期偏移量 (可选)
     * @return R.ok(节点 × 桶 矩阵)
     */
    @GetMapping("/by-scheme-matrix")
    public R<Map<String, Object>> bySchemeMatrix(@RequestParam(required = false) String scheme_code,
                                                  @RequestParam(required = false) Long run_id,
                                                  @RequestParam(required = false) String data_date,
                                                  @RequestParam(required = false) Integer date_offset) {
        return svc.bySchemeMatrix(scheme_code, run_id, data_date, date_offset);
    }

    /**
     * <p>按大类汇总</p>
     *
     * <pre>
     * GET /reverse-result/category-summary
     * Query: scheme_code (String, optional) - 方案编码
     *        run_id      (Long, optional) - 运行 ID
     *        data_date   (yyyy-MM-dd, optional) - 数据日期
     *        date_offset (Integer, optional) - 日期偏移量
     *
     * Response: R.ok({categories: [{category, total, count}, ...]})
     * </pre>
     *
     * @param scheme_code 方案编码 (可选)
     * @param run_id      运行 ID (可选)
     * @param data_date   数据日期 yyyy-MM-dd (可选)
     * @param date_offset 日期偏移量 (可选)
     * @return R.ok(大类汇总)
     */
    @GetMapping("/category-summary")
    public R<Map<String, Object>> categorySummary(@RequestParam(required = false) String scheme_code,
                                                   @RequestParam(required = false) Long run_id,
                                                   @RequestParam(required = false) String data_date,
                                                   @RequestParam(required = false) Integer date_offset) {
        return svc.categorySummary(scheme_code, run_id, data_date, date_offset);
    }

    /**
     * <p>导出反算结果到 xlsx</p>
     *
     * <pre>
     * GET /reverse-result/export-xlsx
     * Query: scheme_code (String, optional) - 方案编码
     *        run_id      (Long, optional) - 运行 ID
     *
     * Response: 二进制流 (xlsx) 文件名: 反算结果_{scheme_code}_run{run_id}.xlsx
     * </pre>
     *
     * @param scheme_code 方案编码 (可选)
     * @param run_id      运行 ID (可选)
     * @param resp        HTTP 响应对象
     * @throws Exception 导出异常
     */
    @GetMapping("/export-xlsx")
    public void exportXlsx(@RequestParam(required = false) String scheme_code,
                            @RequestParam(required = false) Long run_id,
                            HttpServletResponse resp) throws Exception {
        String fname = "反算结果_" + (scheme_code == null ? "" : scheme_code) + (run_id == null ? "" : "_run" + run_id) + ".xlsx";
        String enc = URLEncoder.encode(fname, StandardCharsets.UTF_8).replace("+", "%20");
        resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + enc + "\"; filename*=UTF-8''" + enc);
        try (OutputStream os = resp.getOutputStream()) {
            svc.exportXlsx(scheme_code, run_id, os);
        }
    }
}