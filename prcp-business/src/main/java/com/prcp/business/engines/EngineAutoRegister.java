package com.prcp.business.engines;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 引擎自动注册 — 启动时把 Spring 容器里所有 EngineBase 实现注册到 EngineRegistry
 *
 * <p>对位 Python 的：
 * <pre>
 *   from app.services.calculate_engine.new_business import NewBusinessEngine
 *   register_engine(NewBusinessEngine())
 * </pre>
 *
 * <p>Java 端借助 Spring 自动扫描所有 EngineBase 子类，自动注册。
 *
 * @author WorkBuddy Agent
 * @date 2026-09-26
 */
@Slf4j
@Component
@Order(1)  // 尽早执行
@RequiredArgsConstructor
public class EngineAutoRegister implements ApplicationRunner {

    private final EngineRegistry registry;
    private final List<EngineBase> engines;  // Spring 自动注入所有 EngineBase 子类

    @Override
    public void run(ApplicationArguments args) {
        if (engines == null || engines.isEmpty()) {
            log.warn("[EngineAutoRegister] 没有发现任何 EngineBase 实现");
            return;
        }
        for (EngineBase e : engines) {
            try {
                registry.register(e);
                log.info("[EngineAutoRegister] 注册引擎成功: type={}, name={}, class={}",
                        e.getEngineType(), e.getEngineName(), e.getClass().getSimpleName());
            } catch (Exception ex) {
                log.error("[EngineAutoRegister] 注册引擎失败: class={}, err={}",
                        e.getClass().getSimpleName(), ex.getMessage());
            }
        }
        log.info("[EngineAutoRegister] 已注册引擎类型: {}", registry.listEngineTypes());
    }
}
