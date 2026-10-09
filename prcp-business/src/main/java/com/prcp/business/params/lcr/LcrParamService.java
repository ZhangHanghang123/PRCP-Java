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
 * <p>LCR (流动性覆盖率) 参数补录 Service</p>
 *
 * <p>核心职责:
 * <ol>
 *   <li>列表查询 (4 个过滤条件)</li>
 *   <li>下拉选项 (4 个 bucket: schemes/nodes/operators/dataDates)</li>
 *   <li>新增 (自动生成主键 + 自动补 node_name + 默认值填充)</li>
 *   <li>LambdaUpdateWrapper partial update</li>
 *   <li>软删除</li>
 * </ol>
 * </p>
 *
 * <p>关键约定:
 * <ul>
 *   <li>复合主键: {scheme_code}_{node_code}_{YYYYMMDD} (例: ZX_COA_S010102010101_20251231)</li>
 *   <li>软删除: is_deleted=1, 不物理删除</li>
 *   <li>默认值: status='ACTIVE', operator='+', is_numerator=0, is_denominator=0</li>
 *   <li>主键冲突兜底: 已存在时抛 badRequest</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.params.lcr.LcrParamMapper
 * @see com.prcp.business.params.lcr.LcrParamEntity
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LcrParamService extends ServiceImpl<LcrParamMapper, LcrParamEntity> {

    private final LcrParamMapper lcrParamMapper;

    /** yyyyMMdd 格式化器 */
    private static final DateTimeFormatter YMD = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * <p>列表查询 (4 个过滤条件, 空串视为无过滤)</p>
     *
     * @param schemeId 方案 ID (可选)
     * @param nodeId   节点 ID (可选)
     * @param dataDate 数据日期 yyyy-MM-dd (可选)
     * @param keyword  关键字 (可选)
     * @return R.ok(Map.of("items", list, "total", list.size()))
     */
    public R<Map<String, Object>> query(Long schemeId, Long nodeId, String dataDate, String keyword) {
        String dd = (dataDate == null || dataDate.isEmpty()) ? null : dataDate;
        String kw = (keyword == null || keyword.isEmpty()) ? null : keyword.trim();
        List<Map<String, Object>> rows = lcrParamMapper.query(schemeId, nodeId, dd, kw);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", rows);
        resp.put("total", rows.size());
        return R.ok(resp);
    }

    /**
     * <p>下拉选项 (从 mapper 一次性取 4 类 bucket, 按 bucket 分组)</p>
     *
     * @return R.ok(Map.of("schemes"/"nodes"/"operators"/"dataDates", ...))
     */
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

    /**
     * <p>新增 (自动生成主键 + 自动补 node_name + 默认值填充 + 主键冲突兜底)</p>
     *
     * @param e LCR 参数实体 (schemeId/schemeCode/nodeId/nodeCode/dataDate 必填)
     * @return R.ok(Map.of("id"/"ok", true)); 主键冲突时抛 badRequest
     */
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

    /**
     * <p>更新 (partial, 使用 LambdaUpdateWrapper 仅 SET 非 null 字段)</p>
     *
     * @param id 主键 ID (必填)
     * @param e  待更新字段
     * @return R.ok(Map.of("ok"/"id"/"updated", N)); 不存在时抛 notFound
     */
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

    /**
     * <p>软删除 (is_deleted=1)</p>
     *
     * @param id 主键 ID (必填)
     * @return R.ok(Map.of("ok"/"id")); 不存在时抛 notFound
     */
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

    /**
     * <p>ID 生成: {scheme_code}_{node_code}_{YYYYMMDD}</p>
     *
     * @param schemeCode 方案编码 (必填)
     * @param nodeCode   节点编码 (必填)
     * @param dataDate   数据日期 (必填)
     * @return 复合主键字符串
     */
    public static String buildId(String schemeCode, String nodeCode, LocalDate dataDate) {
        return schemeCode + "_" + nodeCode + "_" + dataDate.format(YMD);
    }
}
