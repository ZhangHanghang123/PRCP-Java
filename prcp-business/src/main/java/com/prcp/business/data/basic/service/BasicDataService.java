package com.prcp.business.data.basic.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.alibaba.excel.write.metadata.fill.FillConfig;
import com.alibaba.excel.write.style.column.LongestMatchColumnWidthStyleStrategy;
import com.prcp.business.data.BasicDataBuckets;
import com.prcp.business.data.basic.mapper.BasicDataMapper;
import com.prcp.common.exception.BizException;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>基础数据 Service (PRCP 64 桶 + 4 期限桶 + 7 度量)</p>
 *
 * <p>核心职责:
 * <ol>
 *   <li>列表查询 (按方案/节点/日期/类别/关键字)</li>
 *   <li>按方案二维矩阵 (节点 × 64 桶 + 大类汇总)</li>
 *   <li>Upsert (4 元组唯一键 + 自动补节点元数据)</li>
 *   <li>逻辑删除 (单/批量)</li>
 *   <li>Excel 145 列宽表导出</li>
 *   <li>Excel 导入/预览 (dryRun 支持)</li>
 * </ol>
 * </p>
 *
 * <p>关键约定:
 * <ul>
 *   <li>桶维度: m1..m60 (60 月) + y10/y15/y20/y30 (10/15/20/30 年) = 64 桶</li>
 *   <li>期限类型: orig (原始期限) + rem (剩余期限) = 128 桶</li>
 *   <li>度量: asf_rsf/hqla_factor/current_balance/avg_balance/weighted_rate/interest_amount/risk_weight</li>
 *   <li>唯一约束: (coaNodeId, dataDate, dateOffset, offsetUnit)</li>
 *   <li>软删除: is_deleted=1</li>
 *   <li>分类规则: ZX_A*=资产 / ZX_L*=负债 / ZX_E*=权益 / path 中 L1_*</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.data.basic.mapper.BasicDataMapper
 * @see com.prcp.business.data.BasicDataBuckets
 */
@Service
@RequiredArgsConstructor
public class BasicDataService {

    private final BasicDataMapper mapper;
    private static final List<String> BUCKET_KEYS = BasicDataBuckets.KEYS;
    private static final List<String> DISPLAY_KEYS = Arrays.asList(BasicDataBuckets.DISPLAY_BUCKETS);

    /**
     * <p>列表查询 (按方案/节点/日期/类别/关键字过滤)</p>
     *
     * <p>返回全 64 桶 + 7 度量 + 节点元数据; 任意过滤参数为空都视为不限制</p>
     *
     * @param schemeId  方案 ID (可选)
     * @param coaNodeId 节点 ID (可选)
     * @param startDate 起始日期 yyyy-MM-dd (可选)
     * @param endDate   结束日期 yyyy-MM-dd (可选)
     * @param dataDate  精确数据日期 yyyy-MM-dd (可选)
     * @param category  大类 (资产/负债/权益/其他, 可选)
     * @param nodeKw    节点关键字 (匹配 code/name, 可选)
     * @return 行 Map 列表, 每行含 64 桶 (orig/rem) + 7 度量
     */
    public R<List<Map<String, Object>>> list(Long schemeId, Long coaNodeId, String startDate, String endDate,
                                              String dataDate, String category, String nodeKw) {
        return R.ok(mapper.list(schemeId, coaNodeId,
                emptyToNull(startDate), emptyToNull(endDate),
                emptyToNull(dataDate), emptyToNull(category), emptyToNull(nodeKw),
                BUCKET_KEYS));
    }

    /**
     * <p>查询已录入的数据日期列表 (按方案过滤)</p>
     *
     * @param schemeId 方案 ID (可选)
     * @return 日期字符串列表 yyyy-MM-dd
     */
    public R<List<String>> dates(Long schemeId) {
        return R.ok(mapper.dates(schemeId));
    }

