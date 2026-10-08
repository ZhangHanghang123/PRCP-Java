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
 * 资产负债表 API — 10 端点（对位 Python /balance prefix）
 *
 * @author WorkBuddy Agent
 * @date 2026-09-27
 */
@RestController
@RequestMapping("/balance")
@RequiredArgsConstructor
public class BalanceController {

    private final BalanceService svc;
    private final BalanceExcelService excelSvc;

    @GetMapping
    public R<Map<String, Object>> list(@RequestParam(required = false) Long coa_node_id,
                                        @RequestParam(required = false) String data_date,
                                        @RequestParam(required = false) String start_date,
                                        @RequestParam(required = false) String end_date,
                                        @RequestParam(required = false) Long scheme_id) {
        return svc.list(coa_node_id, data_date, start_date, end_date, scheme_id);
    }

    @PostMapping
    public R<Map<String, Object>> upsert(@RequestBody Map<String, Object> body) {
        return svc.upsert(body);
    }

    @DeleteMapping("/{bid}")
    public R<Map<String, Object>> delete(@PathVariable Long bid) {
        return svc.delete(bid);
    }

    @GetMapping("/gap-summary")
    public R<Map<String, Object>> gapSummary(@RequestParam String data_date) {
        return svc.gapSummary(data_date);
    }

    @GetMapping("/by-scheme-matrix")
    public R<Map<String, Object>> bySchemeMatrix(@RequestParam Long scheme_id,
                                                  @RequestParam String start_date,
                                                  @RequestParam String end_date) {
        return svc.bySchemeMatrix(scheme_id, start_date, end_date);
    }

    @GetMapping("/by-scheme")
    public R<Map<String, Object>> byScheme(@RequestParam Long scheme_id,
                                            @RequestParam String data_date) {
        return svc.byScheme(scheme_id, data_date);
    }

    @GetMapping("/dates")
    public R<Map<String, Object>> dates(@RequestParam(required = false) Long scheme_id) {
        return svc.dates(scheme_id);
    }

    @GetMapping("/category-summary")
    public R<Map<String, Object>> categorySummary(@RequestParam String data_date,
                                                    @RequestParam(required = false) Long scheme_id) {
        return svc.categorySummary(data_date, scheme_id);
    }

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

    @PostMapping("/import-xlsx")
    public R<Map<String, Object>> importXlsx(@RequestParam("file") MultipartFile file) throws Exception {
        return excelSvc.importXlsx(file);
    }
}