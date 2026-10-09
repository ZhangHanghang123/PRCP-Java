package com.prcp.business.engines;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>引擎注册表 — 单例工厂 (Strategy + Registry Pattern)</p>
 *
 * <p>线程安全 (synchronized)。新引擎由 {@link EngineAutoRegister} 在 Spring 启动时自动注入, 主入口通过 {@link #getEngine(String)} 获取。</p>
 *
 * <p>对齐 Python:
 * <pre>
 *   register_engine(engine)   → EngineRegistry.register(engine)
 *   get_engine(type)          → EngineRegistry.getEngine(type)
 *   list_engine_types()       → EngineRegistry.listEngineTypes()
 * </pre>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Component
public class EngineRegistry {

    private final Map<String, EngineBase> registry = new LinkedHashMap<>();

    /**
     * <p>注册引擎实例 (线程安全)</p>
     *
     * @param engine 引擎实例 (engineType 必须非空)
     * @throws IllegalArgumentException engine 为空或 engineType 为空
     * @throws IllegalStateException    engineType 重复注册
     */
    public synchronized void register(EngineBase engine) {
        if (engine == null) {
            throw new IllegalArgumentException("engine 不能为空");
        }
        if (engine.getEngineType() == null || engine.getEngineType().isEmpty()) {
            throw new IllegalArgumentException(
                engine.getClass().getSimpleName() + " 必须设置 engineType");
        }
        if (registry.containsKey(engine.getEngineType())) {
            throw new IllegalStateException(
                "引擎类型 '" + engine.getEngineType() + "' 已被 "
                + registry.get(engine.getEngineType()).getClass().getSimpleName()
                + " 注册");
        }
        registry.put(engine.getEngineType(), engine);
    }

    /**
     * <p>获取引擎实例</p>
     *
     * @param engineType 引擎类型
     * @return 引擎实例
     * @throws IllegalArgumentException 类型未注册 (含已注册列表)
     */
    public synchronized EngineBase getEngine(String engineType) {
        EngineBase e = registry.get(engineType);
        if (e == null) {
            throw new IllegalArgumentException(
                "引擎类型 '" + engineType + "' 未注册；已注册的: " + registry.keySet());
        }
        return e;
    }

    /**
     * <p>检查引擎是否已注册</p>
     *
     * @param engineType 引擎类型
     * @return true 已注册 / false 未注册
     */
    public synchronized boolean isRegistered(String engineType) {
        return registry.containsKey(engineType);
    }

    /**
     * <p>列出所有已注册引擎类型 (按注册顺序)</p>
     *
     * @return engineType 字符串列表 (不可修改视图)
     */
    public synchronized List<String> listEngineTypes() {
        return Collections.unmodifiableList(new ArrayList<>(registry.keySet()));
    }

    /**
     * <p>列出所有已注册引擎实例的元信息</p>
     *
     * @return [{engine_type, engine_name}, ...]
     */
    public synchronized List<Map<String, Object>> listEngines() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (EngineBase e : registry.values()) {
            result.add(e.info());
        }
        return result;
    }
}
