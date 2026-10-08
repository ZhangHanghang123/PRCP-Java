package com.prcp.business.esg.service;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.*;

/**
 * PRCP ESG · ScenarioSet（持久化 + 统计）
 *
 * 对齐 Python services/esg/scenario_set.py：
 *   - 封装 paths (n_scenarios × n_steps × n_maturities) + maturities + initial_yields
 *   - 提供百分位带 + 终期分布 + 波动率
 *   - serializeNumpy() 返回 .npz 字节流（自定义二进制格式，含魔数 + JSON header）
 *
 * 注：纯 Java 不依赖 numpy，自己实现紧凑二进制（paths + 元数据 + 简单索引）
 *     用于存到 prcp_esg_scenario.paths_blob BLOB 字段
 */
@Slf4j
public class ScenarioSet {

    private static final byte[] MAGIC = {'P', 'R', 'C', 'P', 'N', 'P', 'Z', '1'};

    public final int nScenarios;
    public final int nSteps;
    public final int nMaturities;
    public final double[][][] paths;                       // (n_scenarios, n_steps, n_maturities)
    public final int[] maturitiesMonths;
    public final double[] initialYieldsPct;
    public final int seed;
    public final String description;
    public final Map<String, Object> metadata;

    public ScenarioSet(int nScenarios, int nSteps, int nMaturities,
                       double[][][] paths, int[] maturitiesMonths, double[] initialYieldsPct,
                       int seed, String description, Map<String, Object> metadata) {
        if (paths.length != nScenarios || paths[0].length != nSteps || paths[0][0].length != nMaturities)
            throw new IllegalArgumentException("paths 形状不匹配期望 (" + nScenarios + "," + nSteps + "," + nMaturities + ")");
        if (maturitiesMonths.length != nMaturities)
            throw new IllegalArgumentException("maturities_months 长度不一致");
        if (initialYieldsPct.length != nMaturities)
            throw new IllegalArgumentException("initial_yields_pct 长度不一致");
        this.nScenarios = nScenarios;
        this.nSteps = nSteps;
        this.nMaturities = nMaturities;
        this.paths = paths;
        this.maturitiesMonths = maturitiesMonths;
        this.initialYieldsPct = initialYieldsPct;
        this.seed = seed;
        this.description = description;
        this.metadata = metadata != null ? metadata : new HashMap<>();
    }

    // ===================== 派生统计 =====================

    /** 计算利率路径的百分位带 */
    public Map<String, double[][]> getPercentiles(List<Integer> percentiles) {
        if (percentiles == null) percentiles = Arrays.asList(10, 50, 90);
        Map<String, double[][]> result = new LinkedHashMap<>();
        for (int p : percentiles) {
            result.put("p" + p, percentileAcrossScenarios(paths, p));
        }
        return result;
    }

    /** 终期分布（最后一步） */
    public Map<String, double[]> getFinalDistribution() {
        Map<String, double[]> result = new LinkedHashMap<>();
        double[] mean = new double[nMaturities];
        double[] std = new double[nMaturities];
        double[] min = new double[nMaturities];
        double[] max = new double[nMaturities];
        double[] p10 = new double[nMaturities];
        double[] p90 = new double[nMaturities];

        for (int m = 0; m < nMaturities; m++) {
            double[] vals = new double[nScenarios];
            for (int s = 0; s < nScenarios; s++) vals[s] = paths[s][nSteps - 1][m];
            mean[m] = avg(vals);
            std[m] = stddev(vals);
            min[m] = minOf(vals);
            max[m] = maxOf(vals);
            p10[m] = percentile(vals, 10);
            p90[m] = percentile(vals, 90);
        }
        result.put("mean", mean);
        result.put("std", std);
        result.put("min", min);
        result.put("max", max);
        result.put("p10", p10);
        result.put("p90", p90);
        return result;
    }

    /** 每个期限的波动率（最后一步） */
    public double[] getVolatilityPerMaturity() {
        double[] vol = new double[nMaturities];
        for (int m = 0; m < nMaturities; m++) {
            double[] vals = new double[nScenarios];
            for (int s = 0; s < nScenarios; s++) vals[s] = paths[s][nSteps - 1][m];
            vol[m] = stddev(vals);
        }
        return vol;
    }

    /** 生成 summary（写 prcp_esg_run.output_json） */
    public Map<String, Object> toSummaryJson() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("nScenarios", nScenarios);
        s.put("nSteps", nSteps);
        s.put("nMaturities", nMaturities);
        s.put("maturitiesMonths", toList(maturitiesMonths));
        s.put("pathsShape", Arrays.asList(nScenarios, nSteps, nMaturities));