    /**
     * <p>按方案 × 单日的二维矩阵查询 (对齐 Python basic.py by-scheme-matrix)</p>
     *
     * <p>输出 nodes (方案下所有节点), matrix[nodeId] (7 度量 + 64 桶 orig/rem), categories (按 L1 大类汇总)</p>
     *
     * @param schemeId    方案 ID (必填)
     * @param dataDate    数据日期 yyyy-MM-dd (必填)
     * @param dateOffset  日期偏移 (可选, 默认 0)
     * @param offsetUnit  偏移单位 D/M/Y (可选, 默认 D)
     * @return R.ok(Map.of("schemeId"/"dataDate"/"dateOffset"/"offsetUnit"/"nodes"/"matrix"/"categories"/"buckets", ...))
     */
    public R<Map<String, Object>> bySchemeMatrix(Long schemeId, String dataDate,
                                                  Integer dateOffset, String offsetUnit) {
        if (schemeId == null) throw BizException.badRequest("schemeId 必填");
        if (emptyToNull(dataDate) == null) throw BizException.badRequest("dataDate 必填");
        int off = dateOffset == null ? 0 : dateOffset;
        String unit = emptyToNull(offsetUnit);
        if (unit == null) unit = "D";

        // 1) 取方案下所有节点
        List<Map<String, Object>> nodeRows = mapper.listNodesByScheme(schemeId);

        // 2) 取该 (schemeId, dataDate, dateOffset, offsetUnit) 下的全部数据
        List<Map<String, Object>> rows = mapper.listBySchemeMatrix(schemeId, dataDate, off, unit, BUCKET_KEYS);

        // 3) 组装 matrix：{nodeId(str): {asf_rsf, hqla_factor, ..., orig_m1..y30, rem_m1..y30}}
        Map<String, Map<String, Object>> matrix = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Object cidObj = r.get("coaNodeId");
            if (cidObj == null) continue;
            String cid = String.valueOf(cidObj);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("asfRsf", r.get("asfRsf"));
            m.put("hqlaFactor", r.get("hqlaFactor"));
            m.put("currentBalance", r.get("currentBalance"));
            m.put("avgBalance", r.get("avgBalance"));
            m.put("weightedRate", r.get("weightedRate"));
            m.put("interestAmount", r.get("interestAmount"));
            m.put("riskWeight", r.get("riskWeight"));
            for (String b : BUCKET_KEYS) {
                m.put("orig" + camel(b), r.get("orig" + camel(b)));
                m.put("rem" + camel(b), r.get("rem" + camel(b)));
            }
            matrix.put(cid, m);
        }

        // 4) 大类汇总
        List<String> NUMERIC_KEYS = new ArrayList<>();
        for (String b : BUCKET_KEYS) {
            NUMERIC_KEYS.add("orig" + camel(b));
            NUMERIC_KEYS.add("rem" + camel(b));
        }
        NUMERIC_KEYS.add("currentBalance");
        NUMERIC_KEYS.add("avgBalance");
        NUMERIC_KEYS.add("weightedRate");
        NUMERIC_KEYS.add("interestAmount");
        NUMERIC_KEYS.add("riskWeight");

        // 节点 byId 索引（key 统一用 String，避免 Long/String 双类型导致 lookup miss）
        Map<String, Map<String, Object>> nodeById = new LinkedHashMap<>();
        Set<String> l1Cids = new LinkedHashSet<>();
        for (Map<String, Object> n : nodeRows) {
            Object nid = n.get("coaNodeId");
            String nidStr = nid == null ? null : String.valueOf(nid);
            if (nidStr != null) nodeById.put(nidStr, n);
            Object lv = n.get("nodeLevel");
            if (nidStr != null && lv != null && Integer.parseInt(lv.toString()) == 1) {
                l1Cids.add(nidStr);
            }
        }

        // 优先汇总 L1（如 L1 有数据则按 L1 汇总，否则按叶子汇总）
        boolean l1HasData = false;
        for (String cid : l1Cids) {
            Map<String, Object> m = matrix.get(cid);
            if (m != null) {
                Object cb = m.get("currentBalance");
                if (cb != null && ((Number) cb).doubleValue() != 0) { l1HasData = true; break; }
            }
        }
        Set<String> targetCids = l1HasData ? l1Cids : matrix.keySet();

