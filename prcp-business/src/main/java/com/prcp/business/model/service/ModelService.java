package com.prcp.business.model.service;

import com.prcp.business.kpi.mapper.KpiMapper;
import com.prcp.business.kpi.service.KpiService;
import com.prcp.business.model.entity.Model;
import com.prcp.business.model.entity.ModelParam;
import com.prcp.business.model.entity.ModelTrain;
import com.prcp.business.model.entity.ModelVersion;
import com.prcp.business.model.mapper.ModelMapper;
import com.prcp.business.model.mapper.ModelParamMapper;
import com.prcp.business.model.mapper.ModelTrainMapper;
import com.prcp.business.model.mapper.ModelVersionMapper;
import com.prcp.common.exception.BizException;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * <p>模型管理 Service (Model + Version + Param + Train)</p>
 *
 * <p>核心职责:
 * <ol>
 *   <li>算法注册表 (7 类算法: 线性回归/逻辑斯蒂/蒙特卡洛/线性规划/蚁群/ARIMA/FNN 大模型)</li>
 *   <li>模型 CRUD (自动创建 V1_BASELINE 初始版本)</li>
 *   <li>模型版本 CRUD + 复制</li>
 *   <li>参数 CRUD (关联 KPI)</li>
 *   <li>训练任务 (异步 CompletableFuture + 状态机 RUNNING/SUCCESS/FAILED/CANCELLED)</li>
 * </ol>
 * </p>
 *
 * <p>关键约定:
 * <ul>
 *   <li>模型唯一约束: uk_model_code (model_code 唯一, 创建时校验)</li>
 *   <li>版本唯一约束: (model_id, version_code), 已删除可复活</li>
 *   <li>软删除级联: 模型删除 → 级联软删所有版本和参数</li>
 *   <li>训练状态机: RUNNING → SUCCESS/FAILED/CANCELLED, 2 秒模拟训练</li>
 *   <li>默认 coa_scheme_id=1 (ZXCOA_V1)</li>
 *   <li>默认训练日期范围: 最近 12 个月</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.model.mapper.ModelMapper
 * @see com.prcp.business.model.entity.Model
 */
@Service
@RequiredArgsConstructor
public class ModelService {

    private final ModelMapper modelMapper;
    private final ModelVersionMapper versionMapper;
    private final ModelParamMapper paramMapper;
    private final ModelTrainMapper trainMapper;
    private final KpiMapper kpiMapper;
    private final KpiService kpiService;

    private static Long uid() { return 1L; }

    /** 算法注册表（对齐 Python ALGORITHMS） */
    private static final List<Map<String, String>> ALGORITHMS = List.of(
        Map.of("code", "LINEAR_REGRESSION", "name", "线性回归", "category", "传统机器学习", "engine", "sklearn.linear_model", "desc", "普通最小二乘法，适合线性关系拟合"),
        Map.of("code", "LOGISTIC_GROWTH", "name", "逻辑斯蒂增长", "category", "传统机器学习", "engine", "numpy", "desc", "S 形曲线，适合存款增长等饱和场景"),
        Map.of("code", "MONTE_CARLO", "name", "蒙特卡洛模拟", "category", "传统机器学习", "engine", "numpy", "desc", "随机抽样，适合不确定性/压力测试"),
        Map.of("code", "LINEAR_PROGRAM", "name", "线性规划", "category", "运筹优化", "engine", "cvxpy", "desc", "CVXPY 求解，适合资产结构优化"),
        Map.of("code", "ANT_COLONY", "name", "蚁群算法", "category", "智能优化", "engine", "services/ant_colony_engine", "desc", "模拟蚂蚁觅食的群体智能优化"),
        Map.of("code", "ARIMA", "name", "ARIMA 时间序列", "category", "传统机器学习", "engine", "statsmodels", "desc", "自回归滑动平均"),
        Map.of("code", "FNN_LLM", "name", "FNN 大模型", "category", "深度学习大模型", "engine", "services/fnn_llm_engine", "desc", "前馈神经网络，适合非线性特征提取")
    );

    /**
     * <p>查询算法注册表 (7 类算法)</p>
     *
     * @return R.ok(Map.of("items", ALGORITHMS))
     */
    public R<Map<String, Object>> listAlgorithms() {
        return R.ok(Collections.singletonMap("items", ALGORITHMS));
    }

