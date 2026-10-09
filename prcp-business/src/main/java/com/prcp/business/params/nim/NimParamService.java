package com.prcp.business.params.nim;

import com.prcp.common.exception.BizException;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * <p>NIM (净息差) 参数补录 Service</p>
 *
 * <p>核心职责:
 * <ol>
 *   <li>列表查询 (4 个过滤条件)</li>
 *   <li>下拉选项 (4 类 _type: scheme/node/operator/date)</li>
 *   <li>新增 (自动生成主键 + 自动补 node_name + 默认值)</li>
 *   <li>局部更新 (跳过 null)</li>
 *   <li>软删除</li>
 * </ol>
 * </p>
 *
 * <p>关键约定:
 * <ul>
 *   <li>复合主键: {scheme_code}_{node_code}_{YYYYMMDD}</li>
 *   <li>软删除: is_deleted=1, 不物理删除</li>
 *   <li>默认值: status='ACTIVE', is_interest_asset=0, is_interest_liability=0, operator='+'</li>
 *   <li>操作符按 sort_order + dict_key 排序</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.params.nim.NimParamMapper
 * @see com.prcp.business.params.nim.NimParamEntity
 */
@Service
@RequiredArgsConstructor
public class NimParamService {

    private final NimParamMapper mapper;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * <p>列表查询 (4 个过滤条件, 空串视为无过滤)</p>
     *
     * @param schemeId 方案 ID (Integer, 可选)
     * @param nodeId   节点 ID (Integer, 可选)
     * @param dataDate 数据日期 yyyy-MM-dd (可选)
     * @param keyword  关键字 (可选)
     * @return R.ok(Map.of("items", list, "total", list.size()))
     */
    public R<Map<String, Object>> query(Integer schemeId, Integer nodeId, String dataDate, String keyword) {
        List<Map<String, Object>> items = mapper.query(
            schemeId, nodeId, emptyToNull(dataDate), emptyToNull(keyword));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        resp.put("total", items.size());
        return R.ok(resp);
    }

