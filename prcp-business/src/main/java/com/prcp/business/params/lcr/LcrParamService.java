package com.prcp.business.params.lcr;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.prcp.common.exception.BizException;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LCR 参数补录 Service
 * <p>对位 Python app/routers/lcr_param.py</p>
 *
 * <p>ID 生成规则：{scheme_code}_{node_code}_{YYYYMMDD}
 * 例如：ZX_COA_S010102010101_20251231</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LcrParamService extends ServiceImpl<LcrParamMapper, LcrParamEntity> {

    private final LcrParamMapper lcrParamMapper;

    /** yyyyMMdd 格式化器 */
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

    // ============== 1. 列表 ==============
    public R<Map<String, Object>> query(Long schemeId, Long nodeId, String dataDate, String keyword) {
        String dd = (dataDate == null || dataDate.isEmpty()) ? null : dataDate;
        String kw = (keyword == null || keyword.isEmpty()) ? null : keyword.trim();
        List<Map<String, Object>> rows = lcrParamMapper.query(schemeId, nodeId, dd, kw);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", rows);
        resp.put("total", rows.size());
        return R.ok(resp);
    }

    // ============== 2. 下拉选项 ==============
    public R<Map<String, Object>> listOptions() {
        List<Map<String, Object>> rows = lcrParamMapper.listOptionsRaw();
        List<Map<String, Object>> schemes = new ArrayList<>();
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> operators = new ArrayList<>();
        List<String> dates = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String bucket = String.valueOf(r.get("bucket"));
            switch (bucket) {
                case "schemes" -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", r.get("id"));
                    m.put("schemeCode", r.get("code"));
                    m.put("schemeName", r.get("name"));
                    m.put("status", r.get("status"));
                    schemes.add(m);
                }
                case "nodes" -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", r.get("id"));
                    m.put("schemeId", r.get("scheme_id"));
                    m.put("nodeCode", r.get("node_code"));
                    m.put("nodeName", r.get("node_name"));
                    m.put("nodeLevel", r.get("node_level"));
                    m.put("status", r.get("status"));
                    m.put("label", String.format("%s | %s", r.get("node_code"), r.get("node_name")));
                    nodes.add(m);
                }
                case "operators" -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("dictKey", r.get("dict_key"));
                    m.put("dictLabel", r.get("dict_label"));
                    m.put("sortOrder", r.get("sort_order"));
                    operators.add(m);
                }
                case "data_dates" -> {
                    Object dd = r.get("data_date");
                    if (dd != null) {
                        dates.add(dd.toString());
                    }
                }
                default -> log.warn("[listOptions] 未知 bucket={}", bucket);
            }
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("schemes", schemes);
        resp.put("nodes", nodes);
        resp.put("operators", operators);
        resp.put("dataDates", dates);
        return R.ok(resp);
    }

    // ============== 3. 新增 ==============
    @Transactional
    public R<Map<String, Object>> create(LcrParamEntity e) {
        if (e.getSchemeId() == null) throw BizException.badRequest("scheme_id 不能为空");
        if (e.getSchemeCode() == null || e.getSchemeCode().isEmpty())
            throw BizException.badRequest("scheme_code 不能为空");
        if (e.getNodeId() == null) throw BizException.badRequest("node_id 不能为空");
        if (e.getNodeCode() == null || e.getNodeCode().isEmpty())
            throw BizException.badRequest("node_code 不能为空");
        if (e.getDataDate() == null) throw BizException.badRequest("data_date 不能为空");

        // 自动补 node_name（前端没传时从节点表查）
        if (e.getNodeName() == null || e.getNodeName().isEmpty()) {
            String nm = lcrParamMapper.nodeNameById(e.getNodeId());
            e.setNodeName(nm == null ? "" : nm);
        }

        // ID 生成：{scheme_code}_{node_code}_{YYYYMMDD}
        e.setId(buildId(e.getSchemeCode(), e.getNodeCode(), e.getDataDate()));

        // 归一化数值字段
        if (e.getIsNumerator() == null) e.setIsNumerator(0);
        if (e.getIsDenominator() == null) e.setIsDenominator(0);
        if (e.getNumFactor() == null) e.setNumFactor(java.math.BigDecimal.ZERO);
        if (e.getDenFactor() == null) e.setDenFactor(java.math.BigDecimal.ZERO);
        if (e.getCurrentBalance() == null) e.setCurrentBalance(java.math.BigDecimal.ZERO);
        if (e.getNumOperator() == null || e.getNumOperator().isEmpty()) e.setNumOperator("+");
        if (e.getDenOperator() == null || e.getDenOperator().isEmpty()) e.setDenOperator("+");
        if (e.getStatus() == null || e.getStatus().isEmpty()) e.setStatus("ACTIVE");

        // 主键冲突兜底
        if (lcrParamMapper.selectById(e.getId()) != null) {
            throw BizException.badRequest("该记录已存在（ID=" + e.getId() + "），请勿重复新增");
        }

        e.setIsDeleted(0);
        boolean ok = save(e);
        if (!ok) return R.fail("新增失败");

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", e.getId());
        resp.put("ok", true);
        return R.ok(resp);
    }

    // ============== 4. 更新（partial）==============
    public R<Map<String, Object>> update(String id, LcrParamEntity e) {
        if (id == null || id.isEmpty()) throw BizException.badRequest("id 不能为空");

        LambdaUpdateWrapper<LcrParamEntity> uw = new LambdaUpdateWrapper<>();
        uw.eq(LcrParamEntity::getId, id).eq(LcrParamEntity::getIsDeleted, 0);

        // 仅非 null 字段写入 SET（partial update）
        if (e.getIsNumerator() != null)     uw.set(LcrParamEntity::getIsNumerator, e.getIsNumerator());
        if (e.getNumFactor() != null)       uw.set(LcrParamEntity::getNumFactor, e.getNumFactor());
        if (e.getNumOperator() != null)     uw.set(LcrParamEntity::getNumOperator, e.getNumOperator());
        if (e.getIsDenominator() != null)   uw.set(LcrParamEntity::getIsDenominator, e.getIsDenominator());
        if (e.getDenFactor() != null)       uw.set(LcrParamEntity::getDenFactor, e.getDenFactor());
        if (e.getDenOperator() != null)     uw.set(LcrParamEntity::getDenOperator, e.getDenOperator());
        if (e.getCurrentBalance() != null)  uw.set(LcrParamEntity::getCurrentBalance, e.getCurrentBalance());
        if (e.getRuleNote() != null)        uw.set(LcrParamEntity::getRuleNote, e.getRuleNote());
        if (e.getStatus() != null)          uw.set(LcrParamEntity::getStatus, e.getStatus());
        uw.set(LcrParamEntity::getUpdatedAt, java.time.LocalDateTime.now());

        // 没有要更新的字段
        String sqlSet = uw.getSqlSet();
        if (sqlSet == null || sqlSet.isEmpty()) {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", true);
            resp.put("id", id);
            resp.put("updated", 0);
            return R.ok(resp);
        }

        int rows = lcrParamMapper.update(null, uw);
        if (rows == 0) throw BizException.notFound("记录不存在");

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("id", id);
        resp.put("updated", rows);
        return R.ok(resp);
    }

    // ============== 5. 软删 ==============
    public R<Map<String, Object>> softDelete(String id) {
        if (id == null || id.isEmpty()) throw BizException.badRequest("id 不能为空");
        LcrParamEntity existing = lcrParamMapper.selectById(id);
        if (existing == null || Integer.valueOf(1).equals(existing.getIsDeleted())) {
            throw BizException.notFound("记录不存在");
        }
        LcrParamEntity upd = new LcrParamEntity();
        upd.setId(id);
        upd.setIsDeleted(1);
        upd.setUpdatedAt(java.time.LocalDateTime.now());
        boolean ok = updateById(upd);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", ok);
        resp.put("id", id);
        return ok ? R.ok(resp) : R.fail("删除失败");
    }

    // ============== 工具方法 ==============
    /** ID 生成：{scheme_code}_{node_code}_{YYYYMMDD} */
    public static String buildId(String schemeCode, String nodeCode, LocalDate dataDate) {
        return schemeCode + "_" + nodeCode + "_" + dataDate.format(YMD);
    }
}
