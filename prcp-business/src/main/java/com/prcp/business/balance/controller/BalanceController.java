package com.prcp.business.balance.controller;

import com.prcp.business.balance.service.BalanceExcelService;
import com.prcp.business.balance.service.BalanceService;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * <p>资产负债表 API — 10 端点 (对位 Python /balance prefix)</p>
 *
 * <p>详细说明:
 * <ul>
 *   <li>业务背景: 银行资产负债数据的 CRUD、按方案矩阵查询、按日期汇总、Excel 导入导出</li>
 *   <li>核心端点: GET /balance (列表)、GET /balance/by-scheme-matrix (节点×期限矩阵)、GET /balance/gap-summary (期限缺口)、POST /balance/import-xlsx (Excel 导入)、GET /balance/export-xlsx (Excel 导出)</li>
 *   <li>关联模块: BalanceService (CRUD+矩阵)、BalanceExcelService (导入导出)</li>
 * </ul>
 * </p>
 *
 * <p>REST 前缀: {@code /balance}</p>
 * <p>权限要求: 登录用户 (Bearer Token)</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.balance.service.BalanceService
 * @see com.prcp.business.balance.service.BalanceExcelService
 */
@RestController
@RequestMapping("/balance")
@RequiredArgsConstructor
public class BalanceController {

    private final BalanceService svc;
    private final BalanceExcelService excelSvc;

    /**
     * <p>列表查询 (按 coa_node_id / data_date / 日期范围 / 方案过滤)</p>
     *
     * <pre>
     * GET /balance
     * Query: coa_node_id (Long, optional) - 账户册节点 ID
     *        data_date   (yyyy-MM-dd, optional) - 数据日期
     *        start_date  (yyyy-MM-dd, optional) - 起期
     *        end_date    (yyyy-MM-dd, optional) - 止期
     *        scheme_id   (Long, optional) - 方案 ID
     *
     * Response: R.ok({items: [...], total: N})
     * </pre>
     *
     * @param coa_node_id 账户册节点 ID (可选)
     * @param data_date   数据日期 yyyy-MM-dd (可选)
     * @param start_date  起期 yyyy-MM-dd (可选)
     * @param end_date    止期 yyyy-MM-dd (可选)
     * @param scheme_id   方案 ID (可选)
     * @return R.ok({items, total})
     */
    @GetMapping
    public R<Map<String, Object>> list(@RequestParam(required = false) Long coa_node_id,
                                        @RequestParam(required = false) String data_date,
                                        @RequestParam(required = false) String start_date,
                                        @RequestParam(required = false) String end_date,
                                        @RequestParam(required = false) Long scheme_id) {
        return svc.list(coa_node_id, data_date, start_date, end_date, scheme_id);
    }

    /**
     * <p>新增或更新资产负债表数据 (upsert)</p>
     *
     * <pre>
     * POST /balance
     * Body: {coa_node_id, scheme_id, data_date, balance_type, amount, term_bucket, ...}
     *
     * Response: R.ok({...}) - 持久化后的记录
     * </pre>
     *
     * @param body 请求体 (含 coa_node_id / data_date / amount / 期限桶 等)
     * @return R.ok(持久化结果)
     */
    @PostMapping
    public R<Map<String, Object>> upsert(@RequestBody Map<String, Object> body) {
        return svc.upsert(body);
    }

    /**
     * <p>删除单条资产负债表数据 (按主键 ID)</p>
     *
     * <pre>
     * DELETE /balance/{bid}
     * Path: bid (Long) - 资产负债表主键 ID
     *
     * Response: R.ok({deleted: N})
     * </pre>
     *
     * @param bid 资产负债表主键 ID
     * @return R.ok({deleted: N})
     */
    @DeleteMapping("/{bid}")
    public R<Map<String, Object>> delete(@PathVariable Long bid) {
        return svc.delete(bid);
    }

    /**
     * <p>期限缺口汇总 (gap-summary, 单一日期)</p>
     *
     * <pre>
     * GET /balance/gap-summary?data_date=2025-12-31
     * Query: data_date (yyyy-MM-dd, required) - 数据日期
     *
     * Response: R.ok({buckets: [...], total_gap: ...})
     * </pre>
     *
     * @param data_date 数据日期 yyyy-MM-dd
     * @return R.ok(期限缺口汇总)
     */
    @GetMapping("/gap-summary")
    public R<Map<String, Object>> gapSummary(@RequestParam String data_date) {
        return svc.gapSummary(data_date);
    }

