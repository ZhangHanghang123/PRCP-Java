package com.prcp.business.report.controller;

import com.prcp.business.report.entity.RptItem;
import com.prcp.business.report.entity.RptReport;
import com.prcp.business.report.service.RptService;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * <p>报表管理 Controller (Report Definitions + Items + Values)</p>
 *
 * <p>详细说明:
 * <ul>
 *   <li>业务背景: 银行财务报表 (资产负债表/损益表/资本表 等) 的定义、表项、试算值管理</li>
 *   <li>核心端点:
 *     <ul>
 *       <li>报表定义 CRUD: GET/POST/PUT/DELETE /reports</li>
 *       <li>报表表项 CRUD + 批量: GET/POST/PUT/DELETE/POST-batch /reports/items</li>
 *       <li>按月试算: POST /reports/items/calc-by-month</li>
 *       <li>历史值: GET /reports/values</li>
 *       <li>预览: GET /reports/preview</li>
 *     </ul>
 *   </li>
 *   <li>关联模块: RptService、RptReport/RptItem 实体</li>
 * </ul>
 * </p>
 *
 * <p>REST 前缀: {@code /reports}</p>
 * <p>权限要求: 登录用户 (Bearer Token)</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.report.service.RptService
 */
@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
public class RptController {

    private final RptService rptService;

    // ========== 报表定义 ==========

