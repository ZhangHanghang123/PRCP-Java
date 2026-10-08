package com.prcp.business.esg.service;

import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * PRCP ESG · YieldCurveGenerator（PCA + HJM）
 *
 * 设计（对齐 Python services/esg/yield_curve_generator.py）：
 *   1. 从 prcp_esg_curve_point 加载 Svensson 6 参数（多日期 × 多期限）
 *   2. 还原成 n_samples × n_maturities 的利率矩阵
 *   3. 对每期限做一阶差分 → 去中心化 → 协方差矩阵 → 特征值分解
 *   4. 取前 n_factors 个主成分
 *   5. HJM 仿射：用 cumsum(dW) × eigenvectors 模拟利率路径
 *
 * 月频 dt=1/12（不引入 22 日转换因子，与 PRCP 一致）。
 */
@Slf4j
public class YieldCurveGenerator {

    /** 默认 13 期限点（与 Python DEFAULT_MATURITIES_MONTHS 一致） */
    public static final int[] DEFAULT_MATURITIES_MONTHS = {1, 3, 6, 12, 24, 36, 48, 60, 84, 120, 180, 240, 360};

    private final int nFactors;
    private final int[] maturities;
    private final int nMaturities;
    private int seed;

    // PCA 拟合结果
    private double[] mu;                            // (n_maturities,)
    private double[] eigenvalues;                   // (n_maturities,) 降序
    private double[][] eigenvectors;                // (n_maturities, n_maturities) 列=特征向量
    private double[] explainedVarianceRatio;       // (n_maturities,)
    private double[] cumulativeVarianceRatio;      // (n_maturities,)

    private int nSamples;
    private boolean pcaFitted;
    private boolean hjmGenerated;

    /** 最近一次 HJM 输出（n_scenarios × n_steps × n_maturities） */
    private double[][][] lastHjmPaths;

    public YieldCurveGenerator(int nFactors, int[] maturitiesMonths, int seed) {
        this.nFactors = nFactors;
        this.maturities = (maturitiesMonths != null) ? maturitiesMonths : DEFAULT_MATURITIES_MONTHS;
        this.nMaturities = this.maturities.length;
        this.seed = seed;
    }

    public int getNFactors() { return nFactors; }
    public int[] getMaturities() { return maturities; }
    public int getSeed() { return seed; }
    public int getNSamples() { return nSamples; }
    public boolean isPcaFitted() { return pcaFitted; }
    public boolean isHjmGenerated() { return hjmGenerated; }
    public double[] getEigenvalues() { return eigenvalues; }
    public double[] getExplainedVarianceRatio() { return explainedVarianceRatio; }
    public double[] getCumulativeVarianceRatio() { return cumulativeVarianceRatio; }
    public double[][] getEigenvectors() { return eigenvectors; }
    public double[][][] getLastHjmPaths() { return lastHjmPaths; }

    // ===================== PCA 拟合 =====================

