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
}