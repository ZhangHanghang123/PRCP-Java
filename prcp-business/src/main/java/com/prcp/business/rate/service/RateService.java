package com.prcp.business.rate.service;

import com.prcp.business.rate.RateKeys;
import com.prcp.business.rate.entity.RatePoint;
import com.prcp.business.rate.entity.RateScheme;
import com.prcp.business.rate.mapper.RatePointMapper;
import com.prcp.business.rate.mapper.RateSchemeMapper;
import com.prcp.common.exception.BizException;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
public class RateService {

    private final RateSchemeMapper schemeMapper;
    private final RatePointMapper pointMapper;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // ============ 曲线方案 CRUD ============
    public R<Map<String, Object>> listSchemes(String curveType, String status) {
        List<Map<String, Object>> items = schemeMapper.listSchemes(emptyToNull(curveType), emptyToNull(status));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        resp.put("total", items.size());
        return R.ok(resp);
    }

    public R<Map<String, Object>> createScheme(RateScheme s) {
        if (s.getCurveCode() == null || s.getCurveCode().isEmpty())
            throw BizException.badRequest("curve_code 不能为空");
        if (s.getCurveName() == null || s.getCurveName().isEmpty())
            throw BizException.badRequest("curve_name 不能为空");
        if (s.getCurveType() == null || s.getCurveType().isEmpty())
            throw BizException.badRequest("curve_type 不能为空");
        s.setCcy(s.getCcy() == null ? "CNY" : s.getCcy());
        s.setDataSource(s.getDataSource() == null ? "WIND" : s.getDataSource());
        s.setStatus(s.getStatus() == null ? "ACTIVE" : s.getStatus());
        s.setIsDeleted(0);
        boolean ok = schemeMapper.insert(s) > 0;
        if (!ok) return R.fail("创建失败");
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", s.getId());
        resp.put("curve_code", s.getCurveCode());
        resp.put("ok", true);
        return R.ok(resp);
    }

    public R<?> updateScheme(Long id, RateScheme s) {
        if (id == null) throw BizException.badRequest("id 不能为空");
        RateScheme exist = schemeMapper.selectById(id);
        if (exist == null || Integer.valueOf(1).equals(exist.getIsDeleted())) {
            throw BizException.badRequest("曲线方案不存在");
        }
        s.setId(id);
        s.setCurveCode(null); // 主键外字段不允许改
        s.setIsDeleted(null);
        boolean ok = schemeMapper.updateById(s) > 0;
        return ok ? R.ok() : R.fail("更新失败");
    }

    @Transactional
    public R<?> deleteScheme(Long id) {
        int n = schemeMapper.softDeleteById(id, 1L);
        if (n == 0) throw BizException.badRequest("曲线方案不存在或已删除");
        // 级联软删所有利率点
        pointMapper.softDeleteByCurveId(id);
        return R.ok();
    }

