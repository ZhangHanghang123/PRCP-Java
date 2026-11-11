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
 * <p>导出 5 个 Sheet:
 * <ol>
 *   <li><b>账户册结构</b> — 树形展示全部账户册节点 (名称按层级缩进)。
 *       末级节点 = 自身预测余额；父节点 = 子树汇总 (roll-up 上卷)</li>
 *   <li><b>按层级汇总</b> — 大类 × 层级，每行含节点数 / 末级节点数 / 逐月汇总，末行为末级合计</li>
 *   <li><b>末级节点大类汇总</b> — 资产/负债/权益/表外/其他 逐月合计 + 合计行</li>
 *   <li><b>当前期指标与评分</b> — 5 个监管指标的当前期值 / 基期值 / 变化量 / 阈值 /
 *       达标状态 / 评分(0-500) / 评分档位</li>
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

    /**
     * <p>导出用的 5 个监管指标定义</p>
     *
     * <p>阈值与页面 KPI 卡 / 目标达成进度<b>同一份口径</b>:
     * ROE≥11% / CET1≥8.5% / LCR≥100% / NSFR≥100% / △EVE|x|≤5 亿。</p>
     *
     * <p>kpiCode 对应 <code>prcp_kpi_definition.kpi_code</code>（注意 △EVE 在库中是
     * <code>REG_EVE</code>，不是 <code>REG_DELTA_EVE</code>）。</p>
     */
    private static final List<Map<String, Object>> METRIC_EXPORT_DEFS = buildMetricDefs();

    /** 指标类型列表 (与 prcp_metric_coefficient.metric_type 对应) */
    private static final List<String> METRIC_EXPORT_TYPES =
            List.of("ROE", "CET1", "LCR", "NSFR", "DELTA_EVE");

    /**
     * <p>构造指标定义表</p>
     *
     * @return 指标定义列表
     */
    private static List<Map<String, Object>> buildMetricDefs() {
        List<Map<String, Object>> l = new ArrayList<>();
        l.add(metricDef("ROE 净资产收益率", "REG_ROE", "ROE", "%", 11.0, "down"));
        l.add(metricDef("CET1 核心一级", "REG_CET1", "CET1", "%", 8.5, "down"));
        l.add(metricDef("LCR 流动性覆盖率", "REG_LCR", "LCR", "%", 100.0, "down"));
        l.add(metricDef("NSFR 净稳定资金", "REG_NSFR", "NSFR", "%", 100.0, "down"));
        l.add(metricDef("△EVE 利率风险", "REG_EVE", "DELTA_EVE", "亿", 5.0, "abs"));
        return l;
    }

    /**
     * <p>构造单个指标定义</p>
     *
     * @param label     中文名
     * @param kpiCode   prcp_kpi_definition.kpi_code
     * @param metricType prcp_metric_coefficient.metric_type
     * @param unit      单位
     * @param limit     阈值
     * @param direction down=越高越好 / abs=越低越好
     * @return 定义 Map
     */
    private static Map<String, Object> metricDef(String label, String kpiCode, String metricType,
                                                 String unit, double limit, String direction) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("kpiCode", kpiCode);
        m.put("metricType", metricType);
        m.put("unit", unit);
        m.put("limit", limit);
        m.put("direction", direction);
        m.put("threshold", ("abs".equals(direction) ? "|x| ≤ " : "≥ ") + limit
                + ("%".equals(unit) ? "%" : " " + unit));
        return m;
    }

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
    public byte[] exportBalanceXlsx(String schemeCode, Long runId, Integer dateOffset) {
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

        // 6a. 数据源回退: 部分引擎 (如 Java 版独立引擎) 产出的 Run 只写了父节点且余额为 0,
        //     没有末级节点预测值 → 导出会得到一张全 0 的表。
        //     此时回退到 prcp_data_basic 基准日末级节点余额, 各月取同一基准值。
        //     口径说明 Sheet 会标注 "已回退基准日", 不静默替换。
        boolean balanceFallback = false;
        int leafWithData = 0;
        for (Map.Entry<String, double[]> e : vals.entrySet()) {
            if (!isLeaf(e.getKey(), nodePath, parentSet, leafMap)) continue;
            for (double v : e.getValue()) { if (Math.abs(v) > 1e-6) { leafWithData++; break; } }
        }
        if (leafWithData == 0) {
            Map<String, Double> baseLeaf = loadBaseLeafBalances(baseDate);
            if (!baseLeaf.isEmpty()) {
                balanceFallback = true;
                log.warn("[ReverseDashboardExportService] Run {} 的预测数据无末级节点余额 ({} 个节点), "
                        + "回退到基准日 {} 的末级节点余额 ({} 个)", runId, vals.size(), baseDate, baseLeaf.size());
                for (Map.Entry<String, Double> e : baseLeaf.entrySet()) {
                    double[] arr = vals.computeIfAbsent(e.getKey(), k -> new double[nm]);
                    for (int i = 0; i < nm; i++) arr[i] = e.getValue();
                }
            }
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

            // ---- Sheet4 当前期指标与评分 ----
            XSSFSheet ws4 = wb.createSheet("当前期指标与评分");
            writeHeader(ws4, Arrays.asList(
                    "指标", "指标编码", "单位", "当前期值", "基期值", "变化量",
                    "阈值", "达标状态", "评分", "评分档位", "当前期数据日期"), st);
            List<Map<String, Object>> metricRows = loadMetricRows(sc, runId, coaSchemeId, dateOffset, baseDate);
            int r4 = 1;
            for (Map<String, Object> m : metricRows) {
                Row row = ws4.createRow(r4++);
                setText(row, 0, str(m.get("label")), st.textBold);
                setText(row, 1, str(m.get("kpiCode")), st.text);
                setText(row, 2, str(m.get("unit")), st.text);
                Double cur = dbl(m.get("curValue"));
                Double base = dbl(m.get("baseValue"));
                setNum(row, 3, cur == null ? 0d : cur, st.num);
                setNum(row, 4, base == null ? 0d : base, st.num);
                // 变化量用 BigDecimal 相减, 避免 double 相减的浮点尾差 (如 0.08230000000000004)
                Double delta = (cur == null || base == null) ? null
                        : BigDecimal.valueOf(cur).subtract(BigDecimal.valueOf(base))
                                .setScale(4, RoundingMode.HALF_UP).doubleValue();
                setNum(row, 5, delta == null ? 0d : delta, st.num);
                setText(row, 6, str(m.get("threshold")), st.text);
                String stTxt = str(m.get("status"));
                setText(row, 7, stTxt, "达标".equals(stTxt) ? st.textOk : ("未达标".equals(stTxt) ? st.textBad : st.text));
                Double score = dbl(m.get("score"));
                setNum(row, 8, score == null ? 0d : score, st.num);
                setText(row, 9, str(m.get("segment")), st.text);
                setText(row, 10, str(m.get("curDate")), st.text);
            }
            ws4.createFreezePane(1, 1);
            ws4.setColumnWidth(0, 20 * 256);
            ws4.setColumnWidth(1, 14 * 256);
            ws4.setColumnWidth(2, 8 * 256);
            ws4.setColumnWidth(3, 14 * 256);
            ws4.setColumnWidth(4, 14 * 256);
            ws4.setColumnWidth(5, 14 * 256);
            ws4.setColumnWidth(6, 18 * 256);
            ws4.setColumnWidth(7, 12 * 256);
            ws4.setColumnWidth(8, 10 * 256);
            ws4.setColumnWidth(9, 22 * 256);
            ws4.setColumnWidth(10, 16 * 256);

            // ---- Sheet5 口径说明 ----
            XSSFSheet ws5 = wb.createSheet("口径说明");
            writeHeader(ws5, Arrays.asList("项目", "内容"), st);
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
            meta.add(new String[]{"当前期指标口径", "「当前期指标与评分」Sheet 的指标值为该期全部有值节点的等权平均"
                    + "（不做末级过滤、不做余额加权）；评分取自 prcp_kpi_value（0-500 分制），绑定基准日"});
            if (balanceFallback) {
                meta.add(new String[]{"⚠ 余额数据源回退", "该 Run 的预测数据未产出末级节点余额（仅有父节点且为 0），"
                        + "Sheet ①②③ 的余额已回退为基准日 " + baseDate + " 的末级节点余额（各月同值），"
                        + "非反算预测结果，请以引擎补齐末级节点后重新导出为准"});
            }
            int r5 = 1;
            for (String[] kv : meta) {
                Row row = ws5.createRow(r5++);
                setText(row, 0, kv[0], st.textBold);
                setText(row, 1, kv[1], st.text);
            }
            ws5.setColumnWidth(0, 16 * 256);
            ws5.setColumnWidth(1, 95 * 256);

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
     * <p>加载基准日末级节点余额 (用于预测数据缺失时的回退)</p>
     *
     * <p>数据源: {@code prcp_data_basic} 的基准日 <code>current_balance</code>,
     * 只取 <code>is_leaf=1</code> 的末级节点。</p>
     *
     * @param baseDate 基准日
     * @return node_code → 余额
     */
    private Map<String, Double> loadBaseLeafBalances(String baseDate) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (baseDate == null) return out;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT node_code, current_balance FROM prcp_data_basic"
              + " WHERE is_deleted = 0 AND data_date = ? AND is_leaf = 1", baseDate);
        for (Map<String, Object> r : rows) {
            String nc = str(r.get("node_code"));
            Double v = dbl(r.get("current_balance"));
            if (nc != null && v != null) out.put(nc, v);
        }
        return out;
    }

    /**
     * <p>加载「当前期指标与评分」数据</p>
     *
     * <p><b>口径与页面完全一致</b>（对位 ReverseDashboardService.snapshot 的第 6/7/7b 步）:</p>
     * <ul>
     *   <li><b>节点范围</b> — 全部账户册方案的全部节点（<code>prcp_coa_node WHERE is_deleted=0</code>，
     *       不按 COA 过滤），与页面 KPI 卡的指标均值口径一致</li>
     *   <li><b>指标值</b> — <code>prcp_metric_coefficient</code>，按 <code>(scheme_code, data_date)</code> 取，
     *       无数据时降级到同 data_date 任意 scheme；再按节点取<b>等权平均</b>
     *       （分母 = 该指标有值节点数；<b>不做末级过滤、不做余额加权</b>），4 位小数 HALF_UP</li>
     *   <li><b>基期值</b> — 同口径，取 <code>date_offset=1</code> 对应的 data_date</li>
     *   <li><b>评分</b> — <code>prcp_kpi_value.score</code>（0-500 分制），<b>绑定基准日</b>；
     *       档位由 <code>prcp_kpi_score_segment</code> 按当前值命中区间取得</li>
     *   <li><b>阈值</b> — 与页面同一份常量表（ROE≥11 / CET1≥8.5 / LCR≥100 / NSFR≥100 / △EVE|x|≤5）</li>
     * </ul>
     *
     * @param schemeCode 反算方案 code
     * @param runId      Run ID
     * @param coaSchemeId 方案关联的账户册方案 ID（当前未用于指标口径，仅保留语义）
     * @param dateOffset 当前期月份偏移；null 时取该 Run 的最大 offset
     * @param baseDate   基准日
     * @return 每个指标一行的 Map 列表
     */
    private List<Map<String, Object>> loadMetricRows(String schemeCode, Long runId,
                                                      Long coaSchemeId, Integer dateOffset,
                                                      String baseDate) {
        List<Map<String, Object>> rows = new ArrayList<>();

        // ① 全部节点 (与页面 snapshot 同范围)
        List<Map<String, Object>> nodes = jdbc.queryForList(
                "SELECT n.node_code FROM prcp_coa_node n"
              + " WHERE n.is_deleted = 0 ORDER BY n.scheme_id, n.path, n.node_level");
        List<String> nodeCodes = new ArrayList<>();
        for (Map<String, Object> n : nodes) {
            String nc = str(n.get("node_code"));
            if (nc != null) nodeCodes.add(nc);
        }

        // ② 当前期 / 基期的 data_date
        Integer off = dateOffset;
        String curDate = null;
        String baseOffsetDate = null;
        List<Map<String, Object>> dts = jdbc.queryForList(
                "SELECT date_offset, data_date FROM prcp_data_reverse"
              + " WHERE scheme_code = ? AND run_id = ? AND is_deleted = 0"
              + "   AND offset_unit = 'M' AND date_offset >= 1"
              + " ORDER BY date_offset ASC", schemeCode, runId);
        for (Map<String, Object> d : dts) {
            int o = ((Number) d.get("date_offset")).intValue();
            String dd = d.get("data_date") == null ? null : d.get("data_date").toString();
            if (off == null && o == Integer.MAX_VALUE) continue;
            if (o == 1 && baseOffsetDate == null) baseOffsetDate = dd;
            if (off != null && o == off) curDate = dd;
        }
        if (off == null && !dts.isEmpty()) {
            Map<String, Object> last = dts.get(dts.size() - 1);
            off = ((Number) last.get("date_offset")).intValue();
            curDate = last.get("data_date") == null ? null : last.get("data_date").toString();
        }
        if (curDate == null) {
            curDate = baseDate;   // 无月度数据时退回基准日
        }

        // ③ 指标均值 (当前期 + 基期)
        Map<String, Double> curAvg = avgMetrics(schemeCode, curDate, nodeCodes);
        Map<String, Double> baseAvg = avgMetrics(schemeCode, baseOffsetDate != null ? baseOffsetDate : baseDate, nodeCodes);

        // ④ 评分 (绑定基准日, 0-500)
        Map<String, Map<String, Object>> scoreMap = new LinkedHashMap<>();
        if (baseDate != null) {
            List<Map<String, Object>> sr = jdbc.queryForList(
                    "SELECT d.kpi_code AS kpiCode, v.current_value AS value, v.score AS score,"
                  + " s.min_value AS segMin, s.max_value AS segMax, s.segment_desc AS segDesc"
                  + " FROM prcp_kpi_definition d"
                  + " LEFT JOIN prcp_kpi_value v ON v.kpi_id = d.id AND v.data_date = ? AND v.is_deleted = 0"
                  + " LEFT JOIN prcp_kpi_score_rule r ON r.kpi_id = d.id AND r.is_deleted = 0"
                  + " LEFT JOIN prcp_kpi_score_segment s ON s.rule_id = r.id AND s.is_deleted = 0"
                  + "    AND (s.min_value IS NULL OR v.current_value >= s.min_value)"
                  + "    AND (s.max_value IS NULL OR v.current_value < s.max_value)"
                  + " WHERE d.is_deleted = 0 AND d.kpi_code LIKE 'REG_%' ORDER BY d.id", baseDate);
            for (Map<String, Object> r : sr) {
                scoreMap.put(str(r.get("kpiCode")), r);
            }
        }

        // ⑤ 组装行
        for (Map<String, Object> def : METRIC_EXPORT_DEFS) {
            String mt = str(def.get("metricType"));
            String kpiCode = str(def.get("kpiCode"));
            String direction = str(def.get("direction"));
            Double limit = dbl(def.get("limit"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", def.get("label"));
            row.put("kpiCode", kpiCode);
            row.put("unit", def.get("unit"));
            row.put("curValue", curAvg.get(mt));
            row.put("baseValue", baseAvg.get(mt));
            row.put("curDate", curDate);
            row.put("threshold", def.get("threshold"));
            row.put("status", statusOf(direction, curAvg.get(mt), limit));
            Map<String, Object> sc = scoreMap.get(kpiCode);
            if (sc != null) {
                row.put("score", sc.get("score") == null ? null
                        : Double.parseDouble(sc.get("score").toString()));
                row.put("segment", sc.get("segDesc"));
            } else {
                row.put("score", null);
                row.put("segment", null);
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * <p>指标等权平均 (对位 ReverseDashboardService 的 metricAvg 口径)</p>
     *
     * <p>分母 = 该指标上取值非 null 的节点数；不做末级过滤、不做余额加权；4 位小数 HALF_UP。
     * 查询带两级降级: 精确 (scheme_code, data_date) → 同 data_date 任意 scheme。</p>
     *
     * @param schemeCode 反算方案 code
     * @param dataDate   数据日期
     * @param nodeCodes  节点编码列表
     * @return metricType → 均值
     */
    private Map<String, Double> avgMetrics(String schemeCode, String dataDate, List<String> nodeCodes) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (dataDate == null || nodeCodes.isEmpty()) return out;
        String ph = String.join(",", Collections.nCopies(nodeCodes.size(), "?"));
        String ph2 = String.join(",", Collections.nCopies(METRIC_EXPORT_TYPES.size(), "?"));
        List<Object> params = new ArrayList<>();
        params.add(schemeCode);
        params.add(dataDate);
        params.addAll(nodeCodes);
        params.addAll(METRIC_EXPORT_TYPES);
        String sql = "SELECT node_code, metric_type, current_value"
                   + " FROM prcp_metric_coefficient WHERE is_deleted = 0 AND scheme_code = ?"
                   + " AND data_date = ? AND node_code IN (" + ph + ")"
                   + " AND metric_type IN (" + ph2 + ")";
        List<Map<String, Object>> mrows = jdbc.queryForList(sql, params.toArray());
        if (mrows.isEmpty()) {
            // 降级: 同 data_date 任意 scheme_code
            List<Object> p2 = new ArrayList<>();
            p2.add(dataDate);
            p2.addAll(nodeCodes);
            p2.addAll(METRIC_EXPORT_TYPES);
            String sql2 = "SELECT node_code, metric_type, current_value"
                        + " FROM prcp_metric_coefficient WHERE is_deleted = 0"
                        + " AND data_date = ? AND node_code IN (" + ph + ")"
                        + " AND metric_type IN (" + ph2 + ")";
            mrows = jdbc.queryForList(sql2, p2.toArray());
        }
        // 按 metric_type 分组求均值
        Map<String, List<Double>> byType = new LinkedHashMap<>();
        for (Map<String, Object> r : mrows) {
            String mt = str(r.get("metric_type"));
            Object cv = r.get("current_value");
            if (mt == null || cv == null) continue;
            try {
                byType.computeIfAbsent(mt, k -> new ArrayList<>())
                      .add(Double.parseDouble(cv.toString()));
            } catch (NumberFormatException ignore) {
                // 非数字跳过
            }
        }
        for (String mt : METRIC_EXPORT_TYPES) {
            List<Double> vs = byType.get(mt);
            if (vs == null || vs.isEmpty()) continue;
            double sum = 0;
            for (Double v : vs) sum += v;
            out.put(mt, round4(sum / vs.size()));
        }
        return out;
    }

    /**
     * <p>达标状态判定</p>
     *
     * @param direction "down" = 越高越好 (≥阈值达标); "abs" = 越低越好 (|x|≤阈值达标)
     * @param value    当前值 (可空)
     * @param limit    阈值
     * @return "达标" / "未达标" / "无数据"
     */
    private String statusOf(String direction, Double value, Double limit) {
        if (value == null || limit == null) return "无数据";
        if ("abs".equals(direction)) {
            return Math.abs(value) <= limit + 1e-9 ? "达标" : "未达标";
        }
        return value >= limit - 1e-9 ? "达标" : "未达标";
    }

    /**
     * <p>四舍五入到 4 位 (与 metricAvg 口径一致)</p>
     *
     * @param v 原始值
     * @return 4 位小数
     */
    private double round4(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * <p>Double 转换 (null / 非数字 → null)</p>
     *
     * @param o 原始值
     * @return Double 或 null
     */
    private static Double dbl(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).doubleValue();
        try {
            return Double.parseDouble(o.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

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
        /** 达标 (绿字) */
        final CellStyle textOk;
        /** 未达标 (红字) */
        final CellStyle textBad;

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

            Font okFont = wb.createFont();
            okFont.setBold(true);
            okFont.setColor(IndexedColors.GREEN.getIndex());
            okFont.setFontHeightInPoints((short) 10);

            Font badFont = wb.createFont();
            badFont.setBold(true);
            badFont.setColor(IndexedColors.RED.getIndex());
            badFont.setFontHeightInPoints((short) 10);

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

            textOk = wb.createCellStyle();
            textOk.setFont(okFont);
            border(textOk);

            textBad = wb.createCellStyle();
            textBad.setFont(badFont);
            border(textBad);
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