    /**
     * <p>下拉选项 (从 mapper 一次性取 4 类 _type, 按 _type 分组)</p>
     *
     * @return R.ok(Map.of("schemes"/"nodes"/"operators"/"data_dates", ...))
     */
    public R<Map<String, Object>> listOptions() {
        List<Map<String, Object>> raw = mapper.listOptionsRaw();
        List<Map<String, Object>> schemes = new ArrayList<>();
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> operators = new ArrayList<>();
        List<String> dataDates = new ArrayList<>();

        for (Map<String, Object> row : raw) {
            String type = asStr(row.get("_type"));
            if ("scheme".equals(type)) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("id", row.get("id"));
                s.put("scheme_code", row.get("code"));
                s.put("scheme_name", row.get("name"));
                s.put("status", "ACTIVE");
                schemes.add(s);
            } else if ("node".equals(type)) {
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("id", row.get("id"));
                n.put("scheme_id", row.get("scheme_id"));
                n.put("node_code", row.get("node_code"));
                n.put("node_name", row.get("node_name"));
                n.put("label", (row.get("node_code") == null ? "" : row.get("node_code"))
                    + " | " + (row.get("node_name") == null ? "" : row.get("node_name")));
                nodes.add(n);
            } else if ("operator".equals(type)) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("dict_key", row.get("dict_key"));
                o.put("dict_label", row.get("dict_label"));
                o.put("sort_order", row.get("sort_order"));
                operators.add(o);
            } else if ("date".equals(type)) {
                Object d = row.get("data_date");
                if (d != null) {
                    String ds = d.toString();
                    if (d instanceof java.sql.Date) ds = d.toString();
                    else if (d instanceof LocalDate) ds = ((LocalDate) d).format(DATE_FMT);
                    dataDates.add(ds);
                }
            }
        }
        // 运算符按 sort_order / dict_key 排序
        operators.sort((a, b) -> {
            Integer sa = (Integer) a.get("sort_order");
            Integer sb = (Integer) b.get("sort_order");
            int c = Integer.compare(sa == null ? 0 : sa, sb == null ? 0 : sb);
            return c != 0 ? c : String.valueOf(a.get("dict_key")).compareTo(String.valueOf(b.get("dict_key")));
        });

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("schemes", schemes);
        resp.put("nodes", nodes);
        resp.put("operators", operators);
        resp.put("data_dates", dataDates);
        return R.ok(resp);
    }

    /**
     * <p>新增 (自动生成主键 + 自动补 node_name + 默认值填充)</p>
     *
     * @param p NIM 参数实体 (schemeId/schemeCode/nodeId/nodeCode/dataDate 必填)
     * @return R.ok(Map.of("ok"/"id")); 异常时抛 badRequest
     */
    @Transactional
    public R<Map<String, Object>> create(NimParamEntity p) {
        if (p.getSchemeId() == null) throw BizException.badRequest("scheme_id 不能为空");
        if (p.getSchemeCode() == null || p.getSchemeCode().isEmpty())
            throw BizException.badRequest("scheme_code 不能为空");
        if (p.getNodeId() == null) throw BizException.badRequest("node_id 不能为空");
        if (p.getNodeCode() == null || p.getNodeCode().isEmpty())
            throw BizException.badRequest("node_code 不能为空");
        if (p.getDataDate() == null) throw BizException.badRequest("data_date 不能为空");

        // 主键生成：{scheme_code}_{node_code}_{YYYYMMDD}
        String newId = buildId(p.getSchemeCode(), p.getNodeCode(), p.getDataDate().format(DATE_FMT));
        p.setId(newId);

        // 默认值
        if (p.getIsInterestAsset() == null) p.setIsInterestAsset(0);
        if (p.getAssetOperator() == null || p.getAssetOperator().isEmpty()) p.setAssetOperator("+");
        if (p.getAssetRate() == null) p.setAssetRate(BigDecimal.ZERO);
        if (p.getIsInterestLiability() == null) p.setIsInterestLiability(0);
        if (p.getLiabilityOperator() == null || p.getLiabilityOperator().isEmpty()) p.setLiabilityOperator("+");
        if (p.getLiabilityRate() == null) p.setLiabilityRate(BigDecimal.ZERO);
        if (p.getCurrentBalance() == null) p.setCurrentBalance(BigDecimal.ZERO);
        if (p.getStatus() == null || p.getStatus().isEmpty()) p.setStatus("ACTIVE");
        p.setIsDeleted(0);

        // 自动补 node_name（前端未传时）
        if (p.getNodeName() == null || p.getNodeName().isEmpty()) {
            p.setNodeName(lookupNodeName(p.getNodeId()));
        }

        try {
            int n = mapper.insert(p);
            if (n == 0) return R.fail("新增失败");
        } catch (Exception e) {
            throw BizException.badRequest("新增失败：" + e.getMessage());
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("id", newId);
        return R.ok(resp);
    }

    /**
     * <p>更新 (局部更新, 跳过 null)</p>
     *
     * @param id    主键 ID (必填)
     * @param patch 待更新字段
     * @return R.ok(Map.of("ok"/"id"/"updated", N)); 记录不存在或未变更时抛 notFound
     */
    public R<Map<String, Object>> update(String id, NimParamEntity patch) {
        if (id == null || id.isEmpty()) throw BizException.badRequest("id 不能为空");

        // 只允许更新以下业务字段
        NimParamEntity upd = new NimParamEntity();
        upd.setId(id);
        if (patch.getIsInterestAsset() != null) upd.setIsInterestAsset(patch.getIsInterestAsset());
        if (patch.getAssetRate() != null) upd.setAssetRate(patch.getAssetRate());
        if (patch.getAssetOperator() != null && !patch.getAssetOperator().isEmpty())
            upd.setAssetOperator(patch.getAssetOperator());
        if (patch.getIsInterestLiability() != null) upd.setIsInterestLiability(patch.getIsInterestLiability());
        if (patch.getLiabilityRate() != null) upd.setLiabilityRate(patch.getLiabilityRate());
        if (patch.getLiabilityOperator() != null && !patch.getLiabilityOperator().isEmpty())
            upd.setLiabilityOperator(patch.getLiabilityOperator());
        if (patch.getCurrentBalance() != null) upd.setCurrentBalance(patch.getCurrentBalance());
        if (patch.getRuleNote() != null) upd.setRuleNote(patch.getRuleNote());
        if (patch.getStatus() != null && !patch.getStatus().isEmpty()) upd.setStatus(patch.getStatus());

        int n = mapper.updateById(upd);
        if (n == 0) throw BizException.notFound("记录不存在或未变更");

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("id", id);
        resp.put("updated", n);
        return R.ok(resp);
    }

    /**
     * <p>软删除 (is_deleted=1)</p>
     *
     * @param id 主键 ID (必填)
     * @return R.ok(Map.of("ok"/"id")); 不存在时抛 notFound
     */
    public R<Map<String, Object>> delete(String id) {
        if (id == null || id.isEmpty()) throw BizException.badRequest("id 不能为空");

        NimParamEntity upd = new NimParamEntity();
        upd.setId(id);
        upd.setIsDeleted(1);
        int n = mapper.updateById(upd);
        if (n == 0) throw BizException.notFound("记录不存在");

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("id", id);
        return R.ok(resp);
    }

    // ============ 工具 ============
    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    private static String asStr(Object o) {
        return o == null ? null : o.toString();
    }

    /** ID 生成：{scheme_code}_{node_code}_{YYYYMMDD} */
    static String buildId(String schemeCode, String nodeCode, String dataDate) {
        String s = dataDate == null ? "" : dataDate.trim().replace("-", "").replace("/", "");
        return schemeCode + "_" + nodeCode + "_" + s;
    }

    private String lookupNodeName(Integer nodeId) {
        try {
            return mapper.selectById(nodeId.toString()) == null ? "" : null;
        } catch (Exception e) {
            return "";
        }
    }
}