    /**
     * <p>查询关联的 KPI 方案下拉选项 (snake_case 字段)</p>
     *
     * @return R.ok(Map.of("items", list))
     */
    public R<Map<String, Object>> schemeOptions() {
        R<List<Map<String, Object>>> res = kpiService.listKpiSchemes();
        List<Map<String, Object>> schemes = res.getData();
        if (schemes == null) schemes = Collections.emptyList();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> s : schemes) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.get("id"));
            m.put("scheme_code", s.get("schemeCode"));
            m.put("scheme_name", s.get("schemeName"));
            m.put("status", s.get("status"));
            m.put("kpi_count", s.get("kpiCount"));
            items.add(m);
        }
        return R.ok(Collections.singletonMap("items", items));
    }

    /**
     * <p>查询模型列表 (按 model_code/name 模糊 + status 过滤)</p>
     *
     * @param keyword 关键字 (可选)
     * @param status  状态 (可选)
     * @return R.ok(Map.of("items", list))
     */
    public R<Map<String, Object>> listModels(String keyword, String status) {
        String kw = (keyword == null || keyword.isEmpty()) ? null : "%" + keyword + "%";
        List<Map<String, Object>> rows = modelMapper.listModels(keyword, kw, status);
        return R.ok(Collections.singletonMap("items", rows));
    }

    /**
     * <p>创建模型 (自动创建 V1_BASELINE 初始版本, 默认 status='ACTIVE')</p>
     *
     * @param body 含 model_code/name/model_type/biz_domain/kpi_scheme_id/description/status
     * @return R.ok(Map.of("id"/"model_code"/"model_name"/"kpi_scheme_id")); model_code 重复时抛 badRequest
     */
    @Transactional
    public R<Map<String, Object>> createModel(Map<String, Object> body) {
        String code = str(body.get("model_code"));
        String name = str(body.get("model_name"));
        if (code == null) throw BizException.badRequest("model_code 必填");
        if (name == null) throw BizException.badRequest("model_name 必填");
        Long kpiId = body.get("kpi_scheme_id") == null ? null : ((Number) body.get("kpi_scheme_id")).longValue();
        if (kpiId != null && kpiMapper.existsScheme(kpiId) == 0)
            throw BizException.badRequest("关联的指标方案不存在");
        Model m = new Model();
        m.setModelCode(code);
        m.setModelName(name);
        m.setModelType(body.get("model_type") == null ? "LINEAR_REGRESSION" : str(body.get("model_type")));
        m.setBizDomain(str(body.get("biz_domain")));
        m.setKpiSchemeId(kpiId);
        m.setDescription(str(body.get("description")));
        // algo_config：JSON 字符串（直接存储为字符串，简单处理）
        m.setAlgoConfig(null);
        m.setStatus(str(body.get("status")) == null ? "ACTIVE" : str(body.get("status")));
        m.setIsDeleted(0);
        m.setCreatedBy(uid());
        m.setUpdatedBy(uid());
        try {
            modelMapper.insert(m);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Duplicate") || msg.contains("uk_model_code")))
                throw BizException.badRequest("模型编码已存在：" + code);
            throw BizException.badRequest("创建失败：" + msg);
        }
        // 自动创建 V1_BASELINE 版本
        ModelVersion v = new ModelVersion();
        v.setModelId(m.getId());
        v.setVersionCode("V1_BASELINE");
        v.setVersionName("基准情景");
        v.setParamCount(0);
        v.setDescription("自动创建的初始版本");
        v.setStatus("DRAFT");
        v.setIsDeleted(0);
        v.setCreatedBy(uid());
        v.setUpdatedBy(uid());
        versionMapper.insert(v);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", m.getId());
        resp.put("model_code", m.getModelCode());
        resp.put("model_name", m.getModelName());
        resp.put("kpi_scheme_id", m.getKpiSchemeId());
        return R.ok(resp);
    }

    /**
     * <p>更新模型 (按字段选择性更新)</p>
     *
     * @param mid  模型 ID (必填)
     * @param body 待更新字段
     * @return R.ok(Map.of("ok", true)); 不存在时抛 notFound
     */
    @Transactional
    public R<Map<String, Object>> updateModel(Long mid, Map<String, Object> body) {
        if (mid == null) throw BizException.badRequest("id 必填");
        Model exist = modelMapper.selectById(mid);
        if (exist == null || Integer.valueOf(1).equals(exist.getIsDeleted()))
            throw BizException.notFound("模型不存在");
        Long kpiId = body.get("kpi_scheme_id") == null ? exist.getKpiSchemeId()
            : (body.get("kpi_scheme_id") == null ? null : ((Number) body.get("kpi_scheme_id")).longValue());
        if (kpiId != null && kpiMapper.existsScheme(kpiId) == 0)
            throw BizException.badRequest("关联的指标方案不存在");
        Model upd = new Model();
        upd.setId(mid);
        if (body.containsKey("model_code")) upd.setModelCode(str(body.get("model_code")));
        if (body.containsKey("model_name")) upd.setModelName(str(body.get("model_name")));
        if (body.containsKey("model_type")) upd.setModelType(str(body.get("model_type")));
        if (body.containsKey("biz_domain")) upd.setBizDomain(str(body.get("biz_domain")));
        upd.setKpiSchemeId(kpiId);
        if (body.containsKey("description")) upd.setDescription(str(body.get("description")));
        if (body.containsKey("status")) upd.setStatus(str(body.get("status")));
        upd.setUpdatedBy(uid());
        upd.setUpdatedAt(java.time.LocalDateTime.now());
        try {
            int n = modelMapper.updateById(upd);
            if (n == 0) throw BizException.notFound("模型不存在");
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Duplicate") || msg.contains("uk_model_code")))
                throw BizException.badRequest("模型编码已存在");
            throw BizException.badRequest("更新失败：" + msg);
        }
        return R.ok(Collections.singletonMap("ok", true));
    }

    /**
     * <p>软删除模型 (级联软删所有版本和参数)</p>
     *
     * @param mid 模型 ID (必填)
     * @return R.ok(Map.of("ok", true)); 不存在时抛 notFound
     */
    @Transactional
    public R<Map<String, Object>> deleteModel(Long mid) {
        if (mid == null) throw BizException.badRequest("id 必填");
        int n = modelMapper.softDeleteById(mid, uid());
        if (n == 0) throw BizException.notFound("模型不存在");
        // 级联软删该模型的所有版本和参数
        List<ModelVersion> versions = versionMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ModelVersion>()
                .eq("model_id", mid).eq("is_deleted", 0));
        for (ModelVersion v : versions) {
            paramMapper.softDeleteByVersion(v.getId(), uid());
            versionMapper.softDeleteById(v.getId(), uid());
        }
        return R.ok(Collections.singletonMap("ok", true));
    }

    /**
     * <p>查询版本列表</p>
     *
     * @param modelId 模型 ID (必填)
     * @param keyword 关键字 (可选)
     * @param status  状态 (可选)
     * @return R.ok(Map.of("items", list))
     */
    public R<Map<String, Object>> listVersions(Long modelId, String keyword, String status) {
        String kw = (keyword == null || keyword.isEmpty()) ? null : "%" + keyword + "%";
        List<Map<String, Object>> rows = versionMapper.listVersions(modelId, keyword, kw, status);
        return R.ok(Collections.singletonMap("items", rows));
    }

    /**
     * <p>创建版本 (智能复活: soft-deleted 同 code 复活, active 同 code 报 409)</p>
     *
     * @param body 含 model_id/version_code/version_name/description/status
     * @return R.ok(Map.of("id"/"version_code", + 可选 "reactivated", true))
     */
    @Transactional
    public R<Map<String, Object>> createVersion(Map<String, Object> body) {
        Long mid = body.get("model_id") == null ? null : ((Number) body.get("model_id")).longValue();
        String vc = str(body.get("version_code"));
        String vn = str(body.get("version_name"));
        if (mid == null) throw BizException.badRequest("model_id 必填");
        if (vc == null) throw BizException.badRequest("version_code 必填");
        if (vn == null) throw BizException.badRequest("version_name 必填");
        // 1) 检查 soft-deleted 同 code → 复活
        Long deletedId = versionMapper.selectDeletedByCode(mid, vc);
        if (deletedId != null) {
            versionMapper.reactivate(deletedId, vn, str(body.get("description")),
                str(body.get("status")) == null ? "DRAFT" : str(body.get("status")), uid());
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("id", deletedId);
            resp.put("version_code", vc);
            resp.put("reactivated", true);
            return R.ok(resp);
        }
        // 2) 检查 active 同 code → 409
        Long activeId = versionMapper.selectActiveByCode(mid, vc);
        if (activeId != null)
            throw BizException.badRequest("该模型下已存在版本 " + vc + "（id=" + activeId + "），请勿重复创建");
        // 3) 正常插入
        ModelVersion v = new ModelVersion();
        v.setModelId(mid);
        v.setVersionCode(vc);
        v.setVersionName(vn);
        v.setParamCount(0);
        v.setDescription(str(body.get("description")));
        v.setStatus(str(body.get("status")) == null ? "DRAFT" : str(body.get("status")));
        v.setIsDeleted(0);
        v.setCreatedBy(uid());
        v.setUpdatedBy(uid());
        try {
            versionMapper.insert(v);
        } catch (Exception e) {
            throw BizException.badRequest("创建失败：" + e.getMessage());
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", v.getId());
        resp.put("version_code", v.getVersionCode());
        return R.ok(resp);
    }

    /**
     * <p>更新版本</p>
     *
     * @param vid  版本 ID (必填)
     * @param body 待更新字段
     * @return R.ok(Map.of("ok", true)); 不存在时抛 notFound
     */
    public R<Map<String, Object>> updateVersion(Long vid, Map<String, Object> body) {
        if (vid == null) throw BizException.badRequest("id 必填");
        ModelVersion upd = new ModelVersion();
        upd.setId(vid);
        if (body.containsKey("version_code")) upd.setVersionCode(str(body.get("version_code")));
        if (body.containsKey("version_name")) upd.setVersionName(str(body.get("version_name")));
        if (body.containsKey("description")) upd.setDescription(str(body.get("description")));
        if (body.containsKey("status")) upd.setStatus(str(body.get("status")));
        upd.setUpdatedBy(uid());
        upd.setUpdatedAt(java.time.LocalDateTime.now());
        int n = versionMapper.updateById(upd);
        if (n == 0) throw BizException.notFound("版本不存在");
        return R.ok(Collections.singletonMap("ok", true));
    }

    /**
     * <p>软删除版本 (级联软删所有参数)</p>
     *
     * @param vid 版本 ID (必填)
     * @return R.ok(Map.of("ok", true)); 不存在时抛 notFound
     */
    @Transactional
    public R<Map<String, Object>> deleteVersion(Long vid) {
        if (vid == null) throw BizException.badRequest("id 必填");
        paramMapper.softDeleteByVersion(vid, uid());
        int n = versionMapper.softDeleteById(vid, uid());
        if (n == 0) throw BizException.notFound("版本不存在");
        return R.ok(Collections.singletonMap("ok", true));
    }

    /**
     * <p>复制版本 (含全部参数, 新版本 status='DRAFT')</p>
     *
     * @param vid  源版本 ID (必填)
     * @param body 含 version_code (必填) + version_name (可选)
     * @return R.ok(Map.of("id"/"version_code"/"copied_params", N))
     */
    @Transactional
    public R<Map<String, Object>> copyVersion(Long vid, Map<String, Object> body) {
        if (vid == null) throw BizException.badRequest("id 必填");
        String newCode = str(body.get("version_code"));
        String newName = str(body.get("version_name"));
        if (newCode == null) throw BizException.badRequest("version_code 必填");
        ModelVersion src = versionMapper.selectById(vid);
        if (src == null || Integer.valueOf(1).equals(src.getIsDeleted()))
            throw BizException.notFound("源版本不存在");
        ModelVersion ins = new ModelVersion();
        ins.setModelId(src.getModelId());
        ins.setVersionCode(newCode);
        ins.setVersionName(newName == null ? "复制自 " + vid : newName);
        ins.setParentVersionId(vid);
        ins.setParamCount(0);
        ins.setDescription("从 " + src.getVersionCode() + " 复制");
        ins.setStatus("DRAFT");
        ins.setIsDeleted(0);
        ins.setCreatedBy(uid());
        ins.setUpdatedBy(uid());
        versionMapper.insert(ins);
        // 复制参数
        List<Map<String, Object>> params = paramMapper.listForCopy(vid);
        for (Map<String, Object> r : params) {
            ModelParam p = new ModelParam();
            p.setVersionId(ins.getId());
            p.setKpiId(r.get("kpi_id") == null ? null : ((Number) r.get("kpi_id")).longValue());
            p.setKpiCode(str(r.get("kpi_code")));
            p.setParamCode(str(r.get("param_code")));
            p.setParamName(str(r.get("param_name")));
            p.setParamType(str(r.get("param_type")));
            p.setParamValue(r.get("param_value") == null ? BigDecimal.ZERO : new BigDecimal(r.get("param_value").toString()));
            p.setUnit(str(r.get("unit")));
            p.setFormula(str(r.get("formula")));
            p.setFormulaDesc(str(r.get("formula_desc")));
            p.setSortOrder(r.get("sort_order") == null ? 0 : ((Number) r.get("sort_order")).intValue());
            p.setDescription(str(r.get("description")));
            p.setIsDeleted(0);
            p.setCreatedBy(uid());
            p.setUpdatedBy(uid());
            paramMapper.insert(p);
        }
        versionMapper.updateParamCount(ins.getId(), params.size(), uid());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", ins.getId());
        resp.put("version_code", newCode);
        resp.put("copied_params", params.size());
        return R.ok(resp);
    }

    /**
     * <p>查询参数列表</p>
     *
     * @param versionId 版本 ID (必填)
     * @return R.ok(Map.of("items", list))
     */
    public R<Map<String, Object>> listParams(Long versionId) {
        List<Map<String, Object>> rows = paramMapper.listParams(versionId);
        return R.ok(Collections.singletonMap("items", rows));
    }

    /**
     * <p>创建参数 (同步更新 version.param_count)</p>
     *
     * @param body 含 version_id/param_code/param_name/kpi_id/kpi_code/param_type/param_value/...
     * @return R.ok(Map.of("id", paramId))
     */
    @Transactional
    public R<Map<String, Object>> saveParam(Map<String, Object> body) {
        Long vid = body.get("version_id") == null ? null : ((Number) body.get("version_id")).longValue();
        if (vid == null) throw BizException.badRequest("version_id 必填");
        String paramCode = str(body.get("param_code"));
        String paramName = str(body.get("param_name"));
        if (paramCode == null) throw BizException.badRequest("param_code 必填");
        if (paramName == null) throw BizException.badRequest("param_name 必填");
        ModelParam p = new ModelParam();
        p.setVersionId(vid);
        p.setKpiId(body.get("kpi_id") == null ? null : ((Number) body.get("kpi_id")).longValue());
        p.setKpiCode(str(body.get("kpi_code")));
        p.setParamCode(paramCode);
        p.setParamName(paramName);
        p.setParamType(body.get("param_type") == null ? "BASE" : str(body.get("param_type")));
        p.setParamCategory(str(body.get("param_category")));
        p.setParamValue(body.get("param_value") == null ? BigDecimal.ZERO
            : new BigDecimal(body.get("param_value").toString()));
        p.setParamValueStr(str(body.get("param_value_str")));
        p.setUnit(str(body.get("unit")));
        p.setFormula(str(body.get("formula")));
        p.setFormulaDesc(str(body.get("formula_desc")));
        p.setSortOrder(body.get("sort_order") == null ? 0 : ((Number) body.get("sort_order")).intValue());
        p.setDescription(str(body.get("description")));
        p.setIsDeleted(0);
        p.setCreatedBy(uid());
        p.setUpdatedBy(uid());
        paramMapper.insert(p);
        // 更新 version.param_count
        versionMapper.updateParamCount(vid, paramMapper.listParams(vid).size(), uid());
        return R.ok(Collections.singletonMap("id", p.getId()));
    }

    /**
     * <p>更新参数 (按字段选择性更新)</p>
     *
     * @param pid  参数 ID (必填)
     * @param body 待更新字段
     * @return R.ok(Map.of("ok", true)); 不存在时抛 notFound
     */
    @Transactional
    public R<Map<String, Object>> updateParam(Long pid, Map<String, Object> body) {
        if (pid == null) throw BizException.badRequest("id 必填");
        ModelParam upd = new ModelParam();
        upd.setId(pid);
        if (body.containsKey("kpi_id")) upd.setKpiId(body.get("kpi_id") == null ? null : ((Number) body.get("kpi_id")).longValue());
        if (body.containsKey("kpi_code")) upd.setKpiCode(str(body.get("kpi_code")));
        if (body.containsKey("param_code")) upd.setParamCode(str(body.get("param_code")));
        if (body.containsKey("param_name")) upd.setParamName(str(body.get("param_name")));
        if (body.containsKey("param_type")) upd.setParamType(str(body.get("param_type")));
        if (body.containsKey("param_category")) upd.setParamCategory(str(body.get("param_category")));
        if (body.containsKey("param_value")) upd.setParamValue(body.get("param_value") == null ? BigDecimal.ZERO
            : new BigDecimal(body.get("param_value").toString()));
        if (body.containsKey("param_value_str")) upd.setParamValueStr(str(body.get("param_value_str")));
        if (body.containsKey("unit")) upd.setUnit(str(body.get("unit")));
        if (body.containsKey("formula")) upd.setFormula(str(body.get("formula")));
        if (body.containsKey("formula_desc")) upd.setFormulaDesc(str(body.get("formula_desc")));
        if (body.containsKey("sort_order")) upd.setSortOrder(body.get("sort_order") == null ? 0
            : ((Number) body.get("sort_order")).intValue());
        if (body.containsKey("description")) upd.setDescription(str(body.get("description")));
        upd.setUpdatedBy(uid());
        upd.setUpdatedAt(java.time.LocalDateTime.now());
        int n = paramMapper.updateById(upd);
        if (n == 0) throw BizException.notFound("参数不存在");
        return R.ok(Collections.singletonMap("ok", true));
    }

    /**
     * <p>软删除参数 (同步更新 version.param_count)</p>
     *
     * @param pid 参数 ID (必填)
     * @return R.ok(Map.of("ok", true)); 不存在时抛 notFound
     */
    @Transactional
    public R<Map<String, Object>> deleteParam(Long pid) {
        if (pid == null) throw BizException.badRequest("id 必填");
        ModelParam p = paramMapper.selectById(pid);
        if (p == null || Integer.valueOf(1).equals(p.getIsDeleted()))
            throw BizException.notFound("参数不存在");
        int n = paramMapper.softDeleteById(pid, uid());
        if (n == 0) throw BizException.notFound("参数不存在");
        versionMapper.updateParamCount(p.getVersionId(), paramMapper.listParams(p.getVersionId()).size(), uid());
        return R.ok(Collections.singletonMap("ok", true));
    }

    /**
     * <p>启动训练 (写 train 记录 + 异步 CompletableFuture 模拟训练, 2 秒后自动 SUCCESS)</p>
     *
     * <p>默认 coa_scheme_id=1 (ZXCOA_V1), 默认训练日期范围=最近 12 个月</p>
     *
     * @param mid  模型 ID (必填)
     * @param body 含 version_id (必填) + coa_scheme_id/balance_date_from/balance_date_to/description
     * @return R.ok(Map.of("ok"/"train_id"/"train_code"/"status", "RUNNING"))
     */
    @Transactional
    public R<Map<String, Object>> startTrain(Long mid, Map<String, Object> body) {
        if (mid == null) throw BizException.badRequest("model_id 必填");
        Model m = modelMapper.selectById(mid);
        if (m == null || Integer.valueOf(1).equals(m.getIsDeleted()))
            throw BizException.notFound("模型不存在");
        Long vid = body.get("version_id") == null ? null : ((Number) body.get("version_id")).longValue();
        if (vid == null) throw BizException.badRequest("version_id 必填");
        ModelTrain t = new ModelTrain();
        t.setTrainCode("TR_" + System.currentTimeMillis());
        t.setModelId(mid);
        t.setVersionId(vid);
        Object coaIdObj = body.get("coa_scheme_id");
        t.setCoaSchemeId(coaIdObj != null
                ? ((Number) coaIdObj).longValue()
                : 1L);  // 默认 ZXCOA_V1
        Object fromObj = body.get("balance_date_from");
        t.setBalanceDateFrom(fromObj != null
                ? java.time.LocalDate.parse(fromObj.toString())
                : java.time.LocalDate.now().minusMonths(12));
        Object toObj = body.get("balance_date_to");
        t.setBalanceDateTo(toObj != null
                ? java.time.LocalDate.parse(toObj.toString())
                : java.time.LocalDate.now());
        t.setStatus("RUNNING");
        t.setProgress(java.math.BigDecimal.ZERO);
        t.setStartAt(java.time.LocalDateTime.now());
        t.setDescription(str(body.get("description")));
        t.setCreatedBy(uid());
        trainMapper.insert(t);
        // 实际训练执行：异步线程简化（Java 端仅做状态机，不跑实际算法）
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(2000); // 模拟训练耗时
                ModelTrain upd = new ModelTrain();
                upd.setId(t.getId());
                upd.setStatus("SUCCESS");
                upd.setProgress(new java.math.BigDecimal(100));
                upd.setEndAt(java.time.LocalDateTime.now());
                upd.setDurationSec(2);
                trainMapper.updateById(upd);
            } catch (Exception e) {
                ModelTrain upd = new ModelTrain();
                upd.setId(t.getId());
                upd.setStatus("FAILED");
                upd.setErrorMessage(e.getMessage());
                upd.setEndAt(java.time.LocalDateTime.now());
                trainMapper.updateById(upd);
            }
        });
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("train_id", t.getId());
        resp.put("train_code", t.getTrainCode());
        resp.put("status", t.getStatus());
        return R.ok(resp);
    }

    /**
     * <p>取消训练 (RUNNING 状态可取消, 其他状态抛错)</p>
     *
     * @param body 含 train_id (必填)
     * @return R.ok(Map.of("ok", true)); 不存在或已结束时抛错
     */
    @Transactional
    public R<Map<String, Object>> cancelTrain(Map<String, Object> body) {
        Long trainId = body.get("train_id") == null ? null : ((Number) body.get("train_id")).longValue();
        if (trainId == null) throw BizException.badRequest("train_id 必填");
        ModelTrain t = trainMapper.selectById(trainId);
        if (t == null || Integer.valueOf(1).equals(t.getIsDeleted()))
            throw BizException.notFound("训练记录不存在");
        if (!"RUNNING".equals(t.getStatus())) throw BizException.badRequest("训练已结束，无法取消");
        ModelTrain upd = new ModelTrain();
        upd.setId(trainId);
        upd.setStatus("CANCELLED");
        upd.setEndAt(java.time.LocalDateTime.now());
        trainMapper.updateById(upd);
        return R.ok(Collections.singletonMap("ok", true));
    }

    /**
     * <p>查询训练日志 (轮询单条 train 详情)</p>
     *
     * @param mid     模型 ID (必填, 校验用)
     * @param trainId 训练记录 ID (可选)
     * @return R.ok(Map.of("items", list))
     */
    public R<Map<String, Object>> trainLogs(Long mid, Long trainId) {
        if (mid == null) throw BizException.badRequest("model_id 必填");
        List<Map<String, Object>> logs = new java.util.ArrayList<>();
        if (trainId != null) {
            ModelTrain t = trainMapper.selectById(trainId);
            if (t != null) {
                Map<String, Object> l = new LinkedHashMap<>();
                l.put("id", t.getId());
                l.put("train_code", t.getTrainCode());
                l.put("status", t.getStatus());
                l.put("progress", t.getProgress());
                l.put("start_at", t.getStartAt());
                l.put("end_at", t.getEndAt());
                l.put("duration_sec", t.getDurationSec());
                l.put("error_message", t.getErrorMessage());
                logs.add(l);
            }
        }
        return R.ok(Collections.singletonMap("items", logs));
    }

    /**
     * <p>查询训练结果 (关联 prcp_model_train_result, 当前为空数组)</p>
     *
     * @param modelId 模型 ID
     * @param trainId 训练记录 ID (预留)
     * @return R.ok(Map.of("items", list))
     */
    public R<Map<String, Object>> trainResults(Long modelId, Long trainId) {
        // 简化：返回当前 trains 列表（如有 trainId 则限定）
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        return R.ok(resp);
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
}