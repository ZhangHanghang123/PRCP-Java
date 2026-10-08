package com.prcp.business.esg.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prcp.business.esg.entity.EsgScenario;
import com.prcp.business.esg.mapper.EsgScenarioMapper;
import com.prcp.common.exception.BizException;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * ESG 情景集服务（4 端点：list / get / stats / download .npz blob）
 * 对齐 Python routers/esg.py scenarios 相关端点
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EsgScenarioService {

    private final EsgScenarioMapper mapper;
    private final ObjectMapper om = new ObjectMapper();

    /** 情景集列表（分页 + scheme 过滤） */
    public R<Map<String, Object>> listScenarios(Long schemeId, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        List<Map<String, Object>> items = mapper.listScenarios(schemeId, Math.min(pageSize, 100), offset);
        int total = mapper.countScenarios(schemeId);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        resp.put("total", total);
        resp.put("page", page);
        resp.put("pageSize", pageSize);
        return R.ok(resp);
    }

    /** 情景集详情 */
    public R<Map<String, Object>> getScenario(String scenarioCode) {
        Map<String, Object> row = mapper.selectByCode(scenarioCode);
        if (row == null) throw BizException.badRequest("scenario " + scenarioCode + " 不存在");
        // 不返回 pathsBlob bytes 字段（太重），改用 hasBlob 标记
        row.remove("pathsBlob");
        // maturitiesJson 反序列化
        Object mj = row.get("maturitiesJson");
        if (mj instanceof String && !((String) mj).isEmpty()) {
            try { row.put("maturitiesMonths", om.readValue((String) mj, new TypeReference<List<Integer>>() {})); }
            catch (Exception ignored) {}
        }
        return R.ok(row);
    }

    /** 预计算统计（HJM 包络 + 终期分布 + 波动率） */
    public R<Map<String, Object>> getStats(String scenarioCode) {
        Map<String, Object> row = mapper.selectByCode(scenarioCode);
        if (row == null) throw BizException.badRequest("scenario " + scenarioCode + " 不存在");

        Object p10 = row.get("percentile10Json");
        if (p10 == null) throw BizException.badRequest("scenario " + scenarioCode + " 没有预计算统计（旧数据请先迁移）");

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("scenarioCode", scenarioCode);
        resp.put("nScenarios", row.get("nScenarios"));
        resp.put("nSteps", row.get("nSteps"));
        resp.put("nMaturities", row.get("nMaturities"));
        resp.put("seed", row.get("seed"));
        try {
            String mj = (String) row.get("maturitiesJson");
            if (mj != null && !mj.isEmpty()) resp.put("maturitiesMonths", om.readValue(mj, new TypeReference<List<Integer>>() {}));
        } catch (Exception ignored) {}
        try { resp.put("p10", om.readValue((String) p10, new TypeReference<List<List<Double>>>() {})); }
        catch (Exception e) { resp.put("p10", p10); }
        try { resp.put("p50", om.readValue((String) row.get("percentile50Json"), new TypeReference<List<List<Double>>>() {})); }
        catch (Exception e) { resp.put("p50", row.get("percentile50Json")); }
        try { resp.put("p90", om.readValue((String) row.get("percentile90Json"), new TypeReference<List<List<Double>>>() {})); }
        catch (Exception e) { resp.put("p90", row.get("percentile90Json")); }
        try { resp.put("finalMean", om.readValue((String) row.get("finalMeanJson"), new TypeReference<List<Double>>() {})); }
        catch (Exception e) { resp.put("finalMean", row.get("finalMeanJson")); }
        try { resp.put("finalStd", om.readValue((String) row.get("finalStdJson"), new TypeReference<List<Double>>() {})); }
        catch (Exception e) { resp.put("finalStd", row.get("finalStdJson")); }
        try { resp.put("finalMin", om.readValue((String) row.get("finalMinJson"), new TypeReference<List<Double>>() {})); }
        catch (Exception e) { resp.put("finalMin", row.get("finalMinJson")); }
        try { resp.put("finalMax", om.readValue((String) row.get("finalMaxJson"), new TypeReference<List<Double>>() {})); }
        catch (Exception e) { resp.put("finalMax", row.get("finalMaxJson")); }
        try { resp.put("volPerMaturity", om.readValue((String) row.get("volPerMaturityJson"), new TypeReference<List<Double>>() {})); }
        catch (Exception e) { resp.put("volPerMaturity", row.get("volPerMaturityJson")); }
        resp.put("nZeros", row.get("nZeros"));
        resp.put("nNegatives", row.get("nNegatives"));
        return R.ok(resp);
    }

    /** 下载 .npz（读 paths_blob BLOB） */
    public byte[] downloadNumpy(String scenarioCode) {
        Map<String, Object> row = mapper.selectByCode(scenarioCode);
        if (row == null) throw BizException.badRequest("scenario " + scenarioCode + " 不存在");
        Object blob = row.get("pathsBlob");
        if (blob instanceof byte[]) return (byte[]) blob;
        throw BizException.badRequest("scenario " + scenarioCode + " 数据源不可用（blob 为空）");
    }

    // ===================== 写情景集（B+D 双写） =====================

    /** ExecutionService 调用：保存情景集 + blob + 9 JSON */
    public Long insertScenario(EsgScenario s) {
        mapper.insertScenario(s);
        return s.getId();
    }

    /** 更新 last_run_id */
    public int updateLastRunId(Long scenarioId, Long runId) {
        return mapper.updateLastRunId(scenarioId, runId);
    }
}