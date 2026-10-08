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
 * 反算结果查询 API（对位 Python /data-reverse 路由）
 *
 * @author WorkBuddy Agent
 * @date 2026-09-28
 */
@RestController
@RequestMapping("/reverse-result")
@RequiredArgsConstructor
public class ReverseResultController {

    private final ReverseResultService svc;

    @GetMapping("/schemes")
    public R<Map<String, Object>> listSchemes() {
        return svc.listSchemes();
    }

    @GetMapping("/runs")
    public R<Map<String, Object>> listRuns(@RequestParam(required = false) String scheme_code) {
        return svc.listRuns(scheme_code);
    }

    @GetMapping("/dates")
    public R<Map<String, Object>> listDates(@RequestParam(required = false) String scheme_code,
                                             @RequestParam(required = false) Long run_id) {
        return svc.listDates(scheme_code, run_id);
    }

    @GetMapping("/by-scheme-matrix")
    public R<Map<String, Object>> bySchemeMatrix(@RequestParam(required = false) String scheme_code,
                                                  @RequestParam(required = false) Long run_id,
                                                  @RequestParam(required = false) String data_date,
                                                  @RequestParam(required = false) Integer date_offset) {
        return svc.bySchemeMatrix(scheme_code, run_id, data_date, date_offset);
    }

    @GetMapping("/category-summary")
    public R<Map<String, Object>> categorySummary(@RequestParam(required = false) String scheme_code,
                                                   @RequestParam(required = false) Long run_id,
                                                   @RequestParam(required = false) String data_date,
                                                   @RequestParam(required = false) Integer date_offset) {
        return svc.categorySummary(scheme_code, run_id, data_date, date_offset);
    }

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