    /**
     * <p>报表列表</p>
     *
     * <pre>
     * GET /reports/
     * Query: reportType (String, optional) - 报表类型 (BALANCE_SHEET/INCOME/CAPITAL/...)
     *        scheme_id  (Long, optional) - 方案 ID
     *        keyword    (String, optional) - 模糊搜索
     *
     * Response: R.ok(List&lt;Map&gt;) 报表定义列表
     * </pre>
     *
     * @param reportType 报表类型 (可选)
     * @param scheme_id  方案 ID (可选)
     * @param keyword    模糊搜索关键字 (可选)
     * @return R.ok(报表定义列表)
     */
    @GetMapping("/")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String reportType,
                                             @RequestParam(required = false) Long scheme_id,
                                             @RequestParam(required = false) String keyword) {
        return rptService.listReports(reportType, scheme_id, keyword);
    }

    /**
     * <p>新建报表定义</p>
     *
     * <pre>
     * POST /reports/
     * Body: RptReport (report_code, report_name, report_type, scheme_id, ...)
     *
     * Response: R.ok(新建报表)
     * </pre>
     *
     * @param r 报表实体
     * @return R.ok(新建报表)
     */
    @PostMapping("/")
    public R<?> create(@Valid @RequestBody RptReport r) { return rptService.create(r); }

    /**
     * <p>更新报表定义</p>
     *
     * <pre>
     * PUT /reports/{id}
     * Path: id (Long, required) - 报表 ID
     * Body: RptReport (更新字段)
     *
     * Response: R.ok(更新后报表)
     * </pre>
     *
     * @param id 报表 ID
     * @param r  报表实体
     * @return R.ok(更新后报表)
     */
    @PutMapping("/{id}")
    public R<?> update(@PathVariable Long id, @Valid @RequestBody RptReport r) { return rptService.update(id, r); }

    /**
     * <p>软删报表定义</p>
     *
     * <pre>
     * DELETE /reports/{id}
     * Path: id (Long, required) - 报表 ID
     *
     * Response: R.ok({deleted: N})
     * </pre>
     *
     * @param id 报表 ID
     * @return R.ok({deleted: N})
     */
    @DeleteMapping("/{id}")
    public R<?> delete(@PathVariable Long id) { return rptService.softDelete(id); }

    // ========== 报表表项 ==========

    /**
     * <p>报表表项列表 (按报表 ID)</p>
     *
     * <pre>
     * GET /reports/items?report_id=1
     * Query: report_id (Long, required) - 报表 ID
     *
     * Response: R.ok(List&lt;Map&gt;) 表项列表
     * </pre>
     *
     * @param reportId 报表 ID
     * @return R.ok(表项列表)
     */
    @GetMapping("/items")
    public R<List<Map<String, Object>>> listItems(@RequestParam("report_id") Long reportId) {
        return rptService.listItems(reportId);
    }

    /**
     * <p>新增报表表项</p>
     *
     * <pre>
     * POST /reports/items
     * Body: RptItem (report_id, item_code, item_name, coa_node_ids, ...)
     *
     * Response: R.ok(新建表项)
     * </pre>
     *
     * @param item 表项实体
     * @return R.ok(新建表项)
     */
    @PostMapping("/items")
    public R<?> createItem(@Valid @RequestBody RptItem item) { return rptService.createItem(item); }

    /**
     * <p>更新报表表项</p>
     *
     * <pre>
     * PUT /reports/items/{id}
     * Path: id (Long, required) - 表项 ID
     * Body: RptItem (更新字段)
     *
     * Response: R.ok(更新后表项)
     * </pre>
     *
     * @param id   表项 ID
     * @param item 表项实体
     * @return R.ok(更新后表项)
     */
    @PutMapping("/items/{id}")
    public R<?> updateItem(@PathVariable Long id, @Valid @RequestBody RptItem item) {
        return rptService.updateItem(id, item);
    }

    /**
     * <p>软删报表表项</p>
     *
     * <pre>
     * DELETE /reports/items/{id}
     * Path: id (Long, required) - 表项 ID
     *
     * Response: R.ok({deleted: N})
     * </pre>
     *
     * @param id 表项 ID
     * @return R.ok({deleted: N})
     */
    @DeleteMapping("/items/{id}")
    public R<?> deleteItem(@PathVariable Long id) { return rptService.deleteItem(id); }

    /**
     * <p>批量新增 + 修改 + 删除 (对齐 Python routers/reports.py batch_items)</p>
     *
     * <pre>
     * POST /reports/items/batch
     * Query: report_id (Long, required) - 报表 ID
     * Body: {create: [...], update: [{id, ...}], delete_ids: [...]}
     *
     * Response: R.ok({ok, created, updated, deleted})
     * </pre>
     *
     * @param reportId 报表 ID
     * @param body     含 create/update/delete_ids
     * @return R.ok({ok, created, updated, deleted})
     */
    @PostMapping("/items/batch")
    public R<Map<String, Object>> batchItems(@RequestParam("report_id") Long reportId,
                                               @RequestBody(required = false) Map<String, Object> body) {
        return rptService.batchItems(reportId, body);
    }

    // ========== 按月试算（trial-calculate）==========

    /**
     * <p>按月试算 (对所有有 coa_node_ids 的 item 按 data_date 聚合 prcp_data_basic.orig_m1, 写入 prcp_rpt_value, source='CALC')</p>
     *
     * <pre>
     * POST /reports/items/calc-by-month
     * Body: {data_date: "2026-08-01", category?: "FINANCIAL", report_id?: 1}
     *
     * Response: R.ok({count, created, updated, data_date, results: [{item_id, item_code, item_name, value, action, matched}]})
     * </pre>
     *
     * @param body 含 data_date / category / report_id
     * @return R.ok({count, created, updated, data_date, results})
     */
    @PostMapping("/items/calc-by-month")
    public R<Map<String, Object>> calcByMonth(@RequestBody Map<String, Object> body) {
        String dataDate = body == null ? null : (String) body.get("data_date");
        String category = body == null ? null : (String) body.get("category");
        Object ridObj = body == null ? null : body.get("report_id");
        Long reportId = ridObj == null ? null
            : (ridObj instanceof Number ? ((Number) ridObj).longValue()
               : Long.parseLong(ridObj.toString()));
        return rptService.calcByMonth(dataDate, category, reportId);
    }

    /**
     * <p>列某 item 的所有历史值</p>
     *
     * <pre>
     * GET /reports/values
     * Query: item_id   (Long, required) - 表项 ID
     *        data_date (yyyy-MM-dd, optional) - 数据日期
     *
     * Response: R.ok(List&lt;Map&gt;) 历史值列表
     * </pre>
     *
     * @param itemId   表项 ID
     * @param data_date 数据日期 yyyy-MM-dd (可选)
     * @return R.ok(历史值列表)
     */
    @GetMapping("/values")
    public R<List<Map<String, Object>>> listValues(@RequestParam("item_id") Long itemId,
                                                    @RequestParam(required = false) String data_date) {
        return rptService.listValues(itemId, data_date);
    }

    /**
     * <p>按 report_id + data_date 取试算预览</p>
     *
     * <pre>
     * GET /reports/preview
     * Query: report_id (Long, required) - 报表 ID
     *        data_date (yyyy-MM-dd, required) - 数据日期
     *
     * Response: R.ok(List&lt;Map&gt;) 预览列表
     * </pre>
     *
     * @param reportId 报表 ID
     * @param dataDate 数据日期 yyyy-MM-dd
     * @return R.ok(预览列表)
     */
    @GetMapping("/preview")
    public R<List<Map<String, Object>>> previewByReport(@RequestParam("report_id") Long reportId,
                                                        @RequestParam("data_date") String dataDate) {
        return rptService.previewByReport(reportId, dataDate);
    }
}