        Map<String, Map<String, Double>> categories = new LinkedHashMap<>();
        for (String cid : targetCids) {
            if (!matrix.containsKey(cid)) continue;
            Map<String, Object> n = nodeById.get(cid);
            if (n == null) continue;
            String cat = classifyCategory(n);
            Map<String, Double> bucket = categories.computeIfAbsent(cat, ck -> {
                Map<String, Double> b = new LinkedHashMap<>();
                for (String nk : NUMERIC_KEYS) b.put(nk, 0.0);
                return b;
            });
            Map<String, Object> m = matrix.get(String.valueOf(cid));
            for (String k : NUMERIC_KEYS) {
                Object v = m.get(k);
                double d = 0;
                if (v != null) {
                    try { d = (v instanceof Number) ? ((Number) v).doubleValue() : Double.parseDouble(v.toString()); }
                    catch (Exception ignore) {}
                }
                bucket.put(k, round2(bucket.get(k) + d));
            }
        }

        // 5) 返回
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemeId", schemeId);
        result.put("dataDate", dataDate);
        result.put("dateOffset", off);
        result.put("offsetUnit", unit);
        result.put("nodes", nodeRows);
        result.put("matrix", matrix);
        result.put("categories", categories);
        result.put("buckets", BUCKET_KEYS);  // 前端按这个顺序横展
        return R.ok(result);
    }

    /** 节点 → 大类归类（与 Python 版 classify_category 对齐） */
    private static String classifyCategory(Map<String, Object> node) {
        Object codeObj = node.get("nodeCode");
        Object pathObj = node.get("path");
        String code = codeObj == null ? "" : codeObj.toString();
        String path = pathObj == null ? "" : pathObj.toString();
        if (code.startsWith("ZX_A")) return "资产";
        if (code.startsWith("ZX_L")) return "负债";
        if (code.startsWith("ZX_E")) return "权益";
        // path 形式：/L1_ASSET/... 取 L1_ 后面的部分
        if (!path.isEmpty()) {
            String[] parts = path.split("/");
            for (String p : parts) {
                if (p.startsWith("L1_")) {
                    return p.substring(3);
                }
            }
        }
        // 否则用 node_type 推断
        Object ntObj = node.get("nodeType");
        if (ntObj != null) {
            String nt = ntObj.toString();
            if (nt.startsWith("ASSET")) return "资产";
            if (nt.startsWith("LIAB")) return "负债";
            if (nt.startsWith("EQUITY")) return "权益";
        }
        return "其他";
    }

    private static double round2(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    /**
     * <p>矩阵: 节点 × 桶 (按月分桶全 64 + 4), 含 7 度量</p>
     *
     * <p>前端按 buckets 顺序横展: m1..m60 + y10/y15/y20/y30</p>
     *
     * @param schemeId 方案 ID
     * @param dataDate 数据日期 yyyy-MM-dd (可选)
     * @return R.ok(Map.of("buckets"/"dates"/"rows"/"totalRows", ...))
     */
    public R<Map<String, Object>> matrix(Long schemeId, String dataDate) {
        // 用全 64 桶（m1..m60 + y10/15/20/30）替代 8 代表桶
        List<Map<String, Object>> raw = mapper.matrix(schemeId, emptyToNull(dataDate), BUCKET_KEYS);
        Map<String, Map<String, Object>> rowMap = new LinkedHashMap<>();
        Set<String> dateSet = new LinkedHashSet<>();
        for (Map<String, Object> r : raw) {
            String nodeKey = (String) r.get("nodeCode");
            if (nodeKey == null) continue;
            dateSet.add((String) r.get("dataDate"));
            Map<String, Object> row = rowMap.computeIfAbsent(nodeKey, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("nodeCode", nodeKey);
                m.put("nodeName", r.get("nodeName"));
                m.put("category", r.get("category"));
                m.put("nodeLevel", r.get("nodeLevel"));
                m.put("dateOffset", r.get("dateOffset"));
                m.put("offsetUnit", r.get("offsetUnit"));
                m.put("asfRsf", r.get("asfRsf"));
                m.put("hqlaFactor", r.get("hqlaFactor"));
                m.put("currentBalance", r.get("currentBalance"));
                m.put("avgBalance", r.get("avgBalance"));
                m.put("weightedRate", r.get("weightedRate"));
                m.put("interestAmount", r.get("interestAmount"));
                m.put("riskWeight", r.get("riskWeight"));
                m.put("orig", new LinkedHashMap<String, Object>());
                m.put("rem", new LinkedHashMap<String, Object>());
                return m;
            });
            // 把每个桶的 orig 和 rem 值按 bucket 维度塞进 map
            Map<String, Object> origMap = (Map<String, Object>) row.get("orig");
            Map<String, Object> remMap = (Map<String, Object>) row.get("rem");
            for (String b : BUCKET_KEYS) {
                origMap.put(b, r.get("orig" + camel(b)));
                remMap.put(b, r.get("rem" + camel(b)));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("buckets", BUCKET_KEYS);  // 前端按这个顺序横展：m1..m60 + y10/y15/y20/y30
        result.put("dates", new ArrayList<>(dateSet));
        result.put("rows", new ArrayList<>(rowMap.values()));
        result.put("totalRows", rowMap.size());
        return R.ok(result);
    }

    /**
     * <p>Upsert (4 元组唯一键 + 64 桶 + 7 度量 + 自动补节点元数据)</p>
     *
     * <p>4 元组: (coaNodeId, dataDate, dateOffset, offsetUnit); 自动从 prcp_coa_node 加载节点元数据 (code/name/parentCode/level/leaf/category)</p>
     *
     * @param body 含 coaNodeId/dataDate + 桶 (orig_* / rem_*) + 度量 (asf_rsf 等) + 元数据字段
     * @return R.ok(Map.of("id"|null + "mode", "insert"|"update")); 至少传一个字段
     */
    @Transactional
    public R<?> upsert(Map<String, Object> body) {
        Object nid = body.get("coaNodeId");
        Object dt = body.get("dataDate");
        if (nid == null) throw BizException.badRequest("coaNodeId 必填");
        if (dt == null) throw BizException.badRequest("dataDate 必填");

        Long nodeId = ((Number) nid).longValue();
        String dataDate = dt.toString();
        Integer dateOffset = toInt(body.get("dateOffset"), 0);
        String offsetUnit = emptyToNull((String) body.get("offsetUnit"));
        if (offsetUnit == null) offsetUnit = "D";

        // 自动补节点元数据（与 Python 版 BasicIn.upsert 一致）
        NodeMeta meta = loadNodeMeta(nodeId);
        // body 中显式传的字段优先覆盖 meta（如前端手动改了 category）
        if (body.get("nodeCode") != null) meta.nodeCode = (String) body.get("nodeCode");
        if (body.get("nodeName") != null) meta.nodeName = (String) body.get("nodeName");
        if (body.get("parentCode") != null) meta.parentCode = (String) body.get("parentCode");
        if (body.get("category") != null) meta.category = (String) body.get("category");
        if (body.get("nodeLevel") != null) meta.nodeLevel = toInt(body.get("nodeLevel"), null);
        if (body.get("isLeaf") != null) meta.isLeaf = toInt(body.get("isLeaf"), null);

        // 收集所有要写入的字段（桶 + 度量 + 流动性 + 说明）
        Map<String, Object> toWrite = new LinkedHashMap<>();
        for (String k : BUCKET_KEYS) {
            String origKey = "orig_" + k;
            String remKey = "rem_" + k;
            if (body.containsKey(origKey)) toWrite.put(origKey, body.get(origKey));
            if (body.containsKey(remKey)) toWrite.put(remKey, body.get(remKey));
        }
        // 度量/流动性/说明
        for (String f : new String[]{"asf_rsf", "hqla_factor", "current_balance", "avg_balance",
                "weighted_rate", "interest_amount", "risk_weight", "calc_note"}) {
            if (body.containsKey(f)) toWrite.put(f, body.get(f));
        }

        if (toWrite.isEmpty()) throw BizException.badRequest("至少传一个字段");

        // 拼接 SET 子句（update）和 INSERT 列名+值
        StringBuilder setSb = new StringBuilder();
        StringBuilder colSb = new StringBuilder();
        List<Object> args = new ArrayList<>();
        for (Map.Entry<String, Object> e : toWrite.entrySet()) {
            if (setSb.length() > 0) setSb.append(", ");
            setSb.append(e.getKey()).append(" = ?");
            if (colSb.length() > 0) colSb.append(", ");
            colSb.append(e.getKey());
            args.add(toValue(e.getKey(), e.getValue()));
        }

        Long id = mapper.findId(nodeId, dataDate, dateOffset, offsetUnit);
        if (id != null) {
            mapper.updateByDynamic(id, setSb.toString(), args);
            return R.ok(Map.of("id", id, "mode", "update"));
        } else {
            mapper.insertByDynamic(dataDate, nodeId, dateOffset, offsetUnit,
                    colSb.toString(), args,
                    meta.nodeCode, meta.nodeName, meta.nodeLevel, meta.parentCode,
                    meta.isLeaf, meta.category);
            return R.ok(Map.of("mode", "insert"));
        }
    }

    /**
     * <p>逻辑删除 (单条, is_deleted=1)</p>
     *
     * @param id 基础数据主键 ID (必填)
     * @return R.ok()
     */
    public R<?> delete(Long id) {
        mapper.softDeleteById(id);
        return R.ok();
    }

    /**
     * <p>批量逻辑删除 (is_deleted=1)</p>
     *
     * @param body 含 ids 数组 (Number 或字符串均可)
     * @return R.ok(Map.of("deleted", N)); ids 缺失或解析失败时抛 badRequest
     */
    @Transactional
    public R<Map<String, Object>> deleteBatch(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("ids");
        if (!(raw instanceof List)) throw BizException.badRequest("ids 必填（数组）");
        List<?> rawList = (List<?>) raw;
        if (rawList.isEmpty()) throw BizException.badRequest("ids 不能为空");
        List<Long> ids = new ArrayList<>();
        for (Object o : rawList) {
            if (o instanceof Number) ids.add(((Number) o).longValue());
            else if (o != null) {
                try { ids.add(Long.parseLong(o.toString().trim())); } catch (Exception ignore) {}
            }
        }
        if (ids.isEmpty()) throw BizException.badRequest("ids 解析失败");
        int deleted = mapper.softDeleteByIds(ids);
        return R.ok(Map.of("deleted", deleted));
    }

    // ============================================================
    // Excel 导出 / 导入 / 预览
    // ============================================================

    /**
     * <p>导出 145 列宽表 xlsx (对齐 Python 版 export-xlsx)</p>
     *
     * <p>表头: 第 1 行 = 4 大分组, 第 2 行 = bucket/字段名, 第 3 行 = 详细列名; 数据从第 4 行开始</p>
     *
     * @param schemeId   方案 ID (可选, 为空则全部)
     * @param dataDate   数据日期 yyyy-MM-dd (必填)
     * @param dateOffset 日期偏移 (可选, 默认 0)
     * @param offsetUnit 偏移单位 D/M/Y (可选, 默认 D)
     * @param response   HTTP 响应 (用于直接写出 xlsx 字节流)
     * @throws IOException 写出失败时
     */
    public void exportXlsx(Long schemeId, String dataDate, Integer dateOffset,
                           String offsetUnit, HttpServletResponse response) throws IOException {
        // 1) 校验参数（异常必须先抛，不写到 response）
        if (dataDate == null || dataDate.isEmpty()) throw BizException.badRequest("dataDate 必填");
        int off = dateOffset == null ? 0 : dateOffset;
        String unit = emptyToNull(offsetUnit);
        if (unit == null) unit = "D";

        // 2) 查数据（异常也先抛）
        List<Map<String, Object>> rows = mapper.list(schemeId, null, null, null,
                dataDate, null, null, BUCKET_KEYS);
        rows.sort(Comparator.comparing(r -> {
            Object id = r.get("coaNodeId");
            return id == null ? 0L : ((Number) id).longValue();
        }));

        // 3) 构造表头
        List<String> head1 = new ArrayList<>();
        List<String> head2 = new ArrayList<>();
        List<String> head3 = new ArrayList<>();
        String[] baseHdr = {"数据日期", "节点ID", "节点编码", "节点名称", "节点层级",
                "父节点编码", "是否叶子", "类别", "日期偏移", "偏移单位"};
        for (String h : baseHdr) {
            head1.add("基础信息");
            head2.add("");
            head3.add(h);
        }
        for (String b : BUCKET_KEYS) {
            head1.add("原始期限");
            head2.add(b);
            head3.add("orig_" + b);
        }
        for (String b : BUCKET_KEYS) {
            head1.add("剩余期限");
            head2.add(b);
            head3.add("rem_" + b);
        }
        String[] measHdr = {"asf_rsf", "hqla_factor", "current_balance", "avg_balance",
                "weighted_rate(%)", "interest_amount", "risk_weight(%)"};
        for (String h : measHdr) {
            head1.add("度量");
            head2.add("");
            head3.add(h);
        }
        List<List<String>> head = Arrays.asList(head1, head2, head3);

        // 4) 构造数据行
        List<List<Object>> data = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            List<Object> row = new ArrayList<>();
            // dataDate 可能是 java.sql.Date 或 String，转 String 避免 EasyExcel 找不到 Date Converter
            Object dd = r.get("dataDate");
            row.add(dd == null ? "" : dd.toString().substring(0, 10));
            row.add(r.get("coaNodeId"));
            row.add(r.get("nodeCode"));
            row.add(r.get("nodeName"));
            row.add(r.get("nodeLevel"));
            row.add(r.get("parentCode"));
            row.add(r.get("isLeaf"));
            row.add(r.get("category"));
            row.add(r.get("dateOffset"));
            row.add(r.get("offsetUnit"));
            for (String b : BUCKET_KEYS) {
                row.add(r.get("orig" + camel(b)));
            }
            for (String b : BUCKET_KEYS) {
                row.add(r.get("rem" + camel(b)));
            }
            row.add(r.get("asfRsf"));
            row.add(r.get("hqlaFactor"));
            row.add(r.get("currentBalance"));
            row.add(r.get("avgBalance"));
            Object wr = r.get("weightedRate");
            row.add(wr == null ? null : toBigDecimal(wr).multiply(BigDecimal.valueOf(100)));
            row.add(r.get("interestAmount"));
            Object rw = r.get("riskWeight");
            row.add(rw == null ? null : toBigDecimal(rw).multiply(BigDecimal.valueOf(100)));
            data.add(row);
        }

        // 5) 先写到 ByteArrayOutputStream，最后才写入 response（异常可控）
        String dateStr = dataDate.replace("-", "");
        String fn = "prcp_basic_" + dateStr + "_" + off + unit + ".xlsx";
        byte[] xlsxBytes;
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        ExcelWriter writer = EasyExcel.write(baos).head(head).build();
        try {
            WriteSheet sheet = EasyExcel.writerSheet(0, "basic_data")
                    .registerWriteHandler(new LongestMatchColumnWidthStyleStrategy())
                    .build();
            writer.write(data, sheet);
            writer.finish();
        } finally {
            writer.close();
        }
        xlsxBytes = baos.toByteArray();

        // 6) 设置响应头 + 写入字节（此时不会抛异常）
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition", "attachment; filename=\""
                + URLEncoder.encode(fn, StandardCharsets.UTF_8) + "\"");
        response.setContentLength(xlsxBytes.length);
        response.getOutputStream().write(xlsxBytes);
        response.getOutputStream().flush();
    }

    /**
     * <p>Excel 导入 / 预览 (dryRun=true 时只校验不入库)</p>
     *
     * <p>期望 xlsx 表头: 第 1 行=分组, 第 2 行=bucket/字段, 第 3 行=详细列名, 第 4+ 行=数据</p>
     *
     * @param file       上传的 xlsx 文件 (必填)
     * @param schemeId   方案 ID
     * @param dateOffset 日期偏移 (可选, 默认 0)
     * @param offsetUnit 偏移单位 D/M/Y (可选, 默认 D)
     * @param dryRun     true=只校验不入库, false=真正 upsert
     * @return R.ok(Map.of("inserted"/"updated"/"skipped"/"totalErrors"/"errors", ...)); 错误超过 1000 行截断
     */
    @Transactional
    public R<Map<String, Object>> importXlsx(MultipartFile file, Long schemeId,
                                              Integer dateOffset, String offsetUnit, Boolean dryRun) {
        if (file == null || file.isEmpty()) throw BizException.badRequest("file 必填");
        boolean dry = Boolean.TRUE.equals(dryRun);
        int off = dateOffset == null ? 0 : dateOffset;
        String unit = emptyToNull(offsetUnit);
        if (unit == null) unit = "D";

        // 列索引映射（按 head[2] 详细列名解析）
        Map<String, Integer> idx = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        // 解析：单独读表头 + 数据
        try (InputStream in = file.getInputStream()) {
            List<List<Object>> allRows = EasyExcel.read(in).headRowNumber(3).doReadAllSync();
            // allRows[0..2] = 表头（基础信息/原始期限/剩余期限/度量, bucket key, 详细列名）
            // allRows[3..] = 数据
            if (allRows.size() < 4) {
                return R.ok(Map.of("inserted", 0, "updated", 0, "skipped", 0,
                        "totalErrors", 1, "errors", List.of("文件少于 4 行，表头不完整")));
            }
            List<Object> detailHeader = allRows.get(2); // 第 3 行：详细列名
            Map<String, Integer> colIdx = new LinkedHashMap<>();
            for (int i = 0; i < detailHeader.size(); i++) {
                Object h = detailHeader.get(i);
                if (h != null) colIdx.put(h.toString().trim(), i);
            }

            // 必须的列
            for (String must : new String[]{"节点ID", "数据日期"}) {
                if (!colIdx.containsKey(must)) {
                    throw BizException.badRequest("缺少必需列：" + must);
                }
            }

            int inserted = 0, updated = 0, skipped = 0;
            for (int r = 3; r < allRows.size(); r++) {
                List<Object> row = allRows.get(r);
                if (row == null || row.isEmpty()) continue;
                // 检查是否全空行
                boolean allEmpty = true;
                for (Object o : row) if (o != null && !o.toString().trim().isEmpty()) { allEmpty = false; break; }
                if (allEmpty) continue;

                try {
                    // 节点ID（必填）
                    Object nidObj = getCell(row, colIdx, "节点ID");
                    Long nid = nidObj == null ? null : toLong(nidObj);
                    if (nid == null) {
                        errors.add("第" + (r + 1) + "行：节点ID 缺失");
                        skipped++;
                        continue;
                    }
                    // 数据日期
                    Object dtObj = getCell(row, colIdx, "数据日期");
                    String dt = dtObj == null ? null : dtObj.toString().trim();
                    if (dt == null || dt.isEmpty()) {
                        errors.add("第" + (r + 1) + "行：数据日期 缺失");
                        skipped++;
                        continue;
                    }

                    // 构造 upsert body
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("coaNodeId", nid);
                    body.put("dataDate", dt);
                    body.put("dateOffset", off);
                    body.put("offsetUnit", unit);

                    // 桶列（原始 + 剩余 64+64）
                    for (String b : BUCKET_KEYS) {
                        body.put("orig_" + b, getCell(row, colIdx, "orig_" + b));
                        body.put("rem_" + b, getCell(row, colIdx, "rem_" + b));
                    }
                    // 度量
                    body.put("asf_rsf", getCell(row, colIdx, "asf_rsf"));
                    body.put("hqla_factor", getCell(row, colIdx, "hqla_factor"));
                    body.put("current_balance", getCell(row, colIdx, "current_balance"));
                    body.put("avg_balance", getCell(row, colIdx, "avg_balance"));
                    // 百分比反推回小数
                    body.put("weighted_rate", dividePercent(getCell(row, colIdx, "weighted_rate(%)")));
                    body.put("interest_amount", getCell(row, colIdx, "interest_amount"));
                    body.put("risk_weight", dividePercent(getCell(row, colIdx, "risk_weight(%)")));

                    // 元数据
                    body.put("nodeCode", getCell(row, colIdx, "节点编码"));
                    body.put("nodeName", getCell(row, colIdx, "节点名称"));
                    body.put("category", getCell(row, colIdx, "类别"));

                    if (!dry) {
                        R<?> r2 = upsert(body);
                        Map<String, Object> data = (Map<String, Object>) r2.getData();
                        String mode = String.valueOf(data.get("mode"));
                        if ("insert".equals(mode)) inserted++;
                        else if ("update".equals(mode)) updated++;
                    } else {
                        // dryRun: 仅校验必填 + 桶列解析
                        inserted++;
                    }
                } catch (Exception ex) {
                    errors.add("第" + (r + 1) + "行：" + ex.getMessage());
                    skipped++;
                }
                if (errors.size() >= 1000) {
                    errors.add("(错误过多，截断)");
                    break;
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("inserted", inserted);
            result.put("updated", updated);
            result.put("skipped", skipped);
            result.put("totalErrors", errors.size());
            result.put("errors", errors.size() <= 20 ? errors : errors.subList(0, 20));
            return R.ok(result);
        } catch (Exception e) {
            throw BizException.badRequest("xlsx 解析失败：" + e.getMessage());
        }
    }

    private static Object getCell(List<Object> row, Map<String, Integer> colIdx, String col) {
        Integer i = colIdx.get(col);
        if (i == null || i >= row.size()) return null;
        Object v = row.get(i);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : v;
    }

    private static Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(o.toString().trim()); } catch (Exception e) { return null; }
    }

    /** 把百分比反推回小数（0.034 → 3.4%） */
    private static BigDecimal dividePercent(Object o) {
        if (o == null) return null;
        BigDecimal bd = toBigDecimal(o);
        if (bd == null) return null;
        return bd.divide(BigDecimal.valueOf(100), 6, BigDecimal.ROUND_HALF_UP);
    }

    /** 取节点元数据（自动补全时用，从 prcp_coa_node 查） */
    private NodeMeta loadNodeMeta(Long nodeId) {
        NodeMeta m = new NodeMeta();
        try {
            Map<String, Object> row = mapper.selectNodeMeta(nodeId);
            if (row != null) {
                m.nodeCode = asStr(row.get("nodeCode"));
                m.nodeName = asStr(row.get("nodeName"));
                m.parentCode = asStr(row.get("parentCode"));
                m.category = asStr(row.get("category"));
                m.nodeLevel = toInt(row.get("nodeLevel"), null);
                Object leaf = row.get("isLeaf");
                m.isLeaf = leaf == null ? null : (leaf instanceof Number ? ((Number) leaf).intValue() : (Integer.parseInt(leaf.toString())));
            }
        } catch (Exception ignore) { /* 节点不存在时返回全空 */ }
        return m;
    }

    private static String asStr(Object o) { return o == null ? null : o.toString(); }

    private static BigDecimal toBigDecimal(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal) return (BigDecimal) o;
        if (o instanceof Number) return new BigDecimal(o.toString());
        try { return new BigDecimal(o.toString().trim()); } catch (Exception e) { return BigDecimal.ZERO; }
    }
    private static Integer toInt(Object o, Integer def) {
        if (o == null) return def;
        if (o instanceof Number) return ((Number) o).intValue();
        try { return Integer.parseInt(o.toString().trim()); } catch (Exception e) { return def; }
    }
    private static Object toValue(String key, Object raw) {
        if (raw == null) return null;
        if ("calc_note".equals(key) || "asf_rsf".equals(key)) return raw.toString();
        return toBigDecimal(raw);
    }
    private static String emptyToNull(String s) { return (s == null || s.isEmpty()) ? null : s; }
    private static String camel(String k) {
        return k.substring(0, 1).toUpperCase() + k.substring(1);
    }

    private static class NodeMeta {
        String nodeCode, nodeName, parentCode, category;
        Integer nodeLevel, isLeaf;
    }
}