    // ============ 利率点 CRUD ============
    public R<Map<String, Object>> listPoints(String curveCode, String dataDate) {
        List<Map<String, Object>> items = pointMapper.listPoints(emptyToNull(curveCode), emptyToNull(dataDate));
        // 把 13 个利率列重组成 rates 子字典（对齐 Python）
        for (Map<String, Object> row : items) {
            Map<String, BigDecimal> rates = new LinkedHashMap<>();
            for (String k : RateKeys.TERM_KEYS) {
                Object v = row.get("rate_" + k);
                rates.put(k, v == null ? BigDecimal.ZERO : new BigDecimal(v.toString()));
            }
            row.put("rates", rates);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        resp.put("total", items.size());
        return R.ok(resp);
    }

    @Transactional
    public R<Map<String, Object>> upsertPoint(Map<String, Object> body) {
        String curveCode = toStr(body.get("curveCode") != null ? body.get("curveCode") : body.get("curve_code"));
        String dataDate = toStr(body.get("dataDate") != null ? body.get("dataDate") : body.get("data_date"));
        String ccy = toStr(body.get("currency") != null ? body.get("currency") : body.get("ccy"));
        if (curveCode == null) throw BizException.badRequest("curve_code 不能为空");
        if (dataDate == null) throw BizException.badRequest("data_date 不能为空");
        ccy = ccy == null ? "CNY" : ccy;

        // 1. 查 curve_id
        Long curveId = schemeMapper.selectIdByCurveCode(curveCode);
        if (curveId == null) throw BizException.badRequest("曲线方案 " + curveCode + " 不存在");

        // 2. 解析 13 个利率
        Object ratesObj = body.get("rates");
        if (!(ratesObj instanceof Map)) throw BizException.badRequest("rates 字典不能为空");
        Map<?, ?> ratesMap = (Map<?, ?>) ratesObj;
        BigDecimal[] rates = new BigDecimal[RateKeys.TERM_KEYS.size()];
        for (int i = 0; i < RateKeys.TERM_KEYS.size(); i++) {
            String k = RateKeys.TERM_KEYS.get(i);
            Object v = ratesMap.get(k);
            rates[i] = v == null ? BigDecimal.ZERO : new BigDecimal(v.toString());
        }
        BigDecimal y1 = rates[5];   // y1
        BigDecimal y10 = rates[9];  // y10

        // 3. 自动计算 curve_slope = 10Y - 1Y
        BigDecimal slope = y10.subtract(y1);

        // 4. 计算 BP 平移 vs 上一个数据日期的 10Y
        BigDecimal prevY10 = pointMapper.selectPrevY10(curveCode, dataDate);
        BigDecimal shiftBps = prevY10 == null ? BigDecimal.ZERO
            : y10.subtract(prevY10).multiply(new BigDecimal("100"));

        // 5. UPSERT
        RatePoint p = new RatePoint();
        p.setCurveId(curveId);
        p.setCurveCode(curveCode);
        p.setDataDate(LocalDate.parse(dataDate, DATE_FMT));
        p.setCcy(ccy);
        p.setRateD1(rates[0]);  p.setRateD7(rates[1]);
        p.setRateM1(rates[2]);  p.setRateM3(rates[3]);  p.setRateM6(rates[4]);
        p.setRateY1(y1);  p.setRateY2(rates[6]);  p.setRateY3(rates[7]);
        p.setRateY5(rates[8]);  p.setRateY10(y10);
        p.setRateY15(rates[10]); p.setRateY20(rates[11]); p.setRateY30(rates[12]);
        p.setCurveShiftBps(shiftBps);
        p.setCurveSlope(slope);
        p.setSourceDate((body.get("sourceDate") == null && body.get("source_date") == null) ? p.getDataDate()
            : LocalDate.parse(toStr(body.get("sourceDate") != null ? body.get("sourceDate") : body.get("source_date")), DATE_FMT));
        p.setRemark(toStr(body.get("remark")));
        p.setCreatedBy(1L);
        p.setUpdatedBy(1L);

        int n = pointMapper.upsertPoint(p, 1L);
        if (n == 0) return R.fail("保存失败");

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("curve_code", curveCode);
        resp.put("data_date", dataDate);
        resp.put("curve_shift_bps", shiftBps.setScale(2, java.math.RoundingMode.HALF_UP));
        resp.put("curve_slope", slope.setScale(4, java.math.RoundingMode.HALF_UP));
        return R.ok(resp);
    }

    public R<?> deletePoint(Long id) {
        int n = pointMapper.softDeleteById(id);
        if (n == 0) throw BizException.badRequest("利率点不存在或已删除");
        return R.ok();
    }

    // ============ 历史曲线对比 ============
    public R<Map<String, Object>> compareCurves(String curveCode, String startDate, String endDate) {
        if (curveCode == null || curveCode.isEmpty())
            throw BizException.badRequest("curve_code 不能为空");
        List<Map<String, Object>> rows = pointMapper.listForCompare(curveCode,
            emptyToNull(startDate), emptyToNull(endDate));
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("data_date", r.get("data_date") == null ? null : r.get("data_date").toString());
            Map<String, BigDecimal> rates = new LinkedHashMap<>();
            for (String k : RateKeys.TERM_KEYS) {
                Object v = r.get("rate_" + k);
                rates.put(k, v == null ? BigDecimal.ZERO : new BigDecimal(v.toString()));
            }
            item.put("rates", rates);
            item.put("curve_slope", r.get("curve_slope") == null ? BigDecimal.ZERO
                : new BigDecimal(r.get("curve_slope").toString()));
            items.add(item);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("curve_code", curveCode);
        resp.put("items", items);
        resp.put("total", items.size());
        return R.ok(resp);
    }

    // ============ 工具：按期限点查单一利率 ============
    public R<Map<String, Object>> lookupRate(String curveCode, String dataDate, String term) {
        if (curveCode == null || curveCode.isEmpty())
            throw BizException.badRequest("curve_code 不能为空");
        if (dataDate == null || dataDate.isEmpty())
            throw BizException.badRequest("data_date 不能为空");
        if (term == null || !RateKeys.TERM_KEYS.contains(term))
            throw BizException.badRequest("期限点非法，应为 " + RateKeys.TERM_KEYS);
        String col = RateKeys.colOf(term);
        BigDecimal rate = pointMapper.lookupRate(curveCode, LocalDate.parse(dataDate, DATE_FMT), col);
        if (rate == null) throw BizException.badRequest("无 " + curveCode + " @ " + dataDate + " 利率数据");
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("curve_code", curveCode);
        resp.put("data_date", dataDate);
        resp.put("term", term);
        resp.put("rate", rate);
        return R.ok(resp);
    }

    // ============ Svensson 6 参数拟合（Nelson-Siegel-Svensson 模型）============
    /**
     * Svensson 模型：
     *   y(τ) = β₀ + β₁·[(1-e^(-τ/τ₁))/(τ/τ₁)]
     *              + β₂·[(1-e^(-τ/τ₁))/(τ/τ₁) - e^(-τ/τ₁)]
     *              + β₃·[(1-e^(-τ/τ₂))/(τ/τ₂) - e^(-τ/τ₂)]
     * 简化：固定 τ₁=1, τ₂=5（行业常用初始值），用 4 元线性回归求 β₀~3
     * Body: {curve_code, data_date}（必填）+ {tau1=1, tau2=5}（可选）
     */
    public R<Map<String, Object>> fitSvensson(Map<String, Object> body) {
        String curveCode = toStr(body.get("curve_code"));
        String dataDate = toStr(body.get("data_date"));
        if (curveCode == null || dataDate == null)
            throw BizException.badRequest("curve_code 和 data_date 必填");
        double tau1 = body.get("tau1") == null ? 1.0 : ((Number) body.get("tau1")).doubleValue();
        double tau2 = body.get("tau2") == null ? 5.0 : ((Number) body.get("tau2")).doubleValue();
        // 1. 取当日 13 个利率点
        R<Map<String, Object>> lookupResp = listPoints(curveCode, dataDate);
        Object itemsObj = lookupResp.getData().get("items");
        if (!(itemsObj instanceof java.util.List) || ((java.util.List<?>) itemsObj).isEmpty())
            throw BizException.badRequest("无 " + curveCode + " @ " + dataDate + " 利率数据");
        Map<String, Object> point = (Map<String, Object>) ((java.util.List<?>) itemsObj).get(0);
        Object ratesObj = point.get("rates");
        if (!(ratesObj instanceof Map))
            throw BizException.badRequest("利率数据格式错误");
        Map<String, Object> rates = (Map<String, Object>) ratesObj;
        // 2. 期限映射到年数
        // m1..m60 月 → τ = m/12; y10/y15/y20/y30 年
        java.util.List<double[]> xs = new java.util.ArrayList<>();
        java.util.List<Double> ys = new java.util.ArrayList<>();
        java.util.Map<String, Double> termTau = new java.util.LinkedHashMap<>();
        termTau.put("d1", 1.0 / 365); termTau.put("d7", 7.0 / 365);
        for (int m = 1; m <= 60; m++) termTau.put("m" + m, m / 12.0);
        termTau.put("y10", 10.0); termTau.put("y15", 15.0); termTau.put("y20", 20.0); termTau.put("y30", 30.0);
        for (java.util.Map.Entry<String, Double> e : termTau.entrySet()) {
            Object v = rates.get(e.getKey());
            if (v == null) continue;
            double tau = e.getValue();
            if (tau < 1e-6) continue;
            // 4 个基变量 [1, factor1, factor2, factor3]
            double f1 = (1 - Math.exp(-tau / tau1)) / (tau / tau1);
            double f2 = f1 - Math.exp(-tau / tau1);
            double f3 = (1 - Math.exp(-tau / tau2)) / (tau / tau2) - Math.exp(-tau / tau2);
            xs.add(new double[]{1.0, f1, f2, f3});
            ys.add(Double.parseDouble(v.toString()));
        }
        int n = ys.size();
        if (n < 4) throw BizException.badRequest("利率点不足 4 个，无法拟合");
        // 3. 多元线性回归 (X^T X)^{-1} X^T y
        double[][] X = new double[n][4];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) { X[i] = xs.get(i); y[i] = ys.get(i); }
        double[][] XtX = new double[4][4];
        double[] Xty = new double[4];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < 4; j++) {
                Xty[j] += X[i][j] * y[i];
                for (int k = 0; k < 4; k++) XtX[j][k] += X[i][j] * X[i][k];
            }
        }
        // 求逆（4x4 高斯-约旦）
        double[][] inv = invertMatrix(XtX);
        double[] beta = new double[4];
        for (int i = 0; i < 4; i++)
            for (int j = 0; j < 4; j++) beta[i] += inv[i][j] * Xty[j];
        // 4. 拟合优度 R²
        double mean = 0; for (double v : y) mean += v; mean /= n;
        double ssRes = 0, ssTot = 0;
        for (int i = 0; i < n; i++) {
            double pred = beta[0] + beta[1] * X[i][1] + beta[2] * X[i][2] + beta[3] * X[i][3];
            ssRes += Math.pow(y[i] - pred, 2);
            ssTot += Math.pow(y[i] - mean, 2);
        }
        double r2 = ssTot < 1e-9 ? 0 : 1 - ssRes / ssTot;
        // 5. 返回拟合曲线（13 期限点）
        java.util.Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("curve_code", curveCode);
        resp.put("data_date", dataDate);
        resp.put("tau1", tau1);
        resp.put("tau2", tau2);
        resp.put("beta0", beta[0]);
        resp.put("beta1", beta[1]);
        resp.put("beta2", beta[2]);
        resp.put("beta3", beta[3]);
        resp.put("r_squared", r2);
        java.util.Map<String, BigDecimal> fitted = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, Double> e : termTau.entrySet()) {
            double tau = e.getValue();
            if (tau < 1e-6) { fitted.put(e.getKey(), BigDecimal.ZERO); continue; }
            double f1 = (1 - Math.exp(-tau / tau1)) / (tau / tau1);
            double f2 = f1 - Math.exp(-tau / tau1);
            double f3 = (1 - Math.exp(-tau / tau2)) / (tau / tau2) - Math.exp(-tau / tau2);
            double pred = beta[0] + beta[1] * f1 + beta[2] * f2 + beta[3] * f3;
            fitted.put(e.getKey(), new BigDecimal(String.format("%.6f", pred)));
        }
        resp.put("fitted", fitted);
        return R.ok(resp);
    }

    /** Nelson-Siegel 4 参数拟合（不含第三项 hump，β₃=0）：复用 Svensson 实现，β₃ 强制 0 */
    public R<Map<String, Object>> fitNS(Map<String, Object> body) {
        // 把 tau2 设为很大，β₃ 自然 ≈ 0
        body = new java.util.LinkedHashMap<>(body);
        body.put("tau2", 1e6);
        return fitSvensson(body);
    }

    /** CSV 导出 */
    public void exportRates(String curveCode, String startDate, String endDate,
                             javax.servlet.http.HttpServletResponse resp) throws java.io.IOException {
        R<Map<String, Object>> data = compareCurves(curveCode, startDate, endDate);
        resp.setContentType("text/csv; charset=UTF-8");
        resp.setHeader("Content-Disposition", "attachment;filename=" + curveCode + "_rates.csv");
        java.io.PrintWriter w = resp.getWriter();
        w.println("data_date,d1,d7,m1,m3,m6,y1,y2,y3,y5,y10,y15,y20,y30");
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> items = (java.util.List<Map<String, Object>>) data.getData().get("items");
        if (items != null) {
            for (Map<String, Object> row : items) {
                StringBuilder sb = new StringBuilder();
                sb.append(row.get("data_date")).append(",");
                for (String k : RateKeys.TERM_KEYS) {
                    Object v = row.get(k);
                    sb.append(v == null ? "" : v.toString()).append(",");
                }
                // 去掉末尾逗号
                w.println(sb.substring(0, sb.length() - 1));
            }
        }
        w.flush();
    }

    /** 4x4 矩阵求逆（高斯-约旦） */
    private static double[][] invertMatrix(double[][] a) {
        int n = a.length;
        double[][] aug = new double[n][2 * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) aug[i][j] = a[i][j];
            aug[i][n + i] = 1;
        }
        for (int i = 0; i < n; i++) {
            // 选主元
            int pivot = i;
            for (int k = i + 1; k < n; k++)
                if (Math.abs(aug[k][i]) > Math.abs(aug[pivot][i])) pivot = k;
            double[] tmp = aug[i]; aug[i] = aug[pivot]; aug[pivot] = tmp;
            // 归一化
            double div = aug[i][i];
            for (int j = 0; j < 2 * n; j++) aug[i][j] /= div;
            // 消元
            for (int k = 0; k < n; k++) {
                if (k == i) continue;
                double factor = aug[k][i];
                for (int j = 0; j < 2 * n; j++) aug[k][j] -= factor * aug[i][j];
            }
        }
        double[][] inv = new double[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) inv[i][j] = aug[i][n + j];
        return inv;
    }

    // ============ 工具方法 ============
    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }
    private static String toStr(Object o) { return o == null ? null : o.toString(); }
}