    /**
     * 从 Svensson 参数点列表拟合 PCA
     * @param curvePoints 至少 2 个日期，每个含 theta0..3 + lambda1/2 + curveDate
     */
    public YieldCurveGenerator fitFromParams(List<Map<String, Object>> curvePoints) {
        if (curvePoints.size() < 2) {
            throw new IllegalArgumentException(
                "PCA 拟合至少需要 2 个历史日期（实际 " + curvePoints.size() + "）");
        }

        int n = curvePoints.size();
        double[][] yields = new double[n][nMaturities];

        for (int i = 0; i < n; i++) {
            Map<String, Object> pt = curvePoints.get(i);
            double t0 = ((Number) pt.get("theta0")).doubleValue();
            double t1 = ((Number) pt.get("theta1")).doubleValue();
            double t2 = ((Number) pt.get("theta2")).doubleValue();
            double t3 = ((Number) pt.get("theta3")).doubleValue();
            double l1 = ((Number) pt.get("lambda1")).doubleValue();
            double l2 = ((Number) pt.get("lambda2")).doubleValue();
            SvenssonCurve curve = new SvenssonCurve(t0, t1, t2, t3, l1, l2);
            for (int j = 0; j < nMaturities; j++) {
                yields[i][j] = curve.yieldPct(maturities[j]);
            }
        }

        // 去中心化（按列减均值）
        double[] colMean = new double[nMaturities];
        for (int j = 0; j < nMaturities; j++) {
            double sum = 0;
            for (int i = 0; i < n; i++) sum += yields[i][j];
            colMean[j] = sum / n;
        }

        // 协方差矩阵 (m × m)
        double[][] cov = new double[nMaturities][nMaturities];
        for (int j1 = 0; j1 < nMaturities; j1++) {
            for (int j2 = j1; j2 < nMaturities; j2++) {
                double s = 0;
                for (int i = 0; i < n; i++) {
                    s += (yields[i][j1] - colMean[j1]) * (yields[i][j2] - colMean[j2]);
                }
                s /= Math.max(n - 1, 1);
                cov[j1][j2] = s;
                cov[j2][j1] = s;
            }
        }

        // 特征值分解（Jacobi 方法，返回升序）
        EigenResult er = jacobiEigenDecomposition(cov);
        // 降序排列
        double[] eigValsDesc = new double[nMaturities];
        double[][] eigVecsDesc = new double[nMaturities][nMaturities];
        for (int i = 0; i < nMaturities; i++) {
            int srcIdx = nMaturities - 1 - i;
            eigValsDesc[i] = er.eigenvalues[srcIdx];
            for (int j = 0; j < nMaturities; j++) {
                eigVecsDesc[j][i] = er.eigenvectors[j][srcIdx];
            }
        }
        this.eigenvalues = eigValsDesc;
        this.eigenvectors = eigVecsDesc;

        // 一阶差分均值（HJM drift）
        double[] deltaSum = new double[nMaturities];
        if (n >= 2) {
            for (int j = 0; j < nMaturities; j++) {
                for (int i = 1; i < n; i++) {
                    deltaSum[j] += yields[i][j] - yields[i - 1][j];
                }
                deltaSum[j] /= (n - 1);
            }
        }
        this.mu = deltaSum;

        // 方差贡献率
        double total = 0;
        for (double ev : eigValsDesc) total += Math.max(ev, 0);
        if (total <= 0) total = 1;
        this.explainedVarianceRatio = new double[nMaturities];
        this.cumulativeVarianceRatio = new double[nMaturities];
        double cum = 0;
        for (int i = 0; i < nMaturities; i++) {
            explainedVarianceRatio[i] = Math.max(eigValsDesc[i], 0) / total;
            cum += explainedVarianceRatio[i];
            cumulativeVarianceRatio[i] = cum;
        }

        this.nSamples = n;
        this.pcaFitted = true;

        double cum3 = nMaturities >= 3 ? cumulativeVarianceRatio[2] : 0;
        log.info("PCA 拟合完成: nSamples={}, nMaturities={}, nFactors={}, PC1-3 累计方差={}",
                  n, nMaturities, nFactors, cum3);
        return this;
    }

    /** 设置默认参数（无历史数据时冷启动用） */
    public YieldCurveGenerator setDefaultParams() {
        this.mu = new double[nMaturities];
        double[] base = new double[nMaturities];
        double[] defaults = {1e-3, 3e-4, 1e-4};
        for (int i = 0; i < nMaturities; i++) {
            if (i < defaults.length) base[i] = defaults[i];
            else base[i] = 5e-5;
        }
        this.eigenvalues = base;
        this.eigenvectors = identity(nMaturities);
        double total = 0; for (double v : base) total += v;
        if (total <= 0) total = 1;
        this.explainedVarianceRatio = new double[nMaturities];
        this.cumulativeVarianceRatio = new double[nMaturities];
        double cum = 0;
        for (int i = 0; i < nMaturities; i++) {
            explainedVarianceRatio[i] = base[i] / total;
            cum += explainedVarianceRatio[i];
            cumulativeVarianceRatio[i] = cum;
        }
        this.nSamples = 0;
        this.pcaFitted = true;
        log.warn("YieldCurveGenerator 使用默认参数（无历史数据），HJM 路径仅供演示");
        return this;
    }

