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
 * 报表管理 Controller
 */
@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
public class RptController {

    private final RptService rptService;

    // ========== 报表定义 ==========

    @GetMapping("/")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) String reportType,
                                             @RequestParam(required = false) Long scheme_id,
                                             @RequestParam(required = false) String keyword) {
        return rptService.listReports(reportType, scheme_id, keyword);
    }

    @PostMapping("/")
    public R<?> create(@Valid @RequestBody RptReport r) { return rptService.create(r); }

    @PutMapping("/{id}")
    public R<?> update(@PathVariable Long id, @Valid @RequestBody RptReport r) { return rptService.update(id, r); }

    @DeleteMapping("/{id}")
    public R<?> delete(@PathVariable Long id) { return rptService.softDelete(id); }

    // ========== 报表表项 ==========

    @GetMapping("/items")
    public R<List<Map<String, Object>>> listItems(@RequestParam("report_id") Long reportId) {
        return rptService.listItems(reportId);
    }

    @PostMapping("/items")
    public R<?> createItem(@Valid @RequestBody RptItem item) { return rptService.createItem(item); }

    @PutMapping("/items/{id}")
    public R<?> updateItem(@PathVariable Long id, @Valid @RequestBody RptItem item) {
        return rptService.updateItem(id, item);
    }

    @DeleteMapping("/items/{id}")
    public R<?> deleteItem(@PathVariable Long id) { return rptService.deleteItem(id); }

    /**
     * 批量新增 + 修改 + 删除（对齐 Python routers/reports.py batch_items）
     * POST /reports/items/batch?report_id=1
     * Body：{create:[...], update:[{id,...}], delete_ids:[...]}
     * 返回：{ok, created, updated, deleted}
     */
    @PostMapping("/items/batch")
    public R<Map<String, Object>> batchItems(@RequestParam("report_id") Long reportId,
                                               @RequestBody(required = false) Map<String, Object> body) {
        return rptService.batchItems(reportId, body);
    }

    // ========== 按月试算（trial-calculate）==========

    /**
     * 按月试算：对所有有 coa_node_ids 的 item 按 data_date 聚合 prcp_data_basic.orig_m1
     * 写入 prcp_rpt_value（upsert，source='CALC'）
     *
     * POST /reports/items/calc-by-month
     * Body: {data_date: "2026-08-01", category?: "FINANCIAL", report_id?: 1}
     * 返回：{count, created, updated, data_date, results:[{item_id, item_code, item_name, value, action, matched}]}
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

    /** 列某 item 的所有历史值 */
    @GetMapping("/values")
    public R<List<Map<String, Object>>> listValues(@RequestParam("item_id") Long itemId,
                                                    @RequestParam(required = false) String data_date) {
        return rptService.listValues(itemId, data_date);
    }

    /** 按 report_id + data_date 取试算预览 */
    @GetMapping("/preview")
    public R<List<Map<String, Object>>> previewByReport(@RequestParam("report_id") Long reportId,
                                                        @RequestParam("data_date") String dataDate) {
        return rptService.previewByReport(reportId, dataDate);
    }
}