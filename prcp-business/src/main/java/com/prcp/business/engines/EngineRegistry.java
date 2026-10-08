package com.prcp.business.engines;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 引擎注册表 — 单例工厂（对位 Python app.services.calculate_engine.registry）
 *
 * <p>线程安全。新引擎只需在子包 {@code EngineRegistrar} 中调用 {@link #register}，
 * 主入口通过 {@link #getEngine(String)} 获取。
 *
 * <p>对齐 Python：
 * <pre>
 *   register_engine(engine)   → EngineRegistry.register(engine)
 *   get_engine(type)          → EngineRegistry.getEngine(type)
 *   list_engine_types()       → EngineRegistry.listEngineTypes()
 * </pre>
 *
 * @author WorkBuddy Agent
 * @date 2026-09-26
 */
@Component
public class EngineRegistry {

    private final Map<String, EngineBase> registry = new LinkedHashMap<>();

    /**
     * 注册引擎实例（线程安全）
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
     * 获取引擎实例
     *
     * @throws IllegalArgumentException 类型未注册
     */
    public synchronized EngineBase getEngine(String engineType) {
        EngineBase e = registry.get(engineType);
        if (e == null) {
            throw new IllegalArgumentException(
                "引擎类型 '" + engineType + "' 未注册；已注册的: " + registry.keySet());
        }
        return e;
    }

    /** 检查是否已注册 */
    public synchronized boolean isRegistered(String engineType) {
        return registry.containsKey(engineType);
    }

    /** 列出所有引擎类型 */
    public synchronized List<String> listEngineTypes() {
        return Collections.unmodifiableList(new ArrayList<>(registry.keySet()));
    }

    /** 列出所有引擎实例信息 */
    public synchronized List<Map<String, Object>> listEngines() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (EngineBase e : registry.values()) {
            result.add(e.info());
        }
        return result;
    }
}