    // ===================== HJM 路径生成 =====================

    /**
     * HJM 仿射模型批量生成利率路径
     * @return (n_scenarios, n_steps, n_maturities) 利率路径（百分比）
     */
    public double[][][] generateHjmPaths(int nScenarios, int nSteps, Integer seedOverride, double[] initialYieldsPct) {
        if (!pcaFitted) throw new RuntimeException("YieldCurveGenerator 未拟合");

        if (nScenarios < 1 || nSteps < 1)
            throw new IllegalArgumentException("n_scenarios/n_steps 必须 >= 1");

        if (seedOverride != null) this.seed = seedOverride;
        java.util.Random rng = new java.util.Random(this.seed);

        if (initialYieldsPct == null) {
            initialYieldsPct = new double[nMaturities];
            Arrays.fill(initialYieldsPct, 3.0);
        }

        double dt = 1.0 / 12.0;
        double sqrtDt = Math.sqrt(dt);
        int k = Math.min(nFactors, nMaturities);

        // 因子标准差 sqrt(eigenvalues)
        double[] factorStd = new double[k];
        for (int i = 0; i < k; i++) {
            factorStd[i] = Math.sqrt(Math.max(eigenvalues[i], 0));
        }

        // (nScenarios, nSteps, k) 布朗运动增量
        double[][][] dW = new double[nScenarios][nSteps][k];
        for (int i = 0; i < nScenarios; i++) {
            for (int t = 0; t < nSteps; t++) {
                for (int f = 0; f < k; f++) {
                    dW[i][t][f] = rng.nextGaussian() * sqrtDt;
                }
            }
        }

        // 累积路径 integral
        double[][][] integral = new double[nScenarios][nSteps][k];
        for (int i = 0; i < nScenarios; i++) {
            for (int f = 0; f < k; f++) {
                double sum = 0;
                for (int t = 0; t < nSteps; t++) {
                    sum += dW[i][t][f];
                    integral[i][t][f] = sum;
                }
            }
        }

        // 因子载荷 = eigenvectors[:, :k] × factor_std[nMaturities, k]
        double[][] factorLoadings = new double[nMaturities][k];
        for (int j = 0; j < nMaturities; j++) {
            for (int f = 0; f < k; f++) {
                factorLoadings[j][f] = eigenvectors[j][f] * factorStd[f];
            }
        }

        // paths[s, t, m] = sum_f integral[s, t, f] * loadings[m, f] + initial[m]
        double[][][] paths = new double[nScenarios][nSteps][nMaturities];
        for (int i = 0; i < nScenarios; i++) {
            for (int t = 0; t < nSteps; t++) {
                for (int m = 0; m < nMaturities; m++) {
                    double val = initialYieldsPct[m];
                    for (int f = 0; f < k; f++) {
                        val += integral[i][t][f] * factorLoadings[m][f];
                    }
                    // 利率非负截断
                    paths[i][t][m] = Math.max(val, 0.0);
                }
            }
        }

        this.hjmGenerated = true;
        this.lastHjmPaths = paths;

        log.info("HJM 路径生成完成: scenarios={}, steps={}, shape=({},{},{})",
                  nScenarios, nSteps, nScenarios, nSteps, nMaturities);
        return paths;
    }

    // ===================== 元数据 =====================

    public double[][] getFactorLoadings() {
        if (!pcaFitted) throw new RuntimeException("PCA 未拟合");
        double[][] loadings = new double[nMaturities][nFactors];
        for (int i = 0; i < nMaturities; i++) {
            for (int j = 0; j < nFactors; j++) {
                loadings[i][j] = eigenvectors[i][j];
            }
        }
        return loadings;
    }

