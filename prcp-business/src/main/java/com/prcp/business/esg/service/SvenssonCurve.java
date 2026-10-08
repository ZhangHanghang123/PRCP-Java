package com.prcp.business.esg.service;

import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * PRCP ESG · Svensson 6 参数收益率曲线
 *
 * 公式（对齐 Python services/esg/svensson.py）：
 *   Y(T) = θ₀ + θ₁·H(T/λ₁) + θ₂·(H(T/λ₁) - e^(-T/λ₁)) + θ₃·(H(T/λ₂) - e^(-T/λ₂))
 *   H(x) = (1 - e^(-x)) / x  (Humphrey 衰减函数)
 *
 * 利率以**百分点**形式（如 3.5 表示 3.5%），与 PRCP 数据库字段一致。
 */
@Slf4j
public class SvenssonCurve {

    public static final double EPS = 1e-12;

    public final double theta0;
    public final double theta1;
    public final double theta2;
    public final double theta3;
    public final double lambda1;
    public final double lambda2;

    public SvenssonCurve(double theta0, double theta1, double theta2, double theta3,
                          double lambda1, double lambda2) {
        if (lambda1 <= 0 || lambda2 <= 0) {
            throw new IllegalArgumentException(
                    "lambda1/lambda2 必须 > 0（避免除零），got lambda1=" + lambda1 + ", lambda2=" + lambda2);
        }
        this.theta0 = theta0;
        this.theta1 = theta1;
        this.theta2 = theta2;
        this.theta3 = theta3;
        this.lambda1 = lambda1;
        this.lambda2 = lambda2;
    }

    /** 还原指定期限的利率（百分比） */
    public double[] yieldsPct(int[] maturitiesMonths) {
        double[] result = new double[maturitiesMonths.length];
        for (int i = 0; i < maturitiesMonths.length; i++) {
            result[i] = yieldPct(maturitiesMonths[i]);
        }
        return result;
    }

    public double[] yieldsPct(List<Integer> maturitiesMonths) {
        double[] result = new double[maturitiesMonths.size()];
        for (int i = 0; i < maturitiesMonths.size(); i++) {
            result[i] = yieldPct(maturitiesMonths.get(i));
        }
        return result;
    }

    /** 单点利率（百分比） */
    public double yieldPct(int maturityMonths) {
        double T = maturityMonths / 12.0;
        double x1 = T > EPS ? T / lambda1 : EPS;
        double x2 = T > EPS ? T / lambda2 : EPS;
        double exp1 = Math.exp(-x1);
        double exp2 = Math.exp(-x2);
        double H1 = x1 > EPS ? (1.0 - exp1) / x1 : 1.0;
        double H2 = x2 > EPS ? (1.0 - exp2) / x2 : 1.0;
        return theta0 + theta1 * H1 + theta2 * (H1 - exp1) + theta3 * (H2 - exp2);
    }

    /** 还原利率（小数形式，如 0.035 表示 3.5%） */
    public double[] yieldsDecimal(int[] maturitiesMonths) {
        double[] pct = yieldsPct(maturitiesMonths);
        double[] dec = new double[pct.length];
        for (int i = 0; i < pct.length; i++) dec[i] = pct[i] / 100.0;
        return dec;
    }

    /** 单点折现因子 D(T) = exp(-T · Y(T) / 100) */
    public double discountFactor(double maturityYears) {
        double yPct = yieldPct((int) Math.round(maturityYears * 12));
        return Math.exp(-maturityYears * yPct / 100.0);
    }

    /** 便捷函数：用 6 参数 + 期限数组还原利率（百分比） */
    public static double[] svenssonRates(double t0, double t1, double t2, double t3,
                                          double l1, double l2, int[] maturitiesMonths) {
        return new SvenssonCurve(t0, t1, t2, t3, l1, l2).yieldsPct(maturitiesMonths);
    }
}