        Map<String, double[][]> pcts = getPercentiles(Arrays.asList(10, 50, 90));
        s.put("p10", toList2D(pcts.get("p10")));
        s.put("p50", toList2D(pcts.get("p50")));
        s.put("p90", toList2D(pcts.get("p90")));

        Map<String, double[]> finalDist = getFinalDistribution();
        s.put("finalDistributionMean", toList(finalDist.get("mean")));
        s.put("finalDistributionStd", toList(finalDist.get("std")));
        s.put("finalDistributionMin", toList(finalDist.get("min")));
        s.put("finalDistributionMax", toList(finalDist.get("max")));
        s.put("volPerMaturity", toList(getVolatilityPerMaturity()));

        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (double[][] sc : paths) for (double[] st : sc) for (double v : st) {
            if (v < min) min = v;
            if (v > max) max = v;
        }
        s.put("pathsMin", min);
        s.put("pathsMax", max);
        return s;
    }

    /** 校验 HJM 路径 */
    public ValidationResult validatePaths(double minThresholdPct, boolean volatilityWarn) {
        List<String> warnings = new ArrayList<>();
        double minRate = Double.POSITIVE_INFINITY, maxRate = Double.NEGATIVE_INFINITY;
        int nNegative = 0, nZeros = 0;
        for (double[][] sc : paths) for (double[] st : sc) for (double v : st) {
            if (v < minRate) minRate = v;
            if (v > maxRate) maxRate = v;
            if (v < minThresholdPct) nNegative++;
            if (v == 0) nZeros++;
        }
        boolean valid = nNegative == 0;
        if (nNegative > 0) warnings.add("HJM 路径出现 " + nNegative + " 个 < " + minThresholdPct + "% 的负值");

        double[] vol = getVolatilityPerMaturity();
        boolean volDecaying = true;
        for (int i = 0; i < vol.length - 1; i++) {
            if (vol[i] < vol[i + 1] - 1e-6) {
                volDecaying = false;
                if (volatilityWarn) warnings.add("波动率非递减 maturity[" + i + "]=" + vol[i] + " < maturity[" + (i+1) + "]=" + vol[i+1]);
                break;
            }
        }

        ValidationResult r = new ValidationResult();
        r.valid = valid;
        r.nNegative = nNegative;
        r.minRate = minRate;
        r.maxRate = maxRate;
        r.nZeros = nZeros;
        r.volPerMaturity = vol;
        r.volDecaying = volDecaying;
        r.warnings = warnings;
        return r;
    }

    // ===================== 持久化（紧凑二进制） =====================

    /**
     * 序列化为自定义 .npz 格式（精简版）
     *
     * 格式：
     *   MAGIC 8B | n_scenarios i32 | n_steps i32 | n_maturities i32 | seed i32
     *   | nMaturities × maturities[i] i32
     *   | nMaturities × initial_yields[i] f64
     *   | nScenarios × nSteps × nMaturities × paths[i][t][m] f64
     *   | descriptionLen i32 | description UTF8
     *   | metadataJsonLen i32 | metadataJson UTF8
     */
    public byte[] serializeNumpy() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.write(MAGIC);
        dos.writeInt(nScenarios);
        dos.writeInt(nSteps);
        dos.writeInt(nMaturities);
        dos.writeInt(seed);
        for (int m : maturitiesMonths) dos.writeInt(m);
        for (double y : initialYieldsPct) dos.writeDouble(y);
        for (double[][] sc : paths) for (double[] st : sc) for (double v : st) dos.writeDouble(v);

        byte[] descBytes = description != null ? description.getBytes("UTF-8") : new byte[0];
        dos.writeInt(descBytes.length);
        if (descBytes.length > 0) dos.write(descBytes);

        String metaJson = toJson(metadata);
        byte[] metaBytes = metaJson.getBytes("UTF-8");
        dos.writeInt(metaBytes.length);
        dos.write(metaBytes);
        dos.flush();
        log.info("ScenarioSet 序列化: {} bytes ({} × {} × {})", baos.size(), nScenarios, nSteps, nMaturities);
        return baos.toByteArray();
    }

    /** 从 .npz 反序列化（兼容 Python 端 npz 不可逆，但支持 Java 自己生成的） */
    public static ScenarioSet deserializeNumpy(byte[] data) throws IOException {
        java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(data);
        java.io.DataInputStream dis = new java.io.DataInputStream(bais);

        byte[] magic = new byte[8];
        dis.readFully(magic);
        if (!Arrays.equals(magic, MAGIC)) throw new IOException("Not a PRCP npz file");

        int ns = dis.readInt();
        int nst = dis.readInt();
        int nm = dis.readInt();
        int seed = dis.readInt();
        int[] mat = new int[nm];
        for (int i = 0; i < nm; i++) mat[i] = dis.readInt();
        double[] iy = new double[nm];
        for (int i = 0; i < nm; i++) iy[i] = dis.readDouble();

        double[][][] paths = new double[ns][nst][nm];
        for (int s = 0; s < ns; s++) for (int t = 0; t < nst; t++) for (int m = 0; m < nm; m++) {
            paths[s][t][m] = dis.readDouble();
        }

        int descLen = dis.readInt();
        String desc = descLen > 0 ? new String(dis.readNBytes(descLen), "UTF-8") : "";

        int metaLen = dis.readInt();
        String metaJson = metaLen > 0 ? new String(dis.readNBytes(metaLen), "UTF-8") : "{}";
        Map<String, Object> meta = parseJson(metaJson);

        return new ScenarioSet(ns, nst, nm, paths, mat, iy, seed, desc, meta);
    }

    // ===================== 工具 =====================

    private static double[][] percentileAcrossScenarios(double[][][] paths, int p) {
        int nSteps = paths[0].length;
        int nMaturities = paths[0][0].length;
        double[][] result = new double[nSteps][nMaturities];
        int nScenarios = paths.length;
        for (int t = 0; t < nSteps; t++) {
            for (int m = 0; m < nMaturities; m++) {
                double[] vals = new double[nScenarios];
                for (int s = 0; s < nScenarios; s++) vals[s] = paths[s][t][m];
                result[t][m] = percentile(vals, p);
            }
        }
        return result;
    }

    private static double percentile(double[] vals, int p) {
        double[] sorted = vals.clone();
        Arrays.sort(sorted);
        double rank = p / 100.0 * (sorted.length - 1);
        int low = (int) Math.floor(rank);
        int high = (int) Math.ceil(rank);
        if (low == high) return sorted[low];
        return sorted[low] + (rank - low) * (sorted[high] - sorted[low]);
    }

    private static double avg(double[] v) {
        double sum = 0; for (double x : v) sum += x;
        return sum / v.length;
    }
    private static double stddev(double[] v) {
        double mean = avg(v);
        double ss = 0;
        for (double x : v) ss += (x - mean) * (x - mean);
        return Math.sqrt(ss / v.length);
    }
    private static double minOf(double[] v) { double m = Double.POSITIVE_INFINITY; for (double x : v) if (x < m) m = x; return m; }
    private static double maxOf(double[] v) { double m = Double.NEGATIVE_INFINITY; for (double x : v) if (x > m) m = x; return m; }

    private static List<Double> toList(double[] a) {
        List<Double> r = new ArrayList<>(a.length);
        for (double v : a) r.add(v);
        return r;
    }
    private static List<Integer> toList(int[] a) {
        List<Integer> r = new ArrayList<>(a.length);
        for (int v : a) r.add(v);
        return r;
    }
    private static List<List<Double>> toList2D(double[][] a) {
        List<List<Double>> r = new ArrayList<>();
        for (double[] row : a) r.add(toList(row));
        return r;
    }

    private static String toJson(Map<String, Object> m) {
        // 极简 JSON 序列化（避免 Jackson 依赖 + 转义问题）
        if (m == null || m.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(e.getKey().replace("\"", "\\\"")).append("\":");
            Object v = e.getValue();
            if (v == null) sb.append("null");
            else if (v instanceof Number || v instanceof Boolean) sb.append(v);
            else sb.append("\"").append(v.toString().replace("\"", "\\\"")).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private static Map<String, Object> parseJson(String json) {
        // 极简 JSON 解析（仅支持平铺 string → string）
        Map<String, Object> m = new HashMap<>();
        if (json == null || json.isEmpty() || json.equals("{}")) return m;
        String s = json.trim();
        if (!s.startsWith("{") || !s.endsWith("}")) return m;
        s = s.substring(1, s.length() - 1);
        for (String pair : s.split(",")) {
            int colonIdx = pair.indexOf(':');
            if (colonIdx < 0) continue;
            String key = pair.substring(0, colonIdx).trim().replaceAll("^\"|\"$", "");
            String val = pair.substring(colonIdx + 1).trim();
            if (val.startsWith("\"") && val.endsWith("\"")) val = val.substring(1, val.length() - 1);
            m.put(key, val);
        }
        return m;
    }

    public static class ValidationResult {
        public boolean valid;
        public int nNegative;
        public int nZeros;
        public double minRate;
        public double maxRate;
        public double[] volPerMaturity;
        public boolean volDecaying;
        public List<String> warnings;
    }
}