    /**
     * <p>按方案 × 期限矩阵 (节点 × 期限桶 二维透视)</p>
     *
     * <pre>
     * GET /balance/by-scheme-matrix
     * Query: scheme_id  (Long, required) - 方案 ID
     *        start_date (yyyy-MM-dd, required) - 起期
     *        end_date   (yyyy-MM-dd, required) - 止期
     *
     * Response: R.ok({scheme_id, start_date, end_date, nodes, matrix, buckets})
     * </pre>
     *
     * @param scheme_id  方案 ID
     * @param start_date 起期 yyyy-MM-dd
     * @param end_date   止期 yyyy-MM-dd
     * @return R.ok(节点 × 期限桶 二维矩阵)
     */
    @GetMapping("/by-scheme-matrix")
    public R<Map<String, Object>> bySchemeMatrix(@RequestParam Long scheme_id,
                                                  @RequestParam String start_date,
                                                  @RequestParam String end_date) {
        return svc.bySchemeMatrix(scheme_id, start_date, end_date);
    }

    /**
     * <p>按方案查询资产负债表数据 (单一日期)</p>
     *
     * <pre>
     * GET /balance/by-scheme
     * Query: scheme_id (Long, required) - 方案 ID
     *        data_date (yyyy-MM-dd, required) - 数据日期
     *
     * Response: R.ok({items: [...], total: N})
     * </pre>
     *
     * @param scheme_id 方案 ID
     * @param data_date 数据日期 yyyy-MM-dd
     * @return R.ok({items, total})
     */
    @GetMapping("/by-scheme")
    public R<Map<String, Object>> byScheme(@RequestParam Long scheme_id,
                                            @RequestParam String data_date) {
        return svc.byScheme(scheme_id, data_date);
    }

    /**
     * <p>可用数据日期列表</p>
     *
     * <pre>
     * GET /balance/dates
     * Query: scheme_id (Long, optional) - 方案 ID (过滤)
     *
     * Response: R.ok({dates: ["2024-12-31", "2025-12-31", ...]})
     * </pre>
     *
     * @param scheme_id 方案 ID (可选, null 返回所有)
     * @return R.ok(可用数据日期列表)
     */
    @GetMapping("/dates")
    public R<Map<String, Object>> dates(@RequestParam(required = false) Long scheme_id) {
        return svc.dates(scheme_id);
    }

    /**
     * <p>按大类 (category) 汇总资产负债表数据</p>
     *
     * <pre>
     * GET /balance/category-summary
     * Query: data_date (yyyy-MM-dd, required) - 数据日期
     *        scheme_id (Long, optional) - 方案 ID
     *
     * Response: R.ok({categories: [{category, total_amount, count}, ...]})
     * </pre>
     *
     * @param data_date 数据日期 yyyy-MM-dd
     * @param scheme_id 方案 ID (可选)
     * @return R.ok(大类汇总)
     */
    @GetMapping("/category-summary")
    public R<Map<String, Object>> categorySummary(@RequestParam String data_date,
                                                    @RequestParam(required = false) Long scheme_id) {
        return svc.categorySummary(data_date, scheme_id);
    }

    /**
     * <p>导出资产负债表数据到 Excel 文件</p>
     *
     * <pre>
     * GET /balance/export-xlsx
     * Query: scheme_id  (Long, required) - 方案 ID
     *        start_date (yyyy-MM-dd, required) - 起期
     *        end_date   (yyyy-MM-dd, required) - 止期
     *
     * Response: 二进制流 (Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet)
     *           文件名: balance.xlsx
     * </pre>
     *
     * @param scheme_id  方案 ID
     * @param start_date 起期 yyyy-MM-dd
     * @param end_date   止期 yyyy-MM-dd
     * @return ResponseEntity 包含 xlsx 二进制流
     * @throws Exception Excel 生成异常
     */
    @GetMapping("/export-xlsx")
    public ResponseEntity<byte[]> exportXlsx(@RequestParam Long scheme_id,
                                              @RequestParam String start_date,
                                              @RequestParam String end_date) throws Exception {
        byte[] data = excelSvc.exportXlsx(scheme_id, start_date, end_date);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDispositionFormData("attachment", "balance.xlsx");
        return new ResponseEntity<>(data, headers, 200);
    }

    /**
     * <p>从 Excel 文件导入资产负债表数据 (multipart/form-data)</p>
     *
     * <pre>
     * POST /balance/import-xlsx
     * Form: file (MultipartFile) - xlsx 文件
     *
     * Response: R.ok({imported: N, skipped: N, errors: [{row, message}]})
     * </pre>
     *
     * @param file 上传的 xlsx 文件
     * @return R.ok({imported, skipped, errors})
     * @throws Exception Excel 解析异常
     */
    @PostMapping("/import-xlsx")
    public R<Map<String, Object>> importXlsx(@RequestParam("file") MultipartFile file) throws Exception {
        return excelSvc.importXlsx(file);
    }
}