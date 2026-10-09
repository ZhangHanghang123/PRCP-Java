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
 * <p>ESG 情景集 Service (4 端点: list / get / stats / download .npz blob)</p>
 *
 * <p>核心职责:
 * <ol>
 *   <li>情景集列表 (分页 + scheme 过滤)</li>
 *   <li>情景集详情 (不含 pathsBlob, 改用 hasBlob 标记)</li>
 *   <li>预计算统计 (HJM 包络 + 终期分布 + 波动率, 9 JSON 字段反序列化)</li>
 *   <li>下载 .npz (读 paths_blob BLOB)</li>
 *   <li>写情景集 (B+D 双写, ExecutionService 调用)</li>
 * </ol>
 * </p>
 *
 * <p>关键约定:
 * <ul>
 *   <li>9 JSON 字段: percentile10/50/90 + finalMean/Std/Min/Max + volPerMaturity</li>
 *   <li>BLOB: paths_blob 存 .npz 字节流</li>
 *   <li>maturitiesJson 反序列化为 maturitiesMonths (List&lt;Integer&gt;)</li>
 *   <li>pageSize 上限: 100 (防止爆量)</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.esg.mapper.EsgScenarioMapper
 * @see com.prcp.business.esg.entity.EsgScenario
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EsgScenarioService {

    private final EsgScenarioMapper mapper;
    private final ObjectMapper om = new ObjectMapper();

    /**
     * <p>情景集列表 (分页 + scheme 过滤)</p>
     *
     * @param schemeId 方案 ID (可选, null 表示所有方案)
     * @param page     页码 (从 1 开始)
     * @param pageSize 每页条数 (上限 100)
     * @return R.ok(Map.of("items"/"total"/"page"/"pageSize", ...))
     */
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

    /**
     * <p>情景集详情 (不含 pathsBlob 字节, 改用 hasBlob 标记)</p>
     *
     * @param scenarioCode 情景集编码 (必填)
     * @return R.ok(Map); 不存在时抛 badRequest
     */
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

    /**
     * <p>预计算统计 (HJM 包络 + 终期分布 + 波动率, 9 JSON 字段反序列化)</p>
     *
     * @param scenarioCode 情景集编码 (必填)
     * @return R.ok(Map) 含 maturitiesMonths + p10/p50/p90 + finalMean/Std/Min/Max + volPerMaturity + nZeros + nNegatives
     */
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

    /**
     * <p>下载 .npz (读 paths_blob BLOB)</p>
     *
     * @param scenarioCode 情景集编码 (必填)
     * @return .npz 字节流; 不存在时抛 badRequest
     */
    public byte[] downloadNumpy(String scenarioCode) {
        Map<String, Object> row = mapper.selectByCode(scenarioCode);
        if (row == null) throw BizException.badRequest("scenario " + scenarioCode + " 不存在");
        Object blob = row.get("pathsBlob");
        if (blob instanceof byte[]) return (byte[]) blob;
        throw BizException.badRequest("scenario " + scenarioCode + " 数据源不可用（blob 为空）");
    }

    // ===================== 写情景集（B+D 双写） =====================

    /**
     * <p>ExecutionService 调用: 保存情景集 + blob + 9 JSON</p>
     *
     * @param s 完整 EsgScenario 实体 (含 pathsBlob + 9 JSON + maturitiesJson)
     * @return 写入的 scenario ID
     */
    public Long insertScenario(EsgScenario s) {
        mapper.insertScenario(s);
        return s.getId();
    }

    /**
     * <p>更新 last_run_id (关联最近一次 generate run)</p>
     *
     * @param scenarioId 情景集 ID
     * @param runId      关联的 run ID
     * @return 更新行数
     */
    public int updateLastRunId(Long scenarioId, Long runId) {
        return mapper.updateLastRunId(scenarioId, runId);
    }
}