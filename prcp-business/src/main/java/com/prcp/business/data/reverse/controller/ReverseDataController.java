package com.prcp.business.data.reverse.controller;

import com.prcp.business.data.reverse.service.ReverseDataService;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/reverse-data")
@RequiredArgsConstructor
public class ReverseDataController {

    private final ReverseDataService reverseDataService;

    @GetMapping("/list")
    public R<List<Map<String, Object>>> list(@RequestParam(required = false) Long schemeId,
                                              @RequestParam(required = false) String dataDate,
                                              @RequestParam(required = false) String category,
                                              @RequestParam(required = false) String nodeKw) {
        return reverseDataService.list(schemeId, dataDate, category, nodeKw);
    }

    @GetMapping("/matrix")
    public R<Map<String, Object>> matrix(@RequestParam(required = false) Long schemeId,
                                          @RequestParam(required = false) String dataDate) {
        return reverseDataService.matrix(schemeId, dataDate);
    }

    @GetMapping("/dates")
    public R<List<String>> dates(@RequestParam(required = false) Long schemeId) {
        return reverseDataService.dates(schemeId);
    }

    @PostMapping("/upsert")
    public R<?> upsert(@RequestBody Map<String, Object> body) {
        return reverseDataService.upsert(body);
    }

    @DeleteMapping("/{id}")
    public R<?> delete(@PathVariable Long id) {
        return reverseDataService.delete(id);
    }
}