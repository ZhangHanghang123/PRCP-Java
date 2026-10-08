package com.prcp.business.esg.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prcp.business.esg.entity.EsgRun;
import com.prcp.business.esg.mapper.EsgRunMapper;
import com.prcp.common.exception.BizException;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * ESG 运行历史服务（3 端点：scheme 级 run / 全局 run / 单 run 详情）
 * 对齐 Python routers/esg.py: list_scheme_runs / list_all_runs / get_run
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EsgRunService {

    private final EsgRunMapper mapper;
    private final ObjectMapper om = new ObjectMapper();

    /** 方案级 run 历史 */
    public R<Map<String, Object>> listByScheme(Long schemeId, String runType, String status, int limit) {
        List<Map<String, Object>> items = mapper.listByScheme(schemeId, emptyToNull(runType), emptyToNull(status), Math.min(limit, 200));
        decodeJsonFields(items);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        return R.ok(resp);
    }

    /** 全局 run 历史（含分页） */
    public R<Map<String, Object>> listAll(Long schemeId, String runType, String status, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        List<Map<String, Object>> items = mapper.listAll(schemeId, emptyToNull(runType), emptyToNull(status), pageSize, offset);
        decodeJsonFields(items);
        int total = mapper.countAll(schemeId, emptyToNull(runType), emptyToNull(status));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        resp.put("total", total);
        resp.put("page", page);
        resp.put("pageSize", pageSize);
        return R.ok(resp);
    }

    /** 单 run 详情 */
    public R<Map<String, Object>> getRun(Long id) {
        EsgRun r = mapper.selectById(id);
        if (r == null) throw BizException.badRequest("run " + id + " 不存在");
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", r.getId());
        map.put("schemeId", r.getSchemeId());
        map.put("schemeCode", r.getSchemeCode());
        map.put("runType", r.getRunType());
        map.put("status", r.getStatus());
        map.put("params", decodeJson(r.getParamsJson()));
        map.put("output", decodeJson(r.getOutputJson()));
        map.put("filePath", r.getFilePath());
        map.put("durationMs", r.getDurationMs());
        map.put("errorMessage", r.getErrorMessage());
        map.put("createdAt", r.getCreatedAt() != null ? r.getCreatedAt().toString() : null);
        return R.ok(map);
    }

    /** 写 run（ExecutionService 调用） */
    public Long writeRun(Long schemeId, String schemeCode, String runType, String status,
                          Map<String, Object> params, Map<String, Object> output,
                          String filePath, Integer durationMs, String errorMessage) {
        EsgRun r = new EsgRun();
        r.setSchemeId(schemeId);
        r.setSchemeCode(schemeCode);
        r.setRunType(runType);
        r.setStatus(status != null ? status : "SUCCESS");
        r.setParamsJson(serialize(params));
        r.setOutputJson(serialize(output));
        r.setFilePath(filePath);
        r.setDurationMs(durationMs);
        r.setErrorMessage(errorMessage);
        r.setCreatedBy(1L);
        mapper.insertRun(r);
        return r.getId();
    }

    private void decodeJsonFields(List<Map<String, Object>> items) {
        for (Map<String, Object> row : items) {
            Object pj = row.get("paramsJson");
            if (pj instanceof String && !((String) pj).isEmpty()) {
                try { row.put("params", om.readValue((String) pj, new TypeReference<Object>() {})); }
                catch (Exception e) { row.put("params", pj); }
            }
            Object oj = row.get("outputJson");
            if (oj instanceof String && !((String) oj).isEmpty()) {
                try { row.put("output", om.readValue((String) oj, new TypeReference<Object>() {})); }
                catch (Exception e) { row.put("output", oj); }
            }
        }
    }

    private Object decodeJson(String json) {
        if (json == null || json.isEmpty()) return null;
        try { return om.readValue(json, new TypeReference<Object>() {}); }
        catch (Exception e) { return json; }
    }

    private String serialize(Map<String, Object> m) {
        if (m == null) return null;
        try { return om.writeValueAsString(m); } catch (Exception e) { return null; }
    }

    private static String emptyToNull(String s) { return (s == null || s.isEmpty()) ? null : s; }
}