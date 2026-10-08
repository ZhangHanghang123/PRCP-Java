package com.prcp.business.esg.util;

import com.prcp.business.esg.service.YieldCurveGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * per-scheme YieldCurveGenerator 缓存（替代 Python _STORE）
 *
 * 设计：
 *   - ConcurrentHashMap 线程安全（Spring Controller 默认单例，但 fit/generate 可能并发）
 *   - 方案更新或软删时调 evict() 清空
 *   - diagnostics() 用于运维排查（前端 cache-info 端点）
 */
@Slf4j
@Component
public class EsgGeneratorStore {

    private final ConcurrentHashMap<Long, YieldCurveGenerator> store = new ConcurrentHashMap<>();

    /** 取或新建（如果配置变了） */
    public YieldCurveGenerator getOrCreate(Long schemeId, int nFactors, int[] maturitiesMonths, int seed) {
        return store.compute(schemeId, (k, old) -> {
            if (old != null
                    && old.getNFactors() == nFactors
                    && Arrays.equals(old.getMaturities(), maturitiesMonths)) {
                return old;
            }
            log.info("[GeneratorStore] 新建 schemeId={} nFactors={} maturities={}", schemeId, nFactors, Arrays.toString(maturitiesMonths));
            return new YieldCurveGenerator(nFactors, maturitiesMonths, seed);
        });
    }

    public YieldCurveGenerator get(Long schemeId) {
        return store.get(schemeId);
    }

    public void evict(Long schemeId) {
        YieldCurveGenerator removed = store.remove(schemeId);
        if (removed != null) log.info("[GeneratorStore] 清空 schemeId={}", schemeId);
    }

    public Map<String, Object> diagnostics() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("cachedSchemes", new ArrayList<>(store.keySet()));
        resp.put("totalCached", store.size());
        Map<String, Map<String, Object>> details = new LinkedHashMap<>();
        store.forEach((sid, g) -> {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("nFactors", g.getNFactors());
            d.put("nMaturities", g.getMaturities().length);
            d.put("nSamples", g.getNSamples());
            d.put("pcaFitted", g.isPcaFitted());
            d.put("hjmGenerated", g.isHjmGenerated());
            d.put("seed", g.getSeed());
            details.put(String.valueOf(sid), d);
        });
        resp.put("details", details);
        return resp;
    }
}