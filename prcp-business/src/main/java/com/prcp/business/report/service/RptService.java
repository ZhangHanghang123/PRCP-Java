package com.prcp.business.report.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.prcp.business.report.entity.RptItem;
import com.prcp.business.report.entity.RptReport;
import com.prcp.business.report.mapper.RptItemMapper;
import com.prcp.business.report.mapper.RptMapper;
import com.prcp.business.report.mapper.RptValueMapper;
import com.prcp.common.exception.BizException;
import com.prcp.common.result.R;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RptService extends ServiceImpl<RptMapper, RptReport> {

    private final RptMapper rptMapper;
    private final RptItemMapper rptItemMapper;
    private final RptValueMapper rptValueMapper;
    private final NamedParameterJdbcTemplate jdbc;

    public R<List<Map<String, Object>>> listReports(String reportType, Long schemeId, String keyword) {
        return R.ok(rptMapper.listReports(reportType, schemeId, keyword));
    }

    public R<?> create(RptReport r) {
        if (r.getReportCode() == null || r.getReportCode().isEmpty())
            throw BizException.badRequest("report_code 不能为空");
        if (r.getReportName() == null || r.getReportName().isEmpty())
            throw BizException.badRequest("report_name 不能为空");
        r.setItemCount(0);
        r.setIsDeleted(0);
        r.setStatus(r.getStatus() == null ? "ACTIVE" : r.getStatus());
        boolean ok = save(r);
        return ok ? R.ok(r) : R.fail("创建失败");
    }

    public R<?> update(Long id, RptReport r) {
        if (rptMapper.selectById(id) == null) throw BizException.notFound("报表不存在");
        r.setId(id);
        boolean ok = updateById(r);
        return ok ? R.ok() : R.fail("更新失败");
    }

    public R<?> softDelete(Long id) {
        if (rptMapper.selectById(id) == null) throw BizException.notFound("报表不存在");
        RptReport upd = new RptReport();
        upd.setId(id);
        upd.setIsDeleted(1);
        upd.setUpdatedAt(LocalDateTime.now());
        boolean ok = updateById(upd);
        return ok ? R.ok() : R.fail("删除失败");
    }

    // ============== 表项 ==============

    public R<List<Map<String, Object>>> listItems(Long reportId) {
        if (reportId == null) throw BizException.badRequest("report_id 不能为空");
        return R.ok(rptMapper.treeItems(reportId));
    }

    public R<?> createItem(RptItem item) {
        if (item.getReportId() == null) throw BizException.badRequest("report_id 不能为空");
        if (item.getItemCode() == null || item.getItemCode().isEmpty())
            throw BizException.badRequest("item_code 不能为空");
        if (item.getItemName() == null || item.getItemName().isEmpty())
            throw BizException.badRequest("item_name 不能为空");
        if (item.getParentId() == null || item.getParentId() == 0) {
            item.setItemLevel(1);
            item.setPath("/");
        } else {
            RptItem parent = rptItemMapper.selectById(item.getParentId());
            if (parent == null) throw BizException.badRequest("父表项不存在");
            item.setItemLevel((parent.getItemLevel() == null ? 1 : parent.getItemLevel()) + 1);
            String base = parent.getPath() == null ? "/" : parent.getPath();
            item.setPath(base + item.getItemCode() + "/");
        }
        item.setIsDeleted(0);
        item.setStatus(item.getStatus() == null ? "ACTIVE" : item.getStatus());
        boolean ok = rptItemMapper.insert(item) > 0;
        if (ok) recountItemCount(item.getReportId());
        return ok ? R.ok(item) : R.fail("创建失败");
    }

    public R<?> updateItem(Long id, RptItem item) {
        if (rptItemMapper.selectById(id) == null) throw BizException.notFound("表项不存在");
        item.setId(id);
        item.setItemLevel(null);
        item.setPath(null);
        item.setParentId(null);
        boolean ok = rptItemMapper.updateById(item) > 0;
        return ok ? R.ok() : R.fail("更新失败");
    }

    public R<?> deleteItem(Long id) {
        RptItem existing = rptItemMapper.selectById(id);
        if (existing == null) throw BizException.notFound("表项不存在");
        Long cnt = rptItemMapper.selectCount(
            new LambdaQueryWrapper<RptItem>().eq(RptItem::getParentId, id).eq(RptItem::getIsDeleted, 0));
        if (cnt != null && cnt > 0) throw BizException.badRequest("该表项下存在子项，不能删除");
        RptItem upd = new RptItem();
        upd.setId(id);
        upd.setIsDeleted(1);
        upd.setUpdatedAt(LocalDateTime.now());
        boolean ok = rptItemMapper.updateById(upd) > 0;
        if (ok) recountItemCount(existing.getReportId());
        return ok ? R.ok() : R.fail("删除失败");
    }

    /** 同步报表的 item_count */
    private void recountItemCount(Long reportId) {
        Long cnt = rptItemMapper.selectCount(
            new LambdaQueryWrapper<RptItem>().eq(RptItem::getReportId, reportId).eq(RptItem::getIsDeleted, 0));
        RptReport upd = new RptReport();
        upd.setId(reportId);
        upd.setItemCount(cnt == null ? 0 : cnt.intValue());
        upd.setUpdatedAt(LocalDateTime.now());
        updateById(upd);
    }

    /**
     * 批量新增 + 修改 + 删除（对齐 Python routers/reports.py batch_items）
     *
     * 入参：report_id（必填，Query）+ body {create: [...], update: [...], delete_ids: [...]}
     *   - create[i]：item_code + item_name + parent_id（可选）+ ... → 自动算 path/level
     *   - update[i]：{id, ...任意可改字段} → 动态 SET（coa_node_ids 自动 JSON 序列化）
     *   - delete_ids[i]：按 path 前缀软删除（含自身 + 所有子节点）
     *
     * 返回：{ok:true, created, updated, deleted}
     * 副作用：增量调整 prcp_rpt_report.item_count（created - deleted）
     */
    @Transactional
    public R<Map<String, Object>> batchItems(Long reportId, Map<String, Object> body) {
        if (reportId == null) throw BizException.badRequest("report_id 必填");
        if (rptMapper.selectById(reportId) == null) throw BizException.badRequest("报表不存在");
        Long uid = 1L; // 与现有 createItem 保持一致（占位）

        int created = 0, updated = 0, deleted = 0;

        // 1) 批量新增
        Object createObj = body == null ? null : body.get("create");
        if (createObj instanceof List) {
            for (Object o : (List<?>) createObj) {
                if (!(o instanceof Map)) continue;
                Map<?, ?> m = (Map<?, ?>) o;
                RptItem item = new RptItem();
                item.setReportId(reportId);
                item.setItemCode(toStr(m.get("item_code")));
                item.setItemName(toStr(m.get("item_name")));
                item.setCategory(toStr(m.get("category")));
                Object parentIdObj = m.get("parent_id");
                Long parentId = parentIdObj == null ? null
                    : (parentIdObj instanceof Number ? ((Number) parentIdObj).longValue()
                       : Long.parseLong(parentIdObj.toString()));
                item.setParentId(parentId);
                item.setDataType(toStr(m.get("data_type")));
                item.setFormula(toStr(m.get("formula")));
                item.setSortOrder(m.get("sort_order") == null ? 0 : ((Number) m.get("sort_order")).intValue());
                item.setStatus(toStr(m.get("status")));
                item.setDescription(toStr(m.get("description")));
                // coa_node_ids：List/Array → JSON；String/Number → 原样
                item.setCoaNodeIds(serializeCoaNodeIds(m.get("coa_node_ids")));

                // 算 path + level
                if (parentId == null || parentId == 0) {
                    item.setItemLevel(1);
                    item.setPath("/" + item.getItemCode() + "/");
                } else {
                    RptItem parent = rptItemMapper.selectById(parentId);
                    if (parent == null) {
                        item.setItemLevel(1);
                        item.setPath("/" + item.getItemCode() + "/");
                    } else {
                        item.setItemLevel((parent.getItemLevel() == null ? 1 : parent.getItemLevel()) + 1);
                        String base = parent.getPath() == null ? "/" : parent.getPath();
                        item.setPath(base + item.getItemCode() + "/");
                    }
                }
                item.setIsDeleted(0);
                if (item.getStatus() == null) item.setStatus("ACTIVE");
                if (rptItemMapper.insert(item) > 0) created++;
            }
        }

        // 2) 批量更新
        Object updateObj = body == null ? null : body.get("update");
        if (updateObj instanceof List) {
            for (Object o : (List<?>) updateObj) {
                if (!(o instanceof Map)) continue;
                Map<?, ?> m = (Map<?, ?>) o;
                Object idObj = m.get("id");
                if (idObj == null) continue;
                Long id = idObj instanceof Number ? ((Number) idObj).longValue() : Long.parseLong(idObj.toString());

                // 字段过滤（id 跳过；保护 path/item_level/parent_id 不被前端乱改）
                Map<String, Object> fields = new LinkedHashMap<>();
                for (String f : new String[]{"item_code", "item_name", "category", "data_type",
                        "formula", "coa_node_ids", "sort_order", "status", "description"}) {
                    if (m.containsKey(f)) fields.put(f, m.get(f));
                }
                if (fields.isEmpty()) continue;

                // coa_node_ids JSON 序列化
                if (fields.containsKey("coa_node_ids")) {
                    fields.put("coa_node_ids", serializeCoaNodeIds(fields.get("coa_node_ids")));
                }
                if (fields.containsKey("sort_order")) {
                    Object so = fields.get("sort_order");
                    if (so instanceof Number) fields.put("sort_order", ((Number) so).intValue());
                }

                // 动态 UPDATE（setClause 用 #{values.key} 占位，values Map 提供参数）
                StringBuilder setClause = new StringBuilder();
                Map<String, Object> values = new java.util.HashMap<>();
                for (Map.Entry<String, Object> e : fields.entrySet()) {
                    if (setClause.length() > 0) setClause.append(", ");
                    // 字段名硬编码（白名单），值用 #{values.xxx} 引用
                    setClause.append(e.getKey()).append(" = #{values.").append(e.getKey()).append("}");
                    values.put(e.getKey(), e.getValue());
                }
                setClause.append(", updated_by = #{values.updated_by}, updated_at = NOW()");
                values.put("updated_by", uid);
                int n = rptItemMapper.updateByDynamic(id, setClause.toString(), values);
                if (n > 0) updated++;
            }
        }

        // 3) 批量删除（按 path 前缀，递归删自身 + 子节点）
        Object delObj = body == null ? null : body.get("delete_ids");
        if (delObj instanceof List) {
            for (Object o : (List<?>) delObj) {
                if (o == null) continue;
                Long id = o instanceof Number ? ((Number) o).longValue() : Long.parseLong(o.toString());
                String path = rptItemMapper.selectPathById(id);
                if (path == null) continue;
                // pathPrefix = path + "%"（对齐 Python f"{path}%"）
                int n = rptItemMapper.softDeleteByPathPrefix(path + "%", uid);
                deleted += n;
            }
        }

        // 4) 增量调整报表 item_count（created - deleted，delta=0 时跳过）
        int delta = created - deleted;
        if (delta != 0) {
            rptItemMapper.adjustReportItemCount(reportId, delta);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("created", created);
        resp.put("updated", updated);
        resp.put("deleted", deleted);
        return R.ok(resp);
    }

    /** coa_node_ids 字段序列化（List → JSON；其它原样） */
    private static String serializeCoaNodeIds(Object o) {
        if (o == null) return null;
        if (o instanceof String) return (String) o;
        if (o instanceof Number) return o.toString();
        if (o instanceof List) {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o);
            } catch (Exception e) {
                return o.toString();
            }
        }
        return o.toString();
    }

    private static String toStr(Object o) { return o == null ? null : o.toString(); }

    // ============== 按月试算（trial-calculate）==============

    /**
     * 按月试算：对所有有 coa_node_ids 的 item，按 data_date 从 prcp_data_basic 聚合 orig_m1 写入 prcp_rpt_value
     *
     * 入参：
     *   dataDate  - 必填，yyyy-MM-dd
     *   category  - 可选（FINANCIAL/SCALE/...），过滤 item.category
     *   reportId  - 可选，限定到某报表
     *
     * coa_node_ids 含义：JSON 数组，引用 prcp_data_basic.id（不是 prcp_coa_node.id）
     *   设计意图：prcp_data_basic 每一行 = 节点 × 时点 × 当前余额；
     *   报表 item 通过引用若干 data_basic.id 实现聚合。
     *
     * 返回：{ count, data_date, results:[{item_id, item_code, item_name, value, action, matched}] }
     */
    @Transactional
    public R<Map<String, Object>> calcByMonth(String dataDate, String category, Long reportId) {
        if (dataDate == null || dataDate.isEmpty()) throw BizException.badRequest("data_date 必填");
        // 1) 查所有 is_deleted=0 + coa_node_ids 非空 + JSON_LENGTH>0 的 item
        StringBuilder sql = new StringBuilder("""
                SELECT id, item_code, item_name, category, coa_node_ids
                FROM prcp_rpt_item
                WHERE is_deleted = 0
                  AND coa_node_ids IS NOT NULL
                  AND JSON_LENGTH(coa_node_ids) > 0
            """);
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (category != null && !category.isEmpty()) {
            sql.append(" AND category = :cat");
            params.addValue("cat", category.toUpperCase());
        }
        if (reportId != null) {
            sql.append(" AND report_id = :rid");
            params.addValue("rid", reportId);
        }
        sql.append(" ORDER BY id");

        List<Map<String, Object>> items = jdbc.queryForList(sql.toString(), params);
        ObjectMapper om = new ObjectMapper();
        List<Map<String, Object>> results = new ArrayList<>();
        int created = 0, updated = 0, noop = 0;

        for (Map<String, Object> it : items) {
            Long itemId = ((Number) it.get("id")).longValue();
            String code = (String) it.get("item_code");
            String name = (String) it.get("item_name");
            String coaJson = (String) it.get("coa_node_ids");
            List<Long> ids;
            try {
                ids = om.readValue(coaJson, new TypeReference<List<Long>>() {});
            } catch (Exception e) {
                continue;
            }
            if (ids == null || ids.isEmpty()) continue;

            // 2) 聚合 prcp_data_basic.orig_m1
            MapSqlParameterSource p2 = new MapSqlParameterSource("dd", dataDate);
            StringBuilder inClause = new StringBuilder();
            for (int i = 0; i < ids.size(); i++) {
                if (i > 0) inClause.append(",");
                inClause.append(":n").append(i);
                p2.addValue("n" + i, ids.get(i));
            }
            String sumSql = String.format("""
                    SELECT COALESCE(SUM(orig_m1),0) FROM prcp_data_basic
                    WHERE id IN (%s) AND data_date = :dd AND is_deleted = 0
                """, inClause);
            BigDecimal sum = jdbc.queryForObject(sumSql, p2, BigDecimal.class);
            if (sum == null) sum = BigDecimal.ZERO;

            // 3) upsert prcp_rpt_value
            Long existingId = rptValueMapper.findIdByItemAndDate(itemId, dataDate);
            String action;
            if (existingId != null) {
                String upd = """
                        UPDATE prcp_rpt_value
                        SET value=:v, source='CALC', updated_by=:uid, updated_at=NOW()
                        WHERE id=:id
                    """;
                jdbc.update(upd, new MapSqlParameterSource()
                    .addValue("v", sum)
                    .addValue("uid", 1L)
                    .addValue("id", existingId));
                action = "updated"; updated++;
            } else {
                String ins = """
                        INSERT INTO prcp_rpt_value (item_id, data_date, value, source, created_by, updated_by)
                        VALUES (:iid, :dd, :v, 'CALC', :uid, :uid)
                    """;
                jdbc.update(ins, new MapSqlParameterSource()
                    .addValue("iid", itemId)
                    .addValue("dd", dataDate)
                    .addValue("v", sum)
                    .addValue("uid", 1L));
                action = "created"; created++;
            }

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("item_id", itemId);
            r.put("item_code", code);
            r.put("item_name", name);
            r.put("category", it.get("category"));
            r.put("value", sum);
            r.put("action", action);
            r.put("matched", ids.size());
            results.add(r);
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("count", results.size());
        resp.put("created", created);
        resp.put("updated", updated);
        resp.put("data_date", dataDate);
        resp.put("results", results);
        return R.ok(resp);
    }

    /**
     * 列某 item 的所有历史值（对齐 Python /data-maint/values）
     */
    public R<List<Map<String, Object>>> listValues(Long itemId, String dataDate) {
        if (itemId == null) throw BizException.badRequest("item_id 必填");
        return R.ok(rptValueMapper.listByItem(itemId, dataDate));
    }

    /**
     * 按 report_id + data_date 取试算结果（preview）
     */
    public R<List<Map<String, Object>>> previewByReport(Long reportId, String dataDate) {
        if (reportId == null) throw BizException.badRequest("report_id 必填");
        if (dataDate == null || dataDate.isEmpty()) throw BizException.badRequest("data_date 必填");
        return R.ok(rptValueMapper.listByReportAndDate(reportId, dataDate));
    }
}