    public Map<String, Object> getSummary() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("nSamples", nSamples);
        s.put("nMaturities", nMaturities);
        s.put("maturitiesMonths", toListInt(maturities));
        s.put("nFactors", nFactors);
        s.put("eigenvalues", eigenvalues != null ? toList(eigenvalues) : null);
        s.put("explainedVarianceRatio", explainedVarianceRatio != null ? toList(explainedVarianceRatio) : null);
        s.put("cumulativeVarianceRatio", cumulativeVarianceRatio != null ? toList(cumulativeVarianceRatio) : null);
        if (cumulativeVarianceRatio != null && cumulativeVarianceRatio.length >= 3) {
            s.put("cumulativeVariance3fPct", cumulativeVarianceRatio[2]);
        }
        s.put("factorLoadings", pcaFitted ? toList2D(getFactorLoadings()) : null);
        s.put("pcaFitted", pcaFitted);
        return s;
    }

    // ===================== 矩阵工具 =====================

    /** Jacobi 特征值分解（实对称矩阵，返回升序） */
    private static EigenResult jacobiEigenDecomposition(double[][] a) {
        int n = a.length;
        double[][] m = new double[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(a[i], 0, m[i], 0, n);
        double[][] v = identity(n);
        int maxIter = 100;
        double tol = 1e-12;

        for (int iter = 0; iter < maxIter; iter++) {
            // 找最大非对角元
            int p = 0, q = 1;
            double maxOff = Math.abs(m[0][1]);
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    if (Math.abs(m[i][j]) > maxOff) {
                        maxOff = Math.abs(m[i][j]);
                        p = i; q = j;
                    }
                }
            }
            if (maxOff < tol) break;

            double theta;
            if (Math.abs(m[p][p] - m[q][q]) < 1e-30) {
                theta = Math.PI / 4.0;
            } else {
                theta = 0.5 * Math.atan2(2 * m[p][q], m[p][p] - m[q][q]);
            }
            double c = Math.cos(theta), s = Math.sin(theta);

            // 旋转矩阵作用 m
            double app = c * c * m[p][p] + 2 * c * s * m[p][q] + s * s * m[q][q];
            double aqq = s * s * m[p][p] - 2 * c * s * m[p][q] + c * c * m[q][q];
            double apq = 0;
            m[p][p] = app; m[q][q] = aqq; m[p][q] = apq; m[q][p] = apq;

            for (int i = 0; i < n; i++) {
                if (i != p && i != q) {
                    double aip = c * m[i][p] + s * m[i][q];
                    double aiq = -s * m[i][p] + c * m[i][q];
                    m[i][p] = aip; m[p][i] = aip;
                    m[i][q] = aiq; m[q][i] = aiq;
                }
            }

            // 更新特征向量
            for (int i = 0; i < n; i++) {
                double vip = c * v[i][p] + s * v[i][q];
                double viq = -s * v[i][p] + c * v[i][q];
                v[i][p] = vip;
                v[i][q] = viq;
            }
        }

        double[] evs = new double[n];
        for (int i = 0; i < n; i++) evs[i] = m[i][i];
        return new EigenResult(evs, v);
    }

    private static double[][] identity(int n) {
        double[][] m = new double[n][n];
        for (int i = 0; i < n; i++) m[i][i] = 1;
        return m;
    }

    private static List<Double> toList(double[] a) {
        List<Double> r = new ArrayList<>(a.length);
        for (double v : a) r.add(v);
        return r;
    }

    private static List<Integer> toListInt(int[] a) {
        List<Integer> r = new ArrayList<>(a.length);
        for (int v : a) r.add(v);
        return r;
    }

    private static List<List<Double>> toList2D(double[][] a) {
        List<List<Double>> r = new ArrayList<>();
        for (double[] row : a) r.add(toList(row));
        return r;
    }

    private static class EigenResult {
        double[] eigenvalues;
        double[][] eigenvectors;
        EigenResult(double[] ev, double[][] vec) { this.eigenvalues = ev; this.eigenvectors = vec; }
    }
}