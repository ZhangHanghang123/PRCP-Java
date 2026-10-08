package com.prcp.business.esg.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prcp.business.esg.entity.EsgScheme;
import com.prcp.business.esg.mapper.EsgSchemeMapper;
import com.prcp.common.exception.BizException;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * ESG 方案 CRUD 服务（7 端点）
 * 对齐 Python routers/esg.py: list_schemes/create/get/update/delete/clone
 *
 * JSON 字段：maturities_json / initial_yields_json
 *   - 列表查询时反序列化为 List<Integer/Double>
 *   - 写库时序列化为 JSON 字符串
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EsgSchemeService {

    private final EsgSchemeMapper schemeMapper;
    private final ObjectMapper om = new ObjectMapper();

    // ============ list_schemes ============
    public R<Map<String, Object>> listSchemes(String keyword, String status, int page, int pageSize) {
        keyword = emptyToNull(keyword);
        status = emptyToNull(status);
        int offset = (page - 1) * pageSize;
        List<Map<String, Object>> items = schemeMapper.listSchemes(keyword, status, pageSize, offset);
        // JSON 反序列化
        for (Map<String, Object> row : items) {
            decodeJsonFields(row);
        }
        int total = schemeMapper.countSchemes(keyword, status);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        resp.put("total", total);
        resp.put("page", page);
        resp.put("pageSize", pageSize);
        return R.ok(resp);
    }

    // ============ create_scheme ============
    public R<Map<String, Object>> createScheme(Map<String, Object> body) {
        String code = toStr(body.get("schemeCode"));
        if (code == null) code = toStr(body.get("scheme_code"));
        if (code == null || code.length() < 3) throw BizException.badRequest("schemeCode ≥ 3 字符");

        String name = toStr(body.get("schemeName"));
        if (name == null) name = toStr(body.get("scheme_name"));
        if (name == null) throw BizException.badRequest("schemeName 必填");

        Long exists = schemeMapper.selectIdByCode(code);
        if (exists != null) throw BizException.badRequest("scheme_code '" + code + "' 已存在");

        EsgScheme s = new EsgScheme();
        s.setSchemeCode(code);
        s.setSchemeName(name);
        s.setDescription(toStr(body.get("description")));
        s.setDataSource(orDefault(toStr(firstNonNull(body.get("dataSource"), body.get("data_source"))), "ECB"));
        s.setStartDate(toLocalDate(body.get("startDate"), body.get("start_date")));
        s.setEndDate(toLocalDate(body.get("endDate"), body.get("end_date")));
        s.setNFactors(toIntOrDefault(body.get("nFactors"), body.get("n_factors"), 3));
        // 边界：1~6
        if (s.getNFactors() != null && (s.getNFactors() < 1 || s.getNFactors() > 6))
            throw BizException.badRequest("n_factors 必须在 1~6 之间");
        s.setNScenarios(toIntOrDefault(body.get("nScenarios"), body.get("n_scenarios"), 1000));
        if (s.getNScenarios() != null && (s.getNScenarios() < 10 || s.getNScenarios() > 10000))
            throw BizException.badRequest("n_scenarios 必须在 10~10000 之间");
        s.setNSteps(toIntOrDefault(body.get("nSteps"), body.get("n_steps"), 120));
        if (s.getNSteps() != null && (s.getNSteps() < 12 || s.getNSteps() > 360))
            throw BizException.badRequest("n_steps 必须在 12~360 之间");
        s.setSeed(toIntOrDefault(body.get("seed"), body.get("seed"), 42));
        s.setStatus(orDefault(toStr(body.get("status")), "DRAFT"));
        // JSON 字段
        s.setMaturitiesJson(serializeMaturities(body.get("maturitiesMonths"), body.get("maturities_months")));
        s.setInitialYieldsJson(serializeInitialYields(body.get("initialYieldsPct"), body.get("initial_yields_pct")));

        s.setIsDeleted(0);
        schemeMapper.insert(s);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", s.getId());
        resp.put("schemeCode", code);
        resp.put("schemeName", name);
        resp.put("ok", true);
        return R.ok(resp);
    }

    // ============ get_scheme ============
    public R<Map<String, Object>> getScheme(Long id) {
        EsgScheme s = schemeMapper.selectByIdActive(id);
        if (s == null) throw BizException.badRequest("方案不存在或已删除");
        Map<String, Object> map = schemeToMap(s);
        decodeJsonFields(map);
        return R.ok(map);
    }

    // ============ update_scheme ============
    public R<Map<String, Object>> updateScheme(Long id, Map<String, Object> body) {
        EsgScheme exist = schemeMapper.selectByIdActive(id);
        if (exist == null) throw BizException.badRequest("方案不存在");

        EsgScheme upd = new EsgScheme();
        upd.setId(id);
        if (body.containsKey("schemeName") || body.containsKey("scheme_name"))
            upd.setSchemeName(toStr(firstNonNull(body.get("schemeName"), body.get("scheme_name"))));
        if (body.containsKey("description")) upd.setDescription(toStr(body.get("description")));
        if (body.containsKey("dataSource") || body.containsKey("data_source"))
            upd.setDataSource(toStr(firstNonNull(body.get("dataSource"), body.get("data_source"))));
        if (body.containsKey("startDate") || body.containsKey("start_date"))
            upd.setStartDate(toLocalDate(body.get("startDate"), body.get("start_date")));
        if (body.containsKey("endDate") || body.containsKey("end_date"))
            upd.setEndDate(toLocalDate(body.get("endDate"), body.get("end_date")));
        if (body.containsKey("nFactors") || body.containsKey("n_factors"))
            upd.setNFactors(toInt(body.get("nFactors"), body.get("n_factors")));
        if (body.containsKey("nScenarios") || body.containsKey("n_scenarios"))
            upd.setNScenarios(toInt(body.get("nScenarios"), body.get("n_scenarios")));
        if (body.containsKey("nSteps") || body.containsKey("n_steps"))
            upd.setNSteps(toInt(body.get("nSteps"), body.get("n_steps")));
        if (body.containsKey("seed")) upd.setSeed(toInt(body.get("seed")));
        if (body.containsKey("status")) upd.setStatus(toStr(body.get("status")));
        if (body.containsKey("maturitiesMonths") || body.containsKey("maturities_months"))
            upd.setMaturitiesJson(serializeMaturities(body.get("maturitiesMonths"), body.get("maturities_months")));
        if (body.containsKey("initialYieldsPct") || body.containsKey("initial_yields_pct"))
            upd.setInitialYieldsJson(serializeInitialYields(body.get("initialYieldsPct"), body.get("initial_yields_pct")));

        schemeMapper.updateById(upd);
        return R.ok(Map.of("ok", true));
    }

    // ============ delete_scheme ============
    public R<Map<String, Object>> deleteScheme(Long id) {
        int n = schemeMapper.softDeleteById(id, 1L);
        if (n == 0) throw BizException.badRequest("方案不存在或已删除");
        return R.ok(Map.of("ok", true));
    }

    // ============ clone_scheme ============
    public R<Map<String, Object>> cloneScheme(Long id, Map<String, Object> body) {
        EsgScheme src = schemeMapper.selectByIdActive(id);
        if (src == null) throw BizException.badRequest("源方案不存在");

        String newCode = toStr(body.get("newSchemeCode"));
        if (newCode == null) newCode = toStr(body.get("new_scheme_code"));
        if (newCode == null || newCode.length() < 3) throw BizException.badRequest("new_scheme_code ≥ 3 字符");

        Long exists = schemeMapper.selectIdByCode(newCode);
        if (exists != null) throw BizException.badRequest("scheme_code '" + newCode + "' 已存在");

        String newName = toStr(body.get("newSchemeName"));
        if (newName == null) newName = toStr(body.get("new_scheme_name"));
        if (newName == null) newName = src.getSchemeName() + " (副本)";

        EsgScheme copy = new EsgScheme();
        copy.setSchemeCode(newCode);
        copy.setSchemeName(newName);
        copy.setDescription(src.getDescription());
        copy.setDataSource(src.getDataSource());
        copy.setStartDate(src.getStartDate());
        copy.setEndDate(src.getEndDate());
        copy.setNFactors(src.getNFactors());
        copy.setMaturitiesJson(src.getMaturitiesJson());
        copy.setNScenarios(src.getNScenarios());
        copy.setNSteps(src.getNSteps());
        copy.setSeed(src.getSeed());
        copy.setInitialYieldsJson(src.getInitialYieldsJson());
        copy.setStatus("DRAFT");
        copy.setIsDeleted(0);
        schemeMapper.insert(copy);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", copy.getId());
        resp.put("schemeCode", newCode);
        resp.put("schemeName", newName);
        resp.put("ok", true);
        return R.ok(resp);
    }

    // ===================== 工具 =====================

    private Map<String, Object> schemeToMap(EsgScheme s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("schemeCode", s.getSchemeCode());
        m.put("schemeName", s.getSchemeName());
        m.put("description", s.getDescription());
        m.put("dataSource", s.getDataSource());
        m.put("startDate", s.getStartDate() != null ? s.getStartDate().toString() : null);
        m.put("endDate", s.getEndDate() != null ? s.getEndDate().toString() : null);
        m.put("nFactors", s.getNFactors());
        m.put("maturitiesJson", s.getMaturitiesJson());
        m.put("nScenarios", s.getNScenarios());
        m.put("nSteps", s.getNSteps());
        m.put("seed", s.getSeed());
        m.put("initialYieldsJson", s.getInitialYieldsJson());
        m.put("status", s.getStatus());
        m.put("createdBy", s.getCreatedBy());
        m.put("updatedBy", s.getUpdatedBy());
        m.put("createdAt", s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
        m.put("updatedAt", s.getUpdatedAt() != null ? s.getUpdatedAt().toString() : null);
        return m;
    }

    private void decodeJsonFields(Map<String, Object> row) {
        // maturitiesJson -> maturitiesMonths
        Object mj = row.get("maturitiesJson");
        if (mj instanceof String && !((String) mj).isEmpty()) {
            try {
                List<Integer> ms = om.readValue((String) mj, new TypeReference<List<Integer>>() {});
                row.put("maturitiesMonths", ms);
            } catch (Exception e) { log.warn("maturities_json decode failed: {}", e.getMessage()); }
        }
        // initialYieldsJson -> initialYieldsPct
        Object iy = row.get("initialYieldsJson");
        if (iy instanceof String && !((String) iy).isEmpty()) {
            try {
                List<Double> ys = om.readValue((String) iy, new TypeReference<List<Double>>() {});
                row.put("initialYieldsPct", ys);
            } catch (Exception e) { log.warn("initial_yields_json decode failed: {}", e.getMessage()); }
        }
    }

    private String serializeMaturities(Object v1, Object v2) {
        Object v = firstNonNull(v1, v2);
        if (v == null) return null;
        try {
            List<Integer> list = (v instanceof List) ? (List<Integer>) v
                    : om.convertValue(v, new TypeReference<List<Integer>>() {});
            return om.writeValueAsString(list);
        } catch (Exception e) { throw BizException.badRequest("maturities_months 序列化失败: " + e.getMessage()); }
    }

    private String serializeInitialYields(Object v1, Object v2) {
        Object v = firstNonNull(v1, v2);
        if (v == null) return null;
        try {
            List<Double> list = (v instanceof List) ? (List<Double>) v
                    : om.convertValue(v, new TypeReference<List<Double>>() {});
            return om.writeValueAsString(list);
        } catch (Exception e) { throw BizException.badRequest("initial_yields_pct 序列化失败: " + e.getMessage()); }
    }

    private static String emptyToNull(String s) { return (s == null || s.isEmpty()) ? null : s; }
    private static String orDefault(String s, String d) { return (s == null || s.isEmpty()) ? d : s; }
    private static String toStr(Object o) { return o == null ? null : o.toString().trim(); }
    private static Integer toInt(Object... vals) {
        for (Object v : vals) if (v != null) {
            if (v instanceof Number) return ((Number) v).intValue();
            try { return Integer.parseInt(v.toString()); } catch (Exception ignored) {}
        }
        return null;
    }
    private static Integer toIntOrDefault(Object... vals) {
        Integer i = toInt(vals);
        if (i != null) return i;
        return (Integer) vals[vals.length - 1];
    }
    private static java.time.LocalDate toLocalDate(Object... vals) {
        String s = toStr(firstNonNullVals(vals));
        if (s == null) return null;
        try { return java.time.LocalDate.parse(s); } catch (Exception e) { return null; }
    }
    private static Object firstNonNull(Object... vals) {
        for (Object v : vals) if (v != null) return v;
        return null;
    }
    private static Object firstNonNullVals(Object[] vals) {
        for (Object v : vals) if (v != null) return v;
        return null;
    }
}