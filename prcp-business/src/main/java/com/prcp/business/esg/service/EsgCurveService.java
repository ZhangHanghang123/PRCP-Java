package com.prcp.business.esg.service;

import com.prcp.business.esg.entity.EsgCurvePoint;
import com.prcp.business.esg.mapper.EsgCurvePointMapper;
import com.prcp.common.exception.BizException;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * <p>ESG Svensson 曲线 CRUD + 单日还原 (6 端点: list/sources/get/upsert/bulk/rates)</p>
 *
 * <p>核心职责:
 * <ol>
 *   <li>曲线列表 (分页 + source/日期过滤)</li>
 *   <li>按 source 分组汇总</li>
 *   <li>按 (curve_date, source) 取曲线参数</li>
 *   <li>单条 upsert (校验 lambda1/lambda2 > 0)</li>
 *   <li>批量 upsert (失败明细)</li>
 *   <li>Svensson 还原利率 (按 tenor 月份)</li>
 * </ol>
 * </p>
 *
 * <p>关键约定:
 * <ul>
 *   <li>Svensson 参数: theta0/theta1/theta2/theta3 + lambda1/lambda2</li>
 *   <li>校验: lambda1 > 0 且 lambda2 > 0</li>
 *   <li>默认 source: CUSTOM (缺省填值)</li>
 *   <li>利率单位: 还原结果同时输出 percent (%) 和 decimal (小数) 两种</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.esg.mapper.EsgCurvePointMapper
 * @see com.prcp.business.esg.entity.EsgCurvePoint
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EsgCurveService {

    private final EsgCurvePointMapper mapper;

    /**
     * <p>曲线列表 (分页 + source/日期过滤)</p>
     *
     * @param source    数据源 (可选)
     * @param startDate 起始日期 yyyy-MM-dd (可选)
     * @param endDate   结束日期 yyyy-MM-dd (可选)
     * @param page      页码 (从 1 开始)
     * @param pageSize  每页条数
     * @return R.ok(Map.of("items"/"total"/"page"/"pageSize", ...))
     */
    public R<Map<String, Object>> listCurves(String source, String startDate, String endDate,
                                              int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        List<Map<String, Object>> items = mapper.listCurves(emptyToNull(source), emptyToNull(startDate), emptyToNull(endDate), pageSize, offset);
        int total = mapper.countCurves(emptyToNull(source), emptyToNull(startDate), emptyToNull(endDate));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        resp.put("total", total);
        resp.put("page", page);
        resp.put("pageSize", pageSize);
        return R.ok(resp);
    }

    /**
     * <p>按 source 分组汇总 (用于下拉选项)</p>
     *
     * @return R.ok(Map.of("items", list))
     */
    public R<Map<String, Object>> curveSources() {
        List<Map<String, Object>> items = mapper.groupBySource();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        return R.ok(resp);
    }

    /**
     * <p>按 (curve_date, source) 取曲线参数列表</p>
     *
     * @param curveDate 曲线日期 yyyy-MM-dd (必填)
     * @param source    数据源 (可选)
     * @return R.ok(Map.of("items", list)); 未找到时抛 badRequest
     */
    public R<Map<String, Object>> getCurve(String curveDate, String source) {
        List<Map<String, Object>> items = mapper.selectByDateAndSource(curveDate, emptyToNull(source));
        if (items == null || items.isEmpty()) throw BizException.badRequest("curve_date=" + curveDate + " 未找到");
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        return R.ok(resp);
    }

    /**
     * <p>单条曲线 upsert (校验 lambda1>0 且 lambda2>0)</p>
     *
     * @param body 含 curve_date/source/theta0..theta3/lambda1/lambda2/raw_data_json/description
     * @return R.ok(Map.of("id"/"curveDate"/"source"/"ok", true))
     */
    public R<Map<String, Object>> upsertCurve(Map<String, Object> body) {
        String cd = toStr(firstNonNull(body.get("curveDate"), body.get("curve_date")));
        if (cd == null) throw BizException.badRequest("curve_date 必填");
        String src = toStr(firstNonNull(body.get("source"), body.get("source")));
        if (src == null) src = "CUSTOM";
        // 校验 lambda > 0
        BigDecimal l1 = toBigDecimal(firstNonNull(body.get("lambda1"), body.get("lambda1")));
        BigDecimal l2 = toBigDecimal(firstNonNull(body.get("lambda2"), body.get("lambda2")));
        if (l1 == null || l1.compareTo(BigDecimal.ZERO) <= 0)
            throw BizException.badRequest("lambda1 必须 > 0");
        if (l2 == null || l2.compareTo(BigDecimal.ZERO) <= 0)
            throw BizException.badRequest("lambda2 必须 > 0");

        EsgCurvePoint p = new EsgCurvePoint();
        try { p.setCurveDate(LocalDate.parse(cd)); } catch (Exception e) { throw BizException.badRequest("curve_date 格式错误"); }
        p.setSource(src);
        p.setTheta0(toBigDecimal(firstNonNull(body.get("theta0"), body.get("theta0"))));
        p.setTheta1(toBigDecimal(firstNonNull(body.get("theta1"), body.get("theta1"))));
        p.setTheta2(toBigDecimal(firstNonNull(body.get("theta2"), body.get("theta2"))));
        p.setTheta3(toBigDecimal(firstNonNull(body.get("theta3"), body.get("theta3"))));
        p.setLambda1(l1);
        p.setLambda2(l2);
        Object raw = body.get("rawDataJson");
        if (raw == null) raw = body.get("raw_data_json");
        if (raw != null) p.setRawDataJson(raw.toString());
        p.setDescription(toStr(body.get("description")));
        p.setCreatedBy(1L);
        p.setUpdatedBy(1L);

        mapper.upsertCurve(p);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", p.getId());
        resp.put("curveDate", cd);
        resp.put("source", src);
        resp.put("ok", true);
        return R.ok(resp);
    }

    /**
     * <p>批量 upsert (逐条调 upsertCurve, 失败明细返回)</p>
     *
     * @param points 曲线参数列表 (每条同 upsertCurve body)
     * @return R.ok(Map.of("success"/"failed"/"failDetails", ...))
     */
    public R<Map<String, Object>> bulkUpsert(List<Map<String, Object>> points) {
        if (points == null || points.isEmpty()) throw BizException.badRequest("points 不能为空");
        int nSuccess = 0;
        List<Map<String, Object>> nFail = new ArrayList<>();
        for (Map<String, Object> pt : points) {
            try {
                R<Map<String, Object>> r = upsertCurve(pt);
                if (r.getCode() == 200) nSuccess++;
                else nFail.add(pt);
            } catch (Exception e) {
                nFail.add(pt);
            }
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", nSuccess);
        resp.put("failed", nFail.size());
        resp.put("failDetails", nFail);
        return R.ok(resp);
    }

    /**
     * <p>Svensson 还原利率 (按 tenor 月份数组)</p>
     *
     * <p>每个 source 各还原一次; 同时输出 percent (%) 和 decimal 两种</p>
     *
     * @param curveDate 曲线日期 yyyy-MM-dd (必填)
     * @param body      含 source (可选) + tenors (月数列表, 必填)
     * @return R.ok(Map.of("items", list)), 每项含 source/curveDate/tenorsMonths/ratesPct/ratesDecimal
     */
    public R<Map<String, Object>> svenssonRates(String curveDate, Map<String, Object> body) {
        String src = toStr(body.get("source"));
        Object tenorsObj = body.get("tenors");
        if (!(tenorsObj instanceof List) || ((List<?>) tenorsObj).isEmpty())
            throw BizException.badRequest("tenors 必填且至少 1 个");

        List<Map<String, Object>> curves = mapper.selectByDateAndSource(curveDate, emptyToNull(src));
        if (curves.isEmpty()) throw BizException.badRequest("curve_date=" + curveDate + " 未找到");

        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> c : curves) {
            double t0 = ((Number) c.get("theta0")).doubleValue();
            double t1 = ((Number) c.get("theta1")).doubleValue();
            double t2 = ((Number) c.get("theta2")).doubleValue();
            double t3 = ((Number) c.get("theta3")).doubleValue();
            double l1 = ((Number) c.get("lambda1")).doubleValue();
            double l2 = ((Number) c.get("lambda2")).doubleValue();
            SvenssonCurve curve = new SvenssonCurve(t0, t1, t2, t3, l1, l2);

            int[] tenors = new int[((List<?>) tenorsObj).size()];
            for (int i = 0; i < tenors.length; i++) {
                Object o = ((List<?>) tenorsObj).get(i);
                tenors[i] = o instanceof Number ? ((Number) o).intValue() : Integer.parseInt(o.toString());
            }
            double[] pct = curve.yieldsPct(tenors);
            double[] dec = new double[pct.length];
            for (int i = 0; i < pct.length; i++) dec[i] = pct[i] / 100.0;

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("source", c.get("source"));
            r.put("curveDate", curveDate);
            r.put("tenorsMonths", tenorsObj);
            r.put("ratesPct", pct);
            r.put("ratesDecimal", dec);
            results.add(r);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", results);
        return R.ok(resp);
    }

    // ============ 工具 ============

    /**
     * <p>加载某方案的曲线点列表 (fit-pca 用, 按 curve_date ASC)</p>
     *
     * @param source    数据源 (可选)
     * @param startDate 起始日期 yyyy-MM-dd (可选)
     * @param endDate   结束日期 yyyy-MM-dd (可选)
     * @return 曲线点 Map 列表
     */
    public List<Map<String, Object>> rangePoints(String source, String startDate, String endDate) {
        return mapper.rangePoints(source, startDate, endDate);
    }

    private static String emptyToNull(String s) { return (s == null || s.isEmpty()) ? null : s; }
    private static String toStr(Object o) { return o == null ? null : o.toString().trim(); }
    private static BigDecimal toBigDecimal(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal) return (BigDecimal) o;
        if (o instanceof Number) return new BigDecimal(o.toString());
        try { return new BigDecimal(o.toString()); } catch (Exception e) { return null; }
    }
    private static Object firstNonNull(Object... vals) {
        for (Object v : vals) if (v != null) return v;
        return null;
    }
}