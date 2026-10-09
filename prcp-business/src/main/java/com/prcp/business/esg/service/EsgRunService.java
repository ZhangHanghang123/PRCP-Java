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
 * <p>ESG 运行历史 Service (3 端点: scheme 级 run / 全局 run / 单 run 详情)</p>
 *
 * <p>核心职责:
 * <ol>
 *   <li>方案级 run 历史 (按 schemeId + runType/status 过滤)</li>
 *   <li>全局 run 历史 (分页 + 多条件)</li>
 *   <li>单 run 详情 (含 JSON 反序列化)</li>
 *   <li>写 run (ExecutionService 调用, 含 params/output JSON 字段)</li>
 * </ol>
 * </p>
 *
 * <p>关键约定:
 * <ul>
 *   <li>JSON 字段: params_json / output_json 读写时自动序列化/反序列化</li>
 *   <li>run 类型: PCA_FIT / HJM_GENERATE / SCENARIO_GENERATE</li>
 *   <li>limit 上限: 200 (防止爆量)</li>
 *   <li>默认 status: SUCCESS</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.esg.mapper.EsgRunMapper
 * @see com.prcp.business.esg.entity.EsgRun
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EsgRunService {

    private final EsgRunMapper mapper;
    private final ObjectMapper om = new ObjectMapper();

    /**
     * <p>方案级 run 历史</p>
     *
     * @param schemeId 方案 ID (必填)
     * @param runType  run 类型 (PCA_FIT/HJM_GENERATE/SCENARIO_GENERATE, 可选)
     * @param status   run 状态 (SUCCESS/FAILED, 可选)
     * @param limit    返回条数 (上限 200)
     * @return R.ok(Map.of("items", list)); JSON 字段已反序列化
     */
    public R<Map<String, Object>> listByScheme(Long schemeId, String runType, String status, int limit) {
        List<Map<String, Object>> items = mapper.listByScheme(schemeId, emptyToNull(runType), emptyToNull(status), Math.min(limit, 200));
        decodeJsonFields(items);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        return R.ok(resp);
    }

    /**
     * <p>全局 run 历史 (分页)</p>
     *
     * @param schemeId 方案 ID (可选, null 表示所有方案)
     * @param runType  run 类型 (可选)
     * @param status   run 状态 (可选)
     * @param page     页码 (从 1 开始)
     * @param pageSize 每页条数
     * @return R.ok(Map.of("items"/"total"/"page"/"pageSize", ...)); JSON 字段已反序列化
     */
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

    /**
     * <p>单 run 详情</p>
     *
     * @param id run 主键 ID (必填)
     * @return R.ok(Map) 含 schemeId/schemeCode/runType/status/params/output/filePath/durationMs/errorMessage/createdAt
     */
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

    /**
     * <p>写 run (ExecutionService 调用)</p>
     *
     * @param schemeId     方案 ID
     * @param schemeCode   方案编码
     * @param runType      run 类型 (PCA_FIT/HJM_GENERATE/SCENARIO_GENERATE)
     * @param status       run 状态 (默认 SUCCESS)
     * @param params       参数 Map (序列化为 params_json)
     * @param output       输出 Map (序列化为 output_json)
     * @param filePath     关联文件路径 (.npz, 可选)
     * @param durationMs   耗时毫秒
     * @param errorMessage 错误信息 (失败时填)
     * @return 写入的 run ID
     */
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