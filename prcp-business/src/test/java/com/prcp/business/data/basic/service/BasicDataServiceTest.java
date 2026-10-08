package com.prcp.business.data.basic.service;

import com.prcp.business.data.basic.mapper.BasicDataMapper;
import com.prcp.common.result.R;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * BasicDataService 单元测试
 *
 * 覆盖 bySchemeMatrix 的核心聚合逻辑：
 * - L1 节点有数据 → 用 L1 聚合 categories
 * - L1 无数据 → 用所有有数据节点聚合
 * - 中文分类名称识别（资产/负债/权益/表外）
 * - 字段命名 camelCase（origM1, remM1, asfRsf 等）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BasicDataService bySchemeMatrix 聚合")
class BasicDataServiceTest {

    @Mock private BasicDataMapper mapper;

    @InjectMocks private BasicDataService service;

    /** 构造节点 helper（path 用中文 L1_资产 形式，触发 classifyCategory 返回中文） */
    private Map<String, Object> node(Long id, String code, String name, int level, String path) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("coaNodeId", id);
        n.put("nodeCode", code);
        n.put("nodeName", name);
        n.put("nodeLevel", level);
        n.put("path", path);
        n.put("category", null);  // 服务器表无此字段
        n.put("nodeType", null);
        return n;
    }

    /** 构造 matrix 单行 helper（key 与 Service 期望一致：camelCase 如 coaNodeId/currentBalance） */
    private Map<String, Object> matrixRow(Long nodeId, double currentBalance) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("coaNodeId", nodeId);  // Service 用 r.get("coaNodeId")
        m.put("currentBalance", currentBalance);
        m.put("avgBalance", currentBalance * 0.9);
        m.put("asfRsf", 0.85);
        m.put("hqlaFactor", 0.0);
        m.put("weightedRate", 0.04);
        m.put("interestAmount", 100.0);
        m.put("riskWeight", 0.0);
        m.put("origM1", currentBalance);
        m.put("remM1", currentBalance * 0.95);
        return m;
    }

    @Test
    @DisplayName("bySchemeMatrix：L1 资产有 currentBalance > 0 → 用 L1 聚合")
    void testBySchemeMatrix_L1HasData() {
        Long schemeId = 4L;
        // 节点：L1 + L2 + L3 叶子
        List<Map<String, Object>> nodes = Arrays.asList(
            node(1L, "L1_资产", "资产", 1, "/L1_资产/"),
            node(2L, "L1_负债", "负债", 1, "/L1_负债/"),
            node(3L, "L1_表外", "表外", 1, "/L1_表外/"),
            node(10L, "A01", "现金", 2, "/L1_资产/A01/"),
            node(11L, "L01", "存款", 2, "/L1_负债/L01/")
        );
        when(mapper.listNodesByScheme(schemeId)).thenReturn(nodes);

        // L1 资产 currentBalance > 0，负债/表外 = 0
        Map<String, Map<String, Object>> matrix = new LinkedHashMap<>();
        matrix.put("1", matrixRow(1L, 100000));  // L1 资产有数据
        matrix.put("2", matrixRow(2L, 0));       // L1 负债 0
        matrix.put("3", matrixRow(3L, 0));       // L1 表外 0
        matrix.put("10", matrixRow(10L, 50000)); // A01 叶子
        matrix.put("11", matrixRow(11L, 80000)); // L01 叶子
        when(mapper.listBySchemeMatrix(eq(schemeId), eq("2026-09-01"), eq(0), eq("D"), anyList()))
            .thenReturn(matrixListFrom(matrix));

        R<Map<String, Object>> resp = service.bySchemeMatrix(schemeId, "2026-09-01", 0, "D");

        assertEquals(0, resp.getCode());
        Map<String, Object> data = resp.getData();
        assertNotNull(data);
        // 顶层字段
        assertEquals(schemeId, data.get("schemeId"));
        assertEquals("2026-09-01", data.get("dataDate"));
        assertEquals(64, ((List<?>) data.get("buckets")).size());

        // categories 聚合：因为 L1 资产有数据，应该用 L1 路径
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Double>> cats = (Map<String, Map<String, Double>>) data.get("categories");
        assertNotNull(cats);
        // 期望 categories 至少包含 "资产"（来自 L1_资产）
        assertTrue(cats.containsKey("资产"), "categories 应包含 '资产'，实际 keys: " + cats.keySet());
        // "资产" 当前余额 = 100000（L1 自身）
        assertEquals(100000.0, cats.get("资产").get("currentBalance"), 0.01);
        // "负债" L1 = 0，但因为 L1 负债被选中（currentBalance=0 所以 l1HasData=false → 走全节点聚合路径）
        // 实际：因为 L1 资产 currentBalance > 0，l1HasData=true 用 L1 路径
        // 所以 "负债" 会因为 L1 负债 currentBalance=0 而 = 0
        assertEquals(0.0, cats.get("负债").get("currentBalance"), 0.01);
    }

    @Test
    @DisplayName("bySchemeMatrix：L1 全无数据 → 用所有有数据节点聚合")
    void testBySchemeMatrix_FallbackToAllNodes() {
        Long schemeId = 4L;
        List<Map<String, Object>> nodes = Arrays.asList(
            node(1L, "L1_资产", "资产", 1, "/L1_资产/"),
            node(10L, "A01", "现金", 2, "/L1_资产/A01/"),
            node(11L, "A02", "贷款", 2, "/L1_资产/A02/")
        );
        when(mapper.listNodesByScheme(schemeId)).thenReturn(nodes);

        // L1 节点 currentBalance = 0，叶子有数据
        Map<String, Map<String, Object>> matrix = new LinkedHashMap<>();
        matrix.put("1", matrixRow(1L, 0));      // L1 资产 = 0
        matrix.put("10", matrixRow(10L, 30000));// A01
        matrix.put("11", matrixRow(11L, 50000));// A02
        when(mapper.listBySchemeMatrix(eq(schemeId), eq("2026-09-01"), eq(0), eq("D"), anyList()))
            .thenReturn(matrixListFrom(matrix));

        R<Map<String, Object>> resp = service.bySchemeMatrix(schemeId, "2026-09-01", 0, "D");

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Double>> cats = (Map<String, Map<String, Double>>) resp.getData().get("categories");
        // 走 fallback 路径：用所有有数据节点（A01 + A02），都属 "资产"
        assertEquals(80000.0, cats.get("资产").get("currentBalance"), 0.01);
    }

    @Test
    @DisplayName("dates：返回日期列表")
    void testDates() {
        when(mapper.dates(4L)).thenReturn(Arrays.asList("2026-09-01", "2026-08-01", "2026-07-01"));

        R<List<String>> resp = service.dates(4L);
        assertEquals(0, resp.getCode());
        assertEquals(3, resp.getData().size());
        assertEquals("2026-09-01", resp.getData().get(0));
    }

    @Test
    @DisplayName("bySchemeMatrix：schemeId 无节点 → nodes 空，但结构完整")
    void testBySchemeMatrix_EmptyNodes() {
        Long schemeId = 999L;
        when(mapper.listNodesByScheme(schemeId)).thenReturn(new ArrayList<>());
        when(mapper.listBySchemeMatrix(eq(schemeId), eq("2026-09-01"), eq(0), eq("D"), anyList()))
            .thenReturn(new ArrayList<>());

        R<Map<String, Object>> resp = service.bySchemeMatrix(schemeId, "2026-09-01", 0, "D");
        assertEquals(0, resp.getCode());
        assertEquals(0, ((List<?>) resp.getData().get("nodes")).size());
        assertEquals(64, ((List<?>) resp.getData().get("buckets")).size());
    }

    /** 把 Map<nodeId, row> 转成 List<Map>（mapper 真实返回类型）*/
    private static List<Map<String, Object>> matrixListFrom(Map<String, Map<String, Object>> matrix) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : matrix.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>(e.getValue());
            // Service 通过 r.get("coaNodeId") 取节点 id
            row.put("coaNodeId", Long.parseLong(e.getKey()));
            out.add(row);
        }
        return out;
    }
}