package com.prcp.business.reverse.dashboard;

import com.prcp.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <p>反算结果导出 (xlsx) — 对位 Python {@code reverse_dashboard.py::balance_export}</p>
 *
 * <p>导出 4 个 Sheet:
 * <ol>
 *   <li><b>账户册结构</b> — 树形展示全部账户册节点 (名称按层级缩进)。
 *       末级节点 = 自身预测余额；父节点 = 子树汇总 (roll-up 上卷)</li>
 *   <li><b>按层级汇总</b> — 大类 × 层级，每行含节点数 / 末级节点数 / 逐月汇总，末行为末级合计</li>
 *   <li><b>末级节点大类汇总</b> — 资产/负债/权益/表外/其他 逐月合计 + 合计行</li>
 *   <li><b>口径说明</b> — 方案 / Run / 基准日 / 月份区间 / 节点数 / 余额口径 / 数据源 / 导出时间</li>
 * </ol>
 *
 * <p><b>关键口径</b>: 只采信末级节点的自身值 (避免父子重复计算)，
 * 父节点余额 = 其子树内末级节点余额之和 (自底向上上卷)。</p>
 *
 * <p>实现: 原生 Apache POI (XSSF) — EasyExcel 传递依赖已在 classpath。
 * 用 POI 而非 EasyExcel 是因为需要精细控制表头填充 / 缩进 / 数字格式 / 冻结窗格。</p>
 *
 * @author zhanghh
 * @since 2026-11-04
 * @see ReverseDashboardService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReverseDashboardExportService {

    private final JdbcTemplate jdbc;

    private static final List<String> CATEGORIES =
            List.of("ASSET", "LIABILITY", "EQUITY", "OFF_BALANCE", "OTHER");

    private static final Map<String, String> CAT_LABEL = new LinkedHashMap<>();

    static {
        CAT_LABEL.put("ASSET", "资产");
        CAT_LABEL.put("LIABILITY", "负债");
        CAT_LABEL.put("EQUITY", "权益");
        CAT_LABEL.put("OFF_BALANCE", "表外");
        CAT_LABEL.put("OTHER", "其他");
    }

    // ============== 导出主流程 ==============

    /**
     * <p>导出测算期每月预测余额 (xlsx)，直接写输出流</p>
     *
     * @param schemeCode 反算方案编码 (为空则取首个方案)
     * @param runId      Run ID (为空则取该方案最新 SUCCESS)
     * @return xlsx 字节数组 (数据准备阶段抛异常时不会污染 HTTP 响应头)
     */
    public byte[] exportBalanceXlsx(String schemeCode, Long runId) {
        // 1. 方案 + Run
        Map<String, Object> rev = null;
        if (schemeCode != null && !schemeCode.isEmpty()) {
            List<Map<String, Object>> rs = jdbc.queryForList(
                    "SELECT s.id, s.scheme_code, s.scheme_name, s.coa_scheme_id, s.data_date,"
                  + "       s.horizon_months, c.scheme_code AS coa_code, c.scheme_name AS coa_name"
                  + "  FROM prcp_reverse_scheme s"
                  + "  LEFT JOIN prcp_coa_scheme c ON c.id = s.coa_scheme_id"
                  + " WHERE s.scheme_code = ? AND s.is_deleted = 0 LIMIT 1", schemeCode);
            if (!rs.isEmpty()) rev = rs.get(0);
        } else {
            List<Map<String, Object>> rs = jdbc.queryForList(
                    "SELECT s.id, s.scheme_code, s.scheme_name, s.coa_scheme_id, s.data_date,"
                  + "       s.horizon_months, c.scheme_code AS coa_code, c.scheme_name AS coa_name"
                  + "  FROM prcp_reverse_scheme s"
                  + "  LEFT JOIN prcp_coa_scheme c ON c.id = s.coa_scheme_id"
                  + " WHERE s.is_deleted = 0 ORDER BY s.id DESC LIMIT 1");
            if (!rs.isEmpty()) rev = rs.get(0);
        }
        if (rev == null) throw BizException.notFound("未找到反算方案");

        Long revId = num(rev.get("id"));
        String sc = str(rev.get("scheme_code"));
        String schemeName = str(rev.get("scheme_name"));
        Long coaSchemeId = num(rev.get("coa_scheme_id"));
        String coaCode = str(rev.get("coa_code"));
        String coaName = str(rev.get("coa_name"));
        String baseDate = rev.get("data_date") == null ? null : rev.get("data_date").toString();
        Integer horizon = rev.get("horizon_months") == null ? null
                : ((Number) rev.get("horizon_months")).intValue();

        // 2. Run (缺省取最新 SUCCESS)
        if (runId == null) {
            List<Map<String, Object>> runs = jdbc.queryForList(
                    "SELECT id FROM prcp_reverse_run"
                  + " WHERE scheme_id = ? AND status = 'SUCCESS' AND is_deleted = 0"
                  + " ORDER BY id DESC LIMIT 1", revId);
            if (runs.isEmpty()) throw BizException.notFound("该方案无 SUCCESS 运行记录");
            runId = num(runs.get(0).get("id"));
        }

        // 3. 月份 (M1..Mn；跳过 offset 0 = 基准月)
        List<Map<String, Object>> monthRows = jdbc.queryForList(
                "SELECT DISTINCT date_offset, data_date FROM prcp_data_reverse"
              + " WHERE scheme_code = ? AND run_id = ? AND is_deleted = 0"
              + "   AND offset_unit = 'M' AND date_offset >= 1"
              + " ORDER BY date_offset ASC", sc, runId);
        if (monthRows.isEmpty()) throw BizException.notFound("该 Run 无月度预测数据");
        List<Integer> offsets = new ArrayList<>();
        List<String> monthDates = new ArrayList<>();
        for (Map<String, Object> m : monthRows) {
            offsets.add(((Number) m.get("date_offset")).intValue());
            monthDates.add(m.get("data_date") == null ? "" : m.get("data_date").toString());
        }
        int nm = offsets.size();

        // 4. 节点元数据 (账户册结构)
        List<Map<String, Object>> nodeRows = coaSchemeId == null ? Collections.emptyList()
                : jdbc.queryForList(
                        "SELECT id, node_code, node_name, node_level, parent_id, path"
                      + "  FROM prcp_coa_node WHERE scheme_id = ? AND is_deleted = 0"
                      + " ORDER BY node_level, sort_order, node_code", coaSchemeId);

        Map<String, String> nodeName = new LinkedHashMap<>();
        Map<String, Integer> nodeLevel = new LinkedHashMap<>();
        Map<String, String> nodePath = new LinkedHashMap<>();
        Map<Long, String> codeById = new HashMap<>();
        Map<Long, List<String>> childrenOf = new LinkedHashMap<>();
        for (Map<String, Object> n : nodeRows) {
            String nc = str(n.get("node_code"));
            if (nc == null) continue;
            nodeName.put(nc, str(n.get("node_name")) == null ? nc : str(n.get("node_name")));
            nodeLevel.put(nc, n.get("node_level") == null ? 1 : ((Number) n.get("node_level")).intValue());
            nodePath.put(nc, str(n.get("path")) == null ? "" : str(n.get("path")));
            Long id = num(n.get("id"));
            Long pid = num(n.get("parent_id"));
            codeById.put(id, nc);
            childrenOf.computeIfAbsent(pid, k -> new ArrayList<>()).add(nc);
        }

        // 5. 末级判定 (优先 prcp_data_basic.is_leaf, 回退 path 推断)
        Map<String, Boolean> leafMap = loadLeafMap(baseDate, new ArrayList<>(nodeName.keySet()));
        Set<String> parentSet = new HashSet<>();
        for (Map<String, Object> n : nodeRows) {
            Long pid = num(n.get("parent_id"));
            if (pid != null) {
                String pc = codeById.get(pid);
                if (pc != null) parentSet.add(pc);
            }
        }

        // 6. 月度余额矩阵 (末级节点自身值)
        List<Map<String, Object>> valRows = jdbc.queryForList(
                "SELECT date_offset, node_code, current_balance FROM prcp_data_reverse"
              + " WHERE scheme_code = ? AND run_id = ? AND is_deleted = 0"
              + "   AND offset_unit = 'M' AND date_offset >= 1", sc, runId);
        Map<Integer, Integer> offIdx = new HashMap<>();
        for (int i = 0; i < nm; i++) offIdx.put(offsets.get(i), i);
        Map<String, double[]> vals = new LinkedHashMap<>();
        for (Map<String, Object> r : valRows) {
            Integer o = ((Number) r.get("date_offset")).intValue();
            Integer idx = offIdx.get(o);
            if (idx == null) continue;
            String nc = str(r.get("node_code"));
            double[] arr = vals.computeIfAbsent(nc, k -> new double[nm]);
            arr[idx] = r.get("current_balance") == null ? 0d
                    : Double.parseDouble(r.get("current_balance").toString());
        }

        // 7. roll-up: 末级取自身值, 父节点取子树之和 (自底向上)
        Map<String, double[]> rolled = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> e : vals.entrySet()) {
            if (isLeaf(e.getKey(), nodePath, parentSet, leafMap)) rolled.put(e.getKey(), e.getValue());
        }
        rollupAll(nodeRows, codeById, childrenOf, rolled, vals, nm, 0);

        // 8. 生成 xlsx
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles st = new Styles(wb);

            // ---- Sheet1 账户册结构 ----
            XSSFSheet ws1 = wb.createSheet("账户册结构");
            List<String> head1 = new ArrayList<>(Arrays.asList("节点编码", "节点名称", "层级", "大类", "末级节点"));
            for (int i = 0; i < nm; i++) head1.add("M" + offsets.get(i) + "\n" + monthDates.get(i));
            writeHeader(ws1, head1, st);
            ws1.getRow(0).setHeightInPoints(32);

            List<String> ordered = new ArrayList<>(nodeName.keySet());
            ordered.sort((a, b) -> {
                int c = nodePath.getOrDefault(a, "").compareTo(nodePath.getOrDefault(b, ""));
                if (c != 0) return c;
                c = Integer.compare(nodeLevel.getOrDefault(a, 9), nodeLevel.getOrDefault(b, 9));
                return c != 0 ? c : a.compareTo(b);
            });
            int r = 1;
            for (String nc : ordered) {
                boolean leaf = isLeaf(nc, nodePath, parentSet, leafMap);
                String cat = ReverseDashboardService.classify(nc, nodePath.getOrDefault(nc, ""));
                int lvl = nodeLevel.getOrDefault(nc, 1);
                double[] series = rolled.getOrDefault(nc, vals.getOrDefault(nc, new double[nm]));
                Row row = ws1.createRow(r++);
                setText(row, 0, nc, st.text);
                setText(row, 1, nodeName.getOrDefault(nc, nc), leaf ? st.text : st.textBold);
                if (!leaf) ((XSSFCellStyle) st.textBold).setIndention((short) ((lvl - 1) * 2));
                setNum(row, 2, lvl, st.num);
                setText(row, 3, CAT_LABEL.getOrDefault(cat, cat), st.text);
                setText(row, 4, leaf ? "是" : "", st.text);
                for (int i = 0; i < nm; i++) setNum(row, 5 + i, series[i], st.num);
                if (!leaf) applySubFill(row, 5 + nm, st);
            }
            ws1.createFreezePane(5, 1);
            ws1.setColumnWidth(0, 20 * 256);
            ws1.setColumnWidth(1, 40 * 256);
            ws1.setColumnWidth(2, 6 * 256);
            ws1.setColumnWidth(3, 10 * 256);
            ws1.setColumnWidth(4, 10 * 256);
            for (int i = 0; i < nm; i++) ws1.setColumnWidth(5 + i, 16 * 256);

            // ---- Sheet2 按层级汇总 ----
            XSSFSheet ws2 = wb.createSheet("按层级汇总");
            List<String> head2 = new ArrayList<>(Arrays.asList("大类", "层级", "节点数", "末级节点数"));
            for (int i = 0; i < nm; i++) head2.add("M" + offsets.get(i) + "\n" + monthDates.get(i));
            writeHeader(ws2, head2, st);
            ws2.getRow(0).setHeightInPoints(32);
            int r2 = 1;
            for (String cat : CATEGORIES) {
                List<String> catNodes = new ArrayList<>();
                for (String nc : ordered) {
                    if (cat.equals(ReverseDashboardService.classify(nc, nodePath.getOrDefault(nc, "")))) {
                        catNodes.add(nc);
                    }
                }
                if (catNodes.isEmpty()) continue;
                Set<Integer> lvSet = new java.util.TreeSet<>();
                for (String nc : catNodes) lvSet.add(nodeLevel.getOrDefault(nc, 1));
                for (int lv : lvSet) {
                    List<String> lvNodes = new ArrayList<>();
                    int lvLeaf = 0;
                    for (String nc : catNodes) {
                        if (nodeLevel.getOrDefault(nc, 1) != lv) continue;
                        lvNodes.add(nc);
                        if (isLeaf(nc, nodePath, parentSet, leafMap)) lvLeaf++;
                    }
                    double[] acc = new double[nm];
                    for (String nc : lvNodes) {
                        double[] s = rolled.getOrDefault(nc, vals.getOrDefault(nc, new double[nm]));
                        for (int i = 0; i < nm; i++) acc[i] += s[i];
                    }
                    Row row = ws2.createRow(r2++);
                    setText(row, 0, CAT_LABEL.getOrDefault(cat, cat), st.text);
                    setText(row, 1, "L" + lv, st.text);
                    setNum(row, 2, lvNodes.size(), st.num);
                    setNum(row, 3, lvLeaf, st.num);
                    for (int i = 0; i < nm; i++) setNum(row, 4 + i, acc[i], st.num);
                }
            }
            // 末级合计行
            double[] allAcc = new double[nm];
            int allLeaf = 0;
            for (String nc : ordered) {
                if (!isLeaf(nc, nodePath, parentSet, leafMap)) continue;
                allLeaf++;
                double[] s = rolled.getOrDefault(nc, vals.getOrDefault(nc, new double[nm]));
                for (int i = 0; i < nm; i++) allAcc[i] += s[i];
            }
            Row tot = ws2.createRow(r2);
            setText(tot, 0, "末级合计", st.textBold);
            setText(tot, 1, "全部", st.textBold);
            setNum(tot, 2, allLeaf, st.numBold);
            setNum(tot, 3, allLeaf, st.numBold);
            for (int i = 0; i < nm; i++) setNum(tot, 4 + i, allAcc[i], st.numBold);
            ws2.createFreezePane(4, 1);
            ws2.setColumnWidth(0, 12 * 256);
            ws2.setColumnWidth(1, 8 * 256);
            ws2.setColumnWidth(2, 10 * 256);
            ws2.setColumnWidth(3, 12 * 256);
            for (int i = 0; i < nm; i++) ws2.setColumnWidth(4 + i, 16 * 256);

            // ---- Sheet3 末级节点大类汇总 ----
            XSSFSheet ws3 = wb.createSheet("末级节点大类汇总");
            List<String> head3 = new ArrayList<>();
            head3.add("大类");
            for (int i = 0; i < nm; i++) head3.add("M" + offsets.get(i) + "\n" + monthDates.get(i));
            writeHeader(ws3, head3, st);
            ws3.getRow(0).setHeightInPoints(32);
            Map<String, double[]> catTot = new LinkedHashMap<>();
            for (String c : CATEGORIES) catTot.put(c, new double[nm]);
            for (String nc : ordered) {
                if (!isLeaf(nc, nodePath, parentSet, leafMap)) continue;
                String cat = ReverseDashboardService.classify(nc, nodePath.getOrDefault(nc, ""));
                double[] s = rolled.getOrDefault(nc, vals.getOrDefault(nc, new double[nm]));
                double[] t = catTot.get(cat);
                if (t == null) continue;
                for (int i = 0; i < nm; i++) t[i] += s[i];
            }
            int r3 = 1;
            for (String cat : CATEGORIES) {
                Row row = ws3.createRow(r3++);
                setText(row, 0, CAT_LABEL.getOrDefault(cat, cat), st.textBold);
                double[] t = catTot.get(cat);
                for (int i = 0; i < nm; i++) setNum(row, 1 + i, t[i], st.num);
            }
            Row ct = ws3.createRow(r3);
            setText(ct, 0, "合计", st.textBold);
            for (int i = 0; i < nm; i++) {
                double s = 0;
                for (String cat : CATEGORIES) s += catTot.get(cat)[i];
                setNum(ct, 1 + i, s, st.numBold);
            }
            ws3.createFreezePane(1, 1);
            ws3.setColumnWidth(0, 16 * 256);
            for (int i = 0; i < nm; i++) ws3.setColumnWidth(1 + i, 16 * 256);

            // ---- Sheet4 口径说明 ----
            XSSFSheet ws4 = wb.createSheet("口径说明");
            writeHeader(ws4, Arrays.asList("项目", "内容"), st);
            List<String[]> meta = new ArrayList<>();
            meta.add(new String[]{"组合方案", sc + " · " + schemeName});
            meta.add(new String[]{"Run ID", String.valueOf(runId)});
            meta.add(new String[]{"账户册方案", (coaCode == null ? "-" : coaCode) + " · " + (coaName == null ? "-" : coaName)});
            meta.add(new String[]{"基准日", baseDate == null ? "-" : baseDate});
            meta.add(new String[]{"预测期", (horizon == null ? nm : horizon) + " 个月"});
            meta.add(new String[]{"月份区间", "M" + offsets.get(0) + " (" + monthDates.get(0) + ") ~ M"
                    + offsets.get(nm - 1) + " (" + monthDates.get(nm - 1) + ")"});
            meta.add(new String[]{"账户册节点数", nodeRows.size() + " 个（含末级 " + allLeaf + " 个）"});
            meta.add(new String[]{"有预测值节点数", vals.size() + " 个"});
            meta.add(new String[]{"数据来源", "prcp_data_reverse（组合反算结果）+ prcp_coa_node（账户册结构）+ prcp_data_basic（末级判定）"});
            meta.add(new String[]{"导出时间", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))});
            meta.add(new String[]{"余额口径", "末级节点 = 自身预测余额；非末级节点 = 其子树内末级节点余额之和（层级汇总，上卷计算）"});
            meta.add(new String[]{"单位说明", "与源表一致（1 = 1 元）；各层级互不重叠，末级合计 = 账户册总额"});
            int r4 = 1;
            for (String[] kv : meta) {
                Row row = ws4.createRow(r4++);
                setText(row, 0, kv[0], st.textBold);
                setText(row, 1, kv[1], st.text);
            }
            ws4.setColumnWidth(0, 16 * 256);
            ws4.setColumnWidth(1, 95 * 256);

            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            log.error("导出 Excel 失败", e);
            throw BizException.badRequest("导出失败：" + e.getMessage());
        }
    }

    // ============== 辅助 ==============

    /**
     * <p>递归上卷: 父节点余额 = 子树内末级节点之和</p>
     *
     * @param nodeRows   全部节点
     * @param codeById   id → node_code
     * @param childrenOf parent_id → 子节点编码列表
     * @param rolled     已上卷结果 (缓存)
     * @param vals       末级节点自身值
     * @param nm         月份数
     * @param depth      递归深度 (防环)
     */
    private void rollupAll(List<Map<String, Object>> nodeRows,
                           Map<Long, String> codeById,
                           Map<Long, List<String>> childrenOf,
                           Map<String, double[]> rolled,
                           Map<String, double[]> vals,
                           int nm, int depth) {
        if (depth > 12) return;
        for (Map<String, Object> n : nodeRows) {
            String nc = str(n.get("node_code"));
            if (nc == null || rolled.containsKey(nc)) continue;
            // childrenOf 的 key 是 parent_id, 需用「自身 id」查才是自己的子节点
            Long selfId = num(n.get("id"));
            List<String> kids = selfId == null ? null : childrenOf.get(selfId);
            if (kids == null || kids.isEmpty()) {
                double[] zero = new double[nm];
                rolled.put(nc, vals.getOrDefault(nc, zero));
                continue;
            }
            double[] acc = new double[nm];
            for (String k : kids) {
                if (!rolled.containsKey(k)) {
                    // 子节点可能还没上卷, 先递归处理它
                    rollupOne(k, nodeRows, codeById, childrenOf, rolled, vals, nm, depth + 1);
                }
                double[] kv = rolled.get(k);
                if (kv == null) continue;
                for (int i = 0; i < nm; i++) acc[i] += kv[i];
            }
            rolled.put(nc, acc);
        }
    }

    private void rollupOne(String nc,
                           List<Map<String, Object>> nodeRows,
                           Map<Long, String> codeById,
                           Map<Long, List<String>> childrenOf,
                           Map<String, double[]> rolled,
                           Map<String, double[]> vals,
                           int nm, int depth) {
        if (depth > 12 || rolled.containsKey(nc)) return;
        Map<String, Object> self = null;
        for (Map<String, Object> n : nodeRows) {
            if (nc.equals(str(n.get("node_code")))) { self = n; break; }
        }
        if (self == null) {
            rolled.put(nc, vals.getOrDefault(nc, new double[nm]));
            return;
        }
        Long selfId = num(self.get("id"));
        List<String> kids = selfId == null ? null : childrenOf.get(selfId);
        if (kids == null || kids.isEmpty()) {
            rolled.put(nc, vals.getOrDefault(nc, new double[nm]));
            return;
        }
        double[] acc = new double[nm];
        for (String k : kids) {
            rollupOne(k, nodeRows, codeById, childrenOf, rolled, vals, nm, depth + 1);
            double[] kv = rolled.get(k);
            if (kv == null) continue;
            for (int i = 0; i < nm; i++) acc[i] += kv[i];
        }
        rolled.put(nc, acc);
    }

    /**
     * <p>加载末级标记 (prcp_data_basic.is_leaf)</p>
     *
     * <p>注意: prcp_data_basic <b>没有 scheme_id 列</b>, 只能按 (data_date, node_code) 定位。</p>
     *
     * @param baseDate  基准日 (yyyy-MM-dd)
     * @param nodeCodes 节点编码列表
     * @return node_code → is_leaf
     */
    private Map<String, Boolean> loadLeafMap(String baseDate, List<String> nodeCodes) {
        Map<String, Boolean> out = new HashMap<>();
        if (baseDate == null || nodeCodes.isEmpty()) return out;
        String ph = String.join(",", Collections.nCopies(nodeCodes.size(), "?"));
        Object[] params = new Object[nodeCodes.size() + 1];
        params[0] = baseDate;
        for (int i = 0; i < nodeCodes.size(); i++) params[i + 1] = nodeCodes.get(i);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT node_code, is_leaf FROM prcp_data_basic"
              + " WHERE is_deleted = 0 AND data_date = ? AND node_code IN (" + ph + ")", params);
        for (Map<String, Object> r : rows) {
            String nc = str(r.get("node_code"));
            Object v = r.get("is_leaf");
            if (nc == null || v == null) continue;
            if (v instanceof Boolean) out.put(nc, (Boolean) v);
            else if (v instanceof Number) out.put(nc, ((Number) v).intValue() == 1);
        }
        return out;
    }

    /**
     * <p>末级判定: 优先 basicMap 的 is_leaf, 回退 path 前缀推断</p>
     */
    private static boolean isLeaf(String nodeCode, Map<String, String> nodePath,
                                  Set<String> parentSet, Map<String, Boolean> leafMap) {
        Boolean flag = leafMap.get(nodeCode);
        if (flag != null) return flag;
        return ReverseDashboardService.isLeafByPath(nodeCode, nodePath.getOrDefault(nodeCode, ""), parentSet);
    }

    private static Long num(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(o.toString()); } catch (Exception e) { return null; }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    // ============== 样式 ==============

    /** POI 样式集合 */
    private static class Styles {
        final CellStyle head;
        final CellStyle text;
        final CellStyle textBold;
        final CellStyle num;
        final CellStyle numBold;
        final CellStyle subText;
        final CellStyle subNum;

        Styles(XSSFWorkbook wb) {
            XSSFColor headBg = new XSSFColor(new byte[]{(byte) 0x1F, (byte) 0x4E, (byte) 0x9C}, null);
            XSSFColor subBg = new XSSFColor(new byte[]{(byte) 0xEA, (byte) 0xF1, (byte) 0xFB}, null);

            Font headFont = wb.createFont();
            headFont.setBold(true);
            headFont.setColor(IndexedColors.WHITE.getIndex());
            headFont.setFontHeightInPoints((short) 10);

            Font boldFont = wb.createFont();
            boldFont.setBold(true);
            boldFont.setFontHeightInPoints((short) 10);

            head = wb.createCellStyle();
            head.setFont(headFont);
            ((XSSFCellStyle) head).setFillForegroundColor(headBg);
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            head.setAlignment(HorizontalAlignment.CENTER);
            head.setVerticalAlignment(VerticalAlignment.CENTER);
            head.setWrapText(true);
            border(head);

            text = wb.createCellStyle();
            border(text);

            textBold = wb.createCellStyle();
            textBold.setFont(boldFont);
            ((XSSFCellStyle) textBold).setFillForegroundColor(subBg);
            textBold.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            border(textBold);

            num = wb.createCellStyle();
            num.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
            border(num);

            numBold = wb.createCellStyle();
            numBold.setFont(boldFont);
            numBold.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
            ((XSSFCellStyle) numBold).setFillForegroundColor(subBg);
            numBold.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            border(numBold);

            subText = wb.createCellStyle();
            ((XSSFCellStyle) subText).setFillForegroundColor(subBg);
            subText.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            border(subText);

            subNum = wb.createCellStyle();
            subNum.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
            ((XSSFCellStyle) subNum).setFillForegroundColor(subBg);
            subNum.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            border(subNum);
        }

        private static void border(CellStyle s) {
            s.setBorderTop(BorderStyle.THIN);
            s.setBorderBottom(BorderStyle.THIN);
            s.setBorderLeft(BorderStyle.THIN);
            s.setBorderRight(BorderStyle.THIN);
        }
    }

    private static void writeHeader(XSSFSheet ws, List<String> headers, Styles st) {
        Row row = ws.createRow(0);
        for (int i = 0; i < headers.size(); i++) {
            Cell c = row.createCell(i);
            c.setCellValue(headers.get(i));
            c.setCellStyle(st.head);
        }
    }

    private static void setText(Row row, int col, String v, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(v == null ? "" : v);
        c.setCellStyle(style);
    }

    private static void setNum(Row row, int col, double v, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(v);
        c.setCellStyle(style);
    }

    private static void applySubFill(Row row, int colCount, Styles st) {
        for (int i = 0; i < colCount; i++) {
            Cell c = row.getCell(i);
            if (c == null) continue;
            CellStyle s = c.getCellStyle();
            if (s == st.text) c.setCellStyle(st.subText);
            else if (s == st.num) c.setCellStyle(st.subNum);
        }
    }
}
