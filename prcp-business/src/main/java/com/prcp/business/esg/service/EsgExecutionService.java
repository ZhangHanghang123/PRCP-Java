package com.prcp.business.esg.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prcp.business.esg.entity.EsgScenario;
import com.prcp.business.esg.entity.EsgScheme;
import com.prcp.business.esg.mapper.EsgSchemeMapper;
import com.prcp.business.esg.util.EsgGeneratorStore;
import com.prcp.common.exception.BizException;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.FileOutputStream;
import java.time.LocalDate;
import java.util.*;

/**
 * ESG 执行服务（5 端点：fit-pca + generate-hjm + generate + run-all + case/run）
 * 对齐 Python routers/esg.py 执行类端点
 *
 * 关键流程：
 *   fit-pca   → 加载 prcp_esg_curve_point → YieldCurveGenerator.fit_from_params → 写 prcp_esg_run
 *   generate-hjm → 用 fit 后的 generator 生成 HJM 路径 → 保存为 .npz + 写 run
 *   generate  → 把 last_hjm_paths 保存为正式 scenario 记录（含 blob + 9 JSON）
 *   run-all   → 一键三步
 *   case/run  → 一键演示：建方案 + 三步
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EsgExecutionService {

    private final EsgSchemeMapper schemeMapper;
    private final EsgCurveService curveService;
    private final EsgRunService runService;
    private final EsgScenarioService scenarioService;
    private final EsgGeneratorStore generatorStore;

    private final ObjectMapper om = new ObjectMapper();

    // ============ fit_pca ============
    public R<Map<String, Object>> fitPca(Long schemeId, Map<String, Object> body) {
        EsgScheme scheme = schemeMapper.selectByIdActive(schemeId);
        if (scheme == null) throw BizException.badRequest("方案不存在");

        int nFactors = body.get("nFactors") != null
                ? ((Number) body.get("nFactors")).intValue()
                : (body.get("n_factors") != null ? ((Number) body.get("n_factors")).intValue() : scheme.getNFactors());
        int[] maturities = decodeMaturities(scheme.getMaturitiesJson());

        YieldCurveGenerator gen = generatorStore.getOrCreate(schemeId, nFactors, maturities, scheme.getSeed());

        long t0 = System.currentTimeMillis();
        List<Map<String, Object>> curvePoints = curveService.rangePoints(
                scheme.getDataSource(),
                scheme.getStartDate() != null ? scheme.getStartDate().toString() : null,
                scheme.getEndDate() != null ? scheme.getEndDate().toString() : null);

        if (curvePoints.size() < 2) {
            gen.setDefaultParams();
            log.warn("fitPca: 曲线点不足（{}），使用默认参数", curvePoints.size());
        } else {
            try {
                gen.fitFromParams(curvePoints);
            } catch (Exception e) {
                log.warn("fitPca: PCA 拟合失败（{}），降级用默认参数", e.getMessage());
                gen.setDefaultParams();
            }
        }

        int durationMs = (int) (System.currentTimeMillis() - t0);
        Map<String, Object> summary = gen.getSummary();
        summary.put("nSamples", curvePoints.size());

        Long runId = runService.writeRun(schemeId, scheme.getSchemeCode(), "PCA_FIT", "SUCCESS",
                Map.of("nFactors", nFactors, "dataSource", scheme.getDataSource(), "nSamples", curvePoints.size()),
                summary, null, durationMs, null);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("runId", runId);
        resp.put("summary", summary);
        return R.ok(resp);
    }

    // ============ generate_hjm ============
    public R<Map<String, Object>> generateHjm(Long schemeId, Map<String, Object> body) {
        EsgScheme scheme = schemeMapper.selectByIdActive(schemeId);
        if (scheme == null) throw BizException.badRequest("方案不存在");

        YieldCurveGenerator gen = generatorStore.get(schemeId);
        if (gen == null || !gen.isPcaFitted()) throw BizException.badRequest("请先跑 PCA");

        int nScenarios = body.get("nScenarios") != null
                ? ((Number) body.get("nScenarios")).intValue()
                : (body.get("n_scenarios") != null ? ((Number) body.get("n_scenarios")).intValue() : scheme.getNScenarios());
        int nSteps = body.get("nSteps") != null
                ? ((Number) body.get("nSteps")).intValue()
                : (body.get("n_steps") != null ? ((Number) body.get("n_steps")).intValue() : scheme.getNSteps());
        Integer seedOverride = body.get("seed") != null ? ((Number) body.get("seed")).intValue() : null;
        double[] initialYieldsPct = decodeInitialYields(scheme.getInitialYieldsJson(), gen.getMaturities().length);

        long t0 = System.currentTimeMillis();
        double[][][] paths = gen.generateHjmPaths(nScenarios, nSteps, seedOverride, initialYieldsPct);
        int durationMs = (int) (System.currentTimeMillis() - t0);

        // 路径校验
        ScenarioSet.ValidationResult v = new ScenarioSet(nScenarios, nSteps, gen.getMaturities().length,
                paths, gen.getMaturities(), initialYieldsPct != null ? initialYieldsPct : new double[gen.getMaturities().length],
                seedOverride != null ? seedOverride : scheme.getSeed(),
                "HJM 中间产物 scheme=" + schemeId, null)
                .validatePaths(-0.5, true);
        if (!v.valid && v.nNegative > nScenarios * nSteps * 0.1) {
            throw BizException.badRequest("HJM 路径严重异常: " + v.warnings);
        }

        // 保存为 .npz 中间产物
        String caseDir = "/tmp/prcp_esg_cases";
        try { new java.io.File(caseDir).mkdirs(); } catch (Exception ignored) {}
        String fileName = "hjm_" + schemeId + "_" + System.currentTimeMillis() + ".npz";
        String filePath = caseDir + "/" + fileName;

        ScenarioSet sc = new ScenarioSet(nScenarios, nSteps, gen.getMaturities().length,
                paths, gen.getMaturities(),
                initialYieldsPct != null ? initialYieldsPct : new double[gen.getMaturities().length],
                seedOverride != null ? seedOverride : scheme.getSeed(),
                "HJM 中间产物 scheme=" + schemeId, null);
        byte[] npzBytes;
        try { npzBytes = sc.serializeNumpy(); } catch (Exception e) { throw BizException.badRequest("HJM npz 序列化失败: " + e.getMessage()); }

        try (FileOutputStream fos = new FileOutputStream(filePath)) { fos.write(npzBytes); } catch (java.io.IOException e) { throw BizException.badRequest("HJM 文件写入失败: " + e.getMessage()); }
        long fileSize = npzBytes.length;

        Map<String, Object> summary = sc.toSummaryJson();
        summary.put("volatilityDecaying", v.volDecaying);
        summary.put("validationWarnings", v.warnings);

        Map<String, Object> hjmParams = new LinkedHashMap<>();
        hjmParams.put("nScenarios", nScenarios);
        hjmParams.put("nSteps", nSteps);
        hjmParams.put("seed", seedOverride);
        Long runId = runService.writeRun(schemeId, scheme.getSchemeCode(), "HJM_GENERATE", "SUCCESS",
                hjmParams, summary, filePath, durationMs, null);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("runId", runId);
        resp.put("filePath", filePath);
        resp.put("fileSize", fileSize);
        resp.put("summary", summary);
        return R.ok(resp);
    }

    // ============ generate_scenarios ============
    public R<Map<String, Object>> generateScenarios(Long schemeId, Map<String, Object> body) {
        EsgScheme scheme = schemeMapper.selectByIdActive(schemeId);
        if (scheme == null) throw BizException.badRequest("方案不存在");

        YieldCurveGenerator gen = generatorStore.get(schemeId);
        if (gen == null || !gen.isHjmGenerated() || gen.getLastHjmPaths() == null)
            throw BizException.badRequest("请先生成 HJM");

        double[][][] paths = gen.getLastHjmPaths();
        int nScenarios = paths.length;
        int nSteps = paths[0].length;
        int nMaturities = paths[0][0].length;

        String scenarioCode = UUID.randomUUID().toString().substring(0, 12);
        String caseDir = "/tmp/prcp_esg_cases";
        new java.io.File(caseDir).mkdirs();
        String fileName = "scenario_" + schemeId + "_" + scenarioCode + ".npz";
        String filePath = caseDir + "/" + fileName;

        double[] initialYields = new double[nMaturities];
        if (scheme.getInitialYieldsJson() != null) {
            double[] decoded = decodeInitialYields(scheme.getInitialYieldsJson(), nMaturities);
            if (decoded != null) System.arraycopy(decoded, 0, initialYields, 0, Math.min(decoded.length, nMaturities));
        }
        // 缺位补 3.0（与 Python 一致）
        for (int i = 0; i < nMaturities; i++) if (initialYields[i] == 0) initialYields[i] = 3.0;

        ScenarioSet sc = new ScenarioSet(nScenarios, nSteps, nMaturities, paths,
                gen.getMaturities(), initialYields, gen.getSeed(),
                "ESG 方案 #" + schemeId + " 情景集 " + scenarioCode,
                Map.of("schemeId", schemeId, "schemeCode", scheme.getSchemeCode()));
        byte[] npzBytes;
        try { npzBytes = sc.serializeNumpy(); } catch (Exception e) { throw BizException.badRequest("scenario npz 序列化失败: " + e.getMessage()); }
        long fileSize = npzBytes.length;
        try (FileOutputStream fos = new FileOutputStream(filePath)) { fos.write(npzBytes); } catch (java.io.IOException e) { throw BizException.badRequest("scenario 文件写入失败: " + e.getMessage()); }

        Map<String, Object> summary = sc.toSummaryJson();
        ScenarioSet.ValidationResult v = sc.validatePaths(-0.5, true);

        // 写 prcp_esg_scenario（B+D 双写）
        EsgScenario entity = new EsgScenario();
        entity.setSchemeId(schemeId);
        entity.setScenarioCode(scenarioCode);
        entity.setScenarioType("esg_factory");
        entity.setFilePath(filePath);
        entity.setNScenarios(nScenarios);
        entity.setNSteps(nSteps);
        entity.setNMaturities(nMaturities);
        entity.setSeed(gen.getSeed());
        entity.setMaturitiesJson(serializeIntList(gen.getMaturities()));
        entity.setFileSizeBytes(fileSize);
        entity.setDescription(sc.description);
        entity.setCreatedBy(1L);
        entity.setIsDeleted(0);
        // BLOB
        entity.setPathsBlob(npzBytes);
        // 9 JSON（summary 中 p10/p50/p90 是 List<List<Double>>，finalMean 等是 List<Double>）
        entity.setPercentile10Json(serializeObjList(summary.get("p10")));
        entity.setPercentile50Json(serializeObjList(summary.get("p50")));
        entity.setPercentile90Json(serializeObjList(summary.get("p90")));
        entity.setFinalMeanJson(serializeObjList(summary.get("finalDistributionMean")));
        entity.setFinalStdJson(serializeObjList(summary.get("finalDistributionStd")));
        entity.setFinalMinJson(serializeObjList(summary.get("finalDistributionMin")));
        entity.setFinalMaxJson(serializeObjList(summary.get("finalDistributionMax")));
        entity.setVolPerMaturityJson(serializeObjList(summary.get("volPerMaturity")));
        entity.setNZeros(v.nZeros);
        entity.setNNegatives(v.nNegative);

        Long scId = scenarioService.insertScenario(entity);

        // 写 prcp_esg_run
        Long runId = runService.writeRun(schemeId, scheme.getSchemeCode(), "SCENARIO_GENERATE", "SUCCESS",
                Map.of("scenarioCode", scenarioCode),
                Map.of("scenarioCode", scenarioCode, "scId", scId, "fileSizeBytes", fileSize, "pathsShape", List.of(nScenarios, nSteps, nMaturities)),
                filePath, 0, null);

        scenarioService.updateLastRunId(scId, runId);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("runId", runId);
        resp.put("scenarioCode", scenarioCode);
        resp.put("scId", scId);
        resp.put("filePath", filePath);
        return R.ok(resp);
    }

    // ============ run_all（一键三步）============
    public R<Map<String, Object>> runAll(Long schemeId) {
        R<Map<String, Object>> pcaR = fitPca(schemeId, Map.of());
        R<Map<String, Object>> hjmR = generateHjm(schemeId, Map.of());
        R<Map<String, Object>> scR = generateScenarios(schemeId, Map.of());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("schemeId", schemeId);
        resp.put("pcaRunId", pcaR.getData().get("runId"));
        resp.put("hjmRunId", hjmR.getData().get("runId"));
        resp.put("scenarioRunId", scR.getData().get("runId"));
        resp.put("scenarioCode", scR.getData().get("scenarioCode"));
        resp.put("scId", scR.getData().get("scId"));
        resp.put("filePath", scR.getData().get("filePath"));
        return R.ok(resp);
    }

    // ============ case/run-one-click（一键演示）============
    public R<Map<String, Object>> caseRun(Map<String, Object> body) {
        String dataSource = body.get("dataSource") != null ? body.get("dataSource").toString() : "ECB";
        String code = "PRCP_ESG_DEMO_001";
        Long schemeId = schemeMapper.selectIdByCode(code);
        if (schemeId == null) {
            // 新建演示方案
            EsgScheme s = new EsgScheme();
            s.setSchemeCode(code);
            s.setSchemeName("一键案例（演示）");
            s.setDescription("PRCP ESG 一键演示方案");
            s.setDataSource(dataSource);
            s.setStartDate(LocalDate.parse("2024-01-01"));
            s.setEndDate(LocalDate.parse("2026-04-01"));
            s.setNFactors(3);
            s.setMaturitiesJson(serializeIntList(new int[]{1, 3, 6, 12, 24, 60, 84, 120, 240, 360}));
            s.setNScenarios(50);
            s.setNSteps(24);
            s.setSeed(42);
            s.setStatus("READY");
            s.setIsDeleted(0);
            s.setCreatedBy(1L);
            s.setUpdatedBy(1L);
            schemeMapper.insert(s);
            schemeId = s.getId();
        }
        // 跑三步（nScenarios=50, nSteps=24, seed=42）
        Map<String, Object> pcaBody = Map.of("nFactors", 3);
        Map<String, Object> hjmBody = Map.of("nScenarios", 50, "nSteps", 24, "seed", 42);
        R<Map<String, Object>> pcaR = fitPca(schemeId, pcaBody);
        R<Map<String, Object>> hjmR = generateHjm(schemeId, hjmBody);
        R<Map<String, Object>> scR = generateScenarios(schemeId, Map.of());

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("schemeId", schemeId);
        resp.put("schemeCode", code);
        resp.put("pcaRunId", pcaR.getData().get("runId"));
        resp.put("hjmRunId", hjmR.getData().get("runId"));
        resp.put("scenarioRunId", scR.getData().get("runId"));
        resp.put("scenarioCode", scR.getData().get("scenarioCode"));
        resp.put("scId", scR.getData().get("scId"));
        resp.put("filePath", scR.getData().get("filePath"));
        return R.ok(resp);
    }

    // ===================== 工具 =====================

    private int[] decodeMaturities(String json) {
        if (json == null || json.isEmpty()) return YieldCurveGenerator.DEFAULT_MATURITIES_MONTHS;
        try {
            List<Integer> list = om.readValue(json, new TypeReference<List<Integer>>() {});
            int[] arr = new int[list.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = list.get(i);
            return arr;
        } catch (Exception e) {
            return YieldCurveGenerator.DEFAULT_MATURITIES_MONTHS;
        }
    }

    private double[] decodeInitialYields(String json, int expectedLen) {
        if (json == null || json.isEmpty()) return null;
        try {
            List<Double> list = om.readValue(json, new TypeReference<List<Double>>() {});
            double[] arr = new double[Math.max(list.size(), expectedLen)];
            for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
            for (int i = list.size(); i < expectedLen; i++) arr[i] = 3.0;
            return arr;
        } catch (Exception e) { return null; }
    }

    private String serializeIntList(int[] a) {
        try {
            List<Integer> list = new ArrayList<>(a.length);
            for (int v : a) list.add(v);
            return om.writeValueAsString(list);
        } catch (Exception e) { return "[]"; }
    }

    private String serializeObjList(Object obj) {
        if (obj == null) return null;
        try { return om.writeValueAsString(obj); } catch (Exception e) { return null; }
    }

    private String serializeObjList2D(Object obj) {
        if (obj == null) return null;
        try { return om.writeValueAsString(obj); } catch (Exception e) { return null; }
    }

    @SuppressWarnings("unused")
    private String serializeDoubleList(double[] a) {
        try {
            List<Double> list = new ArrayList<>(a.length);
            for (double v : a) list.add(v);
            return om.writeValueAsString(list);
        } catch (Exception e) { return null; }
    }

    private String serializeDoubleList2D(double[][] a) {
        try {
            List<List<Double>> list = new ArrayList<>();
            for (double[] row : a) {
                List<Double> r = new ArrayList<>(row.length);
                for (double v : row) r.add(v);
                list.add(r);
            }
            return om.writeValueAsString(list);
        } catch (Exception e) { return null; }
    }
}