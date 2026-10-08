package com.prcp.business.params.roe;

import com.prcp.common.exception.BizException;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ROE 参数补录 Service（4 端点）
 * <p>对齐 Python routers/roe_param.py: list_roe_params / list_options / create / update / delete</p>
 *
 * ID 生成：{scheme_code}_{node_code}_{YYYYMMDD}
 * 默认值：is_net_profit = 0, net_profit_symbol = '+', net_profit_factor = 0
 *         is_net_asset   = 0, net_asset_symbol   = '+', net_asset_factor   = 0
 *         status = 'ACTIVE', is_deleted = 0
 *
 * @author PRCP WorkBuddy Agent
 * @date 2026-10-08
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoeParamService {

    private final RoeParamMapper mapper;

    // ============== 1. 列表查询 ==============
    public R<Map<String, Object>> query(Long schemeId, Long nodeId, String dataDate, String keyword) {
        if (dataDate != null && !dataDate.isEmpty()) {
            // 简单格式校验
            try { LocalDate.parse(dataDate); }
            catch (Exception e) { throw BizException.badRequest("data_date 格式应为 YYYY-MM-DD"); }
        }
        List<Map<String, Object>> items = mapper.query(
                schemeId, nodeId,
                emptyToNull(dataDate),
                emptyToNull(keyword));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        resp.put("total", items.size());
        return R.ok(resp);
    }

    // ============== 2. 下拉选项 ==============
    public R<Map<String, Object>> listOptions() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("schemes", mapper.listSchemes());
        resp.put("nodes", mapper.listNodes());
        resp.put("operators", mapper.listOperators());
        resp.put("data_dates", mapper.listDataDates().stream().map(m -> m.get("dataDate")).toList());
        return R.ok(resp);
    }

    // ============== 3. 新增 ==============
    public R<Map<String, Object>> create(Map<String, Object> body) {
        Long schemeId = toLong(body.get("schemeId"), body.get("scheme_id"));
        if (schemeId == null) throw BizException.badRequest("schemeId 必填");
        String schemeCode = toStr(firstNonNull(body.get("schemeCode"), body.get("scheme_code")));
        if (schemeCode == null || schemeCode.isEmpty()) throw BizException.badRequest("schemeCode 必填");

        Long nodeId = toLong(body.get("nodeId"), body.get("node_id"));
        if (nodeId == null) throw BizException.badRequest("nodeId 必填");
        String nodeCode = toStr(firstNonNull(body.get("nodeCode"), body.get("node_code")));
        if (nodeCode == null || nodeCode.isEmpty()) throw BizException.badRequest("nodeCode 必填");

        String dataDateStr = toStr(firstNonNull(body.get("dataDate"), body.get("data_date")));
        if (dataDateStr == null) throw BizException.badRequest("dataDate 必填");
        LocalDate dataDate;
        try { dataDate = LocalDate.parse(dataDateStr); }
        catch (Exception e) { throw BizException.badRequest("data_date 格式应为 YYYY-MM-DD"); }

        // 自动补 node_name（前端未传时）
        String nodeName = toStr(firstNonNull(body.get("nodeName"), body.get("node_name")));
        if (nodeName == null) {
            nodeName = mapper.selectNodeName(nodeId);
        }

        // ID 生成
        String newId = buildId(schemeCode, nodeCode, dataDateStr);

        // 写入实体
        RoeParamEntity e = new RoeParamEntity();
        e.setId(newId);
        e.setSchemeId(schemeId);
        e.setSchemeCode(schemeCode);
        e.setNodeId(nodeId);
        e.setNodeCode(nodeCode);
        e.setNodeName(nodeName);
        e.setDataDate(dataDate);

        e.setIsNetProfit(normInt01(firstNonNull(body.get("isNetProfit"), body.get("is_net_profit"))));
        e.setNetProfitSymbol(orDefault(toStr(firstNonNull(body.get("netProfitSymbol"), body.get("net_profit_symbol"))), "+"));
        e.setNetProfitFactor(toBigDecimal(firstNonNull(body.get("netProfitFactor"), body.get("net_profit_factor")), BigDecimal.ZERO));
        e.setNetProfitCategory(toStr(firstNonNull(body.get("netProfitCategory"), body.get("net_profit_category"))));

        e.setIsNetAsset(normInt01(firstNonNull(body.get("isNetAsset"), body.get("is_net_asset"))));
        e.setNetAssetSymbol(orDefault(toStr(firstNonNull(body.get("netAssetSymbol"), body.get("net_asset_symbol"))), "+"));
        e.setNetAssetFactor(toBigDecimal(firstNonNull(body.get("netAssetFactor"), body.get("net_asset_factor")), BigDecimal.ZERO));
        e.setNetAssetCategory(toStr(firstNonNull(body.get("netAssetCategory"), body.get("net_asset_category"))));

        e.setCurrentBalance(toBigDecimal(firstNonNull(body.get("currentBalance"), body.get("current_balance")), BigDecimal.ZERO));
        e.setRuleNote(toStr(firstNonNull(body.get("ruleNote"), body.get("rule_note"))));
        e.setStatus(orDefault(toStr(body.get("status")), "ACTIVE"));
        e.setIsDeleted(0);

        try {
            mapper.insert(e);
        } catch (Exception ex) {
            log.warn("ROE param insert failed: {}", ex.getMessage());
            throw BizException.badRequest("新增失败：" + ex.getMessage());
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("id", newId);
        return R.ok(resp);
    }

    // ============== 4. 更新（部分更新，跳过 null） ==============
    public R<Map<String, Object>> update(String id, Map<String, Object> body) {
        if (id == null || id.isEmpty()) throw BizException.badRequest("id 必填");

        RoeParamEntity e = new RoeParamEntity();
        e.setId(id);

        if (hasKey(body, "isNetProfit", "is_net_profit"))
            e.setIsNetProfit(normInt01(firstNonNull(body.get("isNetProfit"), body.get("is_net_profit"))));
        if (hasKey(body, "netProfitFactor", "net_profit_factor"))
            e.setNetProfitFactor(toBigDecimal(firstNonNull(body.get("netProfitFactor"), body.get("net_profit_factor")), null));
        if (hasKey(body, "netProfitSymbol", "net_profit_symbol"))
            e.setNetProfitSymbol(toStr(firstNonNull(body.get("netProfitSymbol"), body.get("net_profit_symbol"))));
        if (hasKey(body, "isNetAsset", "is_net_asset"))
            e.setIsNetAsset(normInt01(firstNonNull(body.get("isNetAsset"), body.get("is_net_asset"))));
        if (hasKey(body, "netAssetFactor", "net_asset_factor"))
            e.setNetAssetFactor(toBigDecimal(firstNonNull(body.get("netAssetFactor"), body.get("net_asset_factor")), null));
        if (hasKey(body, "netAssetSymbol", "net_asset_symbol"))
            e.setNetAssetSymbol(toStr(firstNonNull(body.get("netAssetSymbol"), body.get("net_asset_symbol"))));
        if (hasKey(body, "currentBalance", "current_balance"))
            e.setCurrentBalance(toBigDecimal(firstNonNull(body.get("currentBalance"), body.get("current_balance")), null));
        if (hasKey(body, "ruleNote", "rule_note"))
            e.setRuleNote(toStr(firstNonNull(body.get("ruleNote"), body.get("rule_note"))));
        if (hasKey(body, "status"))
            e.setStatus(toStr(body.get("status")));

        int n = mapper.updateById(e);
        if (n == 0) throw BizException.badRequest("记录不存在或未变更");

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("id", id);
        resp.put("updated", n);
        return R.ok(resp);
    }

    // ============== 5. 软删 ==============
    public R<Map<String, Object>> delete(String id) {
        int n = mapper.softDeleteById(id, 1L);
        if (n == 0) throw BizException.badRequest("记录不存在或已删除");
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("id", id);
        return R.ok(resp);
    }

    // ===================== 工具 =====================

    /** ID 生成：{scheme_code}_{node_code}_{YYYYMMDD} */
    private static String buildId(String schemeCode, String nodeCode, String dataDate) {
        String s = dataDate == null ? "" : dataDate.trim();
        if (s.contains("-")) s = s.replace("-", "");
        return schemeCode + "_" + nodeCode + "_" + s;
    }

    private static String emptyToNull(String s) { return (s == null || s.isEmpty()) ? null : s; }
    private static String orDefault(String s, String d) { return (s == null || s.isEmpty()) ? d : s; }
    private static String toStr(Object o) { return o == null ? null : o.toString().trim(); }

    private static Long toLong(Object... vals) {
        for (Object v : vals) if (v != null) {
            if (v instanceof Number) return ((Number) v).longValue();
            try { return Long.parseLong(v.toString()); } catch (Exception ignored) {}
        }
        return null;
    }

    /** 归一化为 0/1 */
    private static Integer normInt01(Object v) {
        if (v == null) return 0;
        if (v instanceof Boolean) return ((Boolean) v) ? 1 : 0;
        if (v instanceof Number) return ((Number) v).intValue() != 0 ? 1 : 0;
        String s = v.toString().trim().toLowerCase();
        return switch (s) {
            case "是", "yes", "y", "true", "1" -> 1;
            default -> 0;
        };
    }

    private static BigDecimal toBigDecimal(Object v, BigDecimal def) {
        if (v == null) return def;
        if (v instanceof BigDecimal) return (BigDecimal) v;
        if (v instanceof Number) return new BigDecimal(v.toString());
        try { return new BigDecimal(v.toString().trim()); }
        catch (Exception e) { return def; }
    }

    private static Object firstNonNull(Object... vals) {
        for (Object v : vals) if (v != null) return v;
        return null;
    }

    private static boolean hasKey(Map<String, Object> body, String... keys) {
        for (String k : keys) if (body.containsKey(k)) return true;
        return false;
    }
}