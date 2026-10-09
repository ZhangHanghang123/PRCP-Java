package com.prcp.business.engines;

import java.util.List;
import java.util.Map;

/**
 * <p>引擎抽象基类 (对位 Python app/services/calculate_engine/base.py EngineBase)</p>
 *
 * <p>设计目的 (Strategy Pattern + 抽象工厂):
 * <ul>
 *   <li>所有引擎对外暴露同一套接口: run / getRun / listRuns / listResults</li>
 *   <li>子类必须设置 engineType / engineName</li>
 *   <li>由 {@link EngineRegistry} 维护单例表, 由 {@link EngineAutoRegister} 自动注册</li>
 * </ul>
 * </p>
 *
 * <p>核心契约:
 * <pre>
 *   run(ctx, schemeId, userId, params)         → Long run_id
 *   getRun(ctx, runId)                          → Map 单次执行
 *   listRuns(ctx, filters)                      → List 历史
 *   listResults(ctx, filters)                   → List 结果快照
 * </pre>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
public abstract class EngineBase {

    /** 引擎类型标识 (如 "new_business") — 子类必须设置 */
    protected String engineType;

    /** 人类可读名称 (如 "新业务模拟引擎") — 子类必须设置 */
    protected String engineName;

    /**
     * <p>执行引擎, 返回 run_id</p>
     *
     * @param ctx      执行上下文 (含 DataSource + JdbcTemplate)
     * @param schemeId 业务方案 ID
     * @param userId   当前用户 ID
     * @param params   引擎特定参数 (如 monthCount)
     * @return run_id (主键)
     */
    public abstract Long run(EngineContext ctx, Long schemeId, Long userId, Map<String, Object> params);

    /**
     * <p>查询单次执行状态</p>
     *
     * @param ctx  执行上下文
     * @param runId 运行 ID
     * @return 单行 Map (status/progress/duration_ms 等), 不存在返回 null
     */
    public abstract Map<String, Object> getRun(EngineContext ctx, Long runId);

    /**
     * <p>列出执行历史</p>
     *
     * @param ctx     执行上下文
     * @param filters 过滤条件 (simSchemeId/simSchemeCode/status/limit)
     * @return 运行记录 Map 列表
     */
    public abstract List<Map<String, Object>> listRuns(EngineContext ctx, Map<String, Object> filters);

    /**
     * <p>查询结果快照</p>
     *
     * @param ctx     执行上下文
     * @param filters 过滤条件 (runId/simSchemeCode/dateOffset/coaNodeId/category/withBuckets)
     * @return 结果行 Map 列表
     */
    public abstract List<Map<String, Object>> listResults(EngineContext ctx, Map<String, Object> filters);

    /**
     * <p>取引擎类型标识</p>
     *
     * @return engineType 字符串
     */
    public String getEngineType() {
        return engineType;
    }

    /**
     * <p>取引擎显示名</p>
     *
     * @return engineName 字符串
     */
    public String getEngineName() {
        return engineName;
    }

    /**
     * <p>取引擎元信息</p>
     *
     * @return {engine_type, engine_name}
     */
    public Map<String, Object> info() {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("engine_type", engineType);
        m.put("engine_name", engineName);
        return m;
    }

    /**
     * <p>toString: 用于日志/调试</p>
     *
     * @return "&lt;ClassName type=xxx&gt;"
     */
    @Override
    public String toString() {
        return "<" + getClass().getSimpleName() + " type=" + engineType + ">";
    }
}
