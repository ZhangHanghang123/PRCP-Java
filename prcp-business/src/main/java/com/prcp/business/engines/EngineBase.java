package com.prcp.business.engines;

import java.util.List;
import java.util.Map;

/**
 * 引擎抽象基类（对位 Python app.services.calculate_engine.base.EngineBase）
 *
 * <p>设计目的：
 * <pre>
 *   所有引擎对外暴露同一套接口：
 *     run(db, scheme_id, user, params...)      → 执行，返回 run_id
 *     getRun(db, runId)                         → 查询单次执行
 *     listRuns(db, filters...)                  → 执行历史
 *     listResults(db, filters...)               → 结果快照
 *
 *   子类必须设置 engineType / engineName
 * </pre>
 *
 * <p>对齐 Python：{@code app/services/calculate_engine/base.py}
 *
 * @author WorkBuddy Agent
 * @date 2026-09-26
 */
public abstract class EngineBase {

    // ===== 元数据（子类必须设置） =====

    /** 引擎类型标识（如 "new_business"） */
    protected String engineType;

    /** 人类可读名称（如 "新业务模拟引擎"） */
    protected String engineName;

    // ===== 业务接口（子类必须实现） =====

    /**
     * 执行引擎，返回 run_id
     *
     * @param ctx       执行上下文（含 dataSource 等资源；与 Python 不同，Java 端用 dataSource + sqlHelper 组合）
     * @param schemeId  业务方案 id
     * @param userId    当前用户 id
     * @param params    引擎特定参数（如 monthCount）
     */
    public abstract Long run(EngineContext ctx, Long schemeId, Long userId, Map<String, Object> params);

    /**
     * 查询单次执行状态
     */
    public abstract Map<String, Object> getRun(EngineContext ctx, Long runId);

    /**
     * 列出执行历史
     */
    public abstract List<Map<String, Object>> listRuns(EngineContext ctx, Map<String, Object> filters);

    /**
     * 查询结果快照
     */
    public abstract List<Map<String, Object>> listResults(EngineContext ctx, Map<String, Object> filters);

    // ===== 辅助方法 =====

    public String getEngineType() {
        return engineType;
    }

    public String getEngineName() {
        return engineName;
    }

    public Map<String, Object> info() {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("engine_type", engineType);
        m.put("engine_name", engineName);
        return m;
    }

    @Override
    public String toString() {
        return "<" + getClass().getSimpleName() + " type=" + engineType + ">";
    }
}
