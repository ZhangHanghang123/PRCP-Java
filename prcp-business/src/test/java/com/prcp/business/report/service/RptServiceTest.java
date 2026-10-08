package com.prcp.business.report.service;

import com.prcp.business.report.entity.RptItem;
import com.prcp.business.report.entity.RptReport;
import com.prcp.business.report.mapper.RptItemMapper;
import com.prcp.business.report.mapper.RptMapper;
import com.prcp.common.exception.BizException;
import com.prcp.common.result.R;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RptService 单元测试
 *
 * 覆盖 batchItems 三合一逻辑：
 * - 仅 create → created=N + item_count +N
 * - 仅 update → updated=N
 * - 仅 delete（含子节点递归）→ deleted=N
 * - 混合 + 空 body
 * - 边界：reportId null、报表不存在
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RptService batchItems 三合一")
class RptServiceTest {

    @Mock private RptMapper rptMapper;
    @Mock private RptItemMapper rptItemMapper;

    @InjectMocks private RptService rptService;

    private RptReport report;

    @BeforeEach
    void setUp() {
        report = new RptReport();
        report.setId(1L);
        report.setReportCode("BS");
        report.setReportName("资产负债表");
    }

    @Test
    @DisplayName("batchItems：仅 create 2 条 → created=2 + item_count +2")
    void testBatch_CreateOnly() {
        when(rptMapper.selectById(1L)).thenReturn(report);

        List<Map<String, Object>> createList = Arrays.asList(
            itemMap("002", "总负债", null, "DECIMAL"),
            itemMap("003", "权益",   null, "DECIMAL")
        );
        Map<String, Object> body = new HashMap<>();
        body.put("create", createList);

        when(rptItemMapper.insert(any(RptItem.class))).thenReturn(1);
        when(rptItemMapper.adjustReportItemCount(eq(1L), eq(2))).thenReturn(1);

        R<Map<String, Object>> resp = rptService.batchItems(1L, body);

        assertEquals(0, resp.getCode());
        Map<String, Object> data = resp.getData();
        assertEquals(true, data.get("ok"));
        assertEquals(2, data.get("created"));
        assertEquals(0, data.get("updated"));
        assertEquals(0, data.get("deleted"));
        verify(rptItemMapper, times(2)).insert(any(RptItem.class));
        verify(rptItemMapper).adjustReportItemCount(1L, 2);
    }

    @Test
    @DisplayName("batchItems：仅 update 1 条 → updated=1")
    void testBatch_UpdateOnly() {
        when(rptMapper.selectById(1L)).thenReturn(report);

        List<Map<String, Object>> updateList = Arrays.asList(
            updateMap(1L, "新描述", "a+b")
        );
        Map<String, Object> body = new HashMap<>();
        body.put("update", updateList);

        when(rptItemMapper.updateByDynamic(eq(1L), anyString(), anyMap())).thenReturn(1);

        R<Map<String, Object>> resp = rptService.batchItems(1L, body);

        assertEquals(1, resp.getData().get("updated"));
        verify(rptItemMapper).updateByDynamic(eq(1L), anyString(), anyMap());
        // update 不应该调 adjustReportItemCount
        verify(rptItemMapper, never()).adjustReportItemCount(anyLong(), anyInt());
    }

    @Test
    @DisplayName("batchItems：仅 delete 父项 → 递归软删 2 条")
    void testBatch_DeleteWithChildren() {
        when(rptMapper.selectById(1L)).thenReturn(report);

        // 父项 path="/100/"
        when(rptItemMapper.selectPathById(425L)).thenReturn("/100/");
        // 软删 + path 前缀匹配 → 命中 2 行（父+子）
        // Service 端会拼接 path + "%" = "/100/" + "%" = "/100/%"
        when(rptItemMapper.softDeleteByPathPrefix("/100/%", 1L)).thenReturn(2);

        List<Integer> delList = Arrays.asList(425);
        Map<String, Object> body = new HashMap<>();
        body.put("delete_ids", delList);

        R<Map<String, Object>> resp = rptService.batchItems(1L, body);

        assertEquals(2, resp.getData().get("deleted"));
        verify(rptItemMapper).softDeleteByPathPrefix("/100/%", 1L);
        // deleted=2 → adjustReportItemCount(1L, -2)
        verify(rptItemMapper).adjustReportItemCount(1L, -2);
    }

    @Test
    @DisplayName("batchItems：create + update + delete 混合")
    void testBatch_Mixed() {
        when(rptMapper.selectById(1L)).thenReturn(report);

        Map<String, Object> body = new HashMap<>();
        body.put("create", Arrays.asList(itemMap("100", "新项", null, "DECIMAL")));
        body.put("update", Arrays.asList(updateMap(1L, "新描述", null)));
        body.put("delete_ids", Arrays.asList(50));

        when(rptItemMapper.insert(any(RptItem.class))).thenReturn(1);
        when(rptItemMapper.updateByDynamic(eq(1L), anyString(), anyMap())).thenReturn(1);
        when(rptItemMapper.selectPathById(50L)).thenReturn("/50/");
        // Service 端 path + "%" = "/50/" + "%" = "/50/%"
        when(rptItemMapper.softDeleteByPathPrefix("/50/%", 1L)).thenReturn(1);

        R<Map<String, Object>> resp = rptService.batchItems(1L, body);

        Map<String, Object> data = resp.getData();
        assertEquals(1, data.get("created"));
        assertEquals(1, data.get("updated"));
        assertEquals(1, data.get("deleted"));
        // 增量 = 1 - 1 = 0 → 不调 adjustReportItemCount
        verify(rptItemMapper, never()).adjustReportItemCount(anyLong(), anyInt());
    }

    @Test
    @DisplayName("batchItems：空 body → 全部 0，不调 mapper")
    void testBatch_EmptyBody() {
        when(rptMapper.selectById(1L)).thenReturn(report);

        R<Map<String, Object>> resp = rptService.batchItems(1L, new HashMap<>());

        assertEquals(0, resp.getData().get("created"));
        assertEquals(0, resp.getData().get("updated"));
        assertEquals(0, resp.getData().get("deleted"));
        verify(rptItemMapper, never()).insert(any(RptItem.class));
        verify(rptItemMapper, never()).adjustReportItemCount(anyLong(), anyInt());
    }

    @Test
    @DisplayName("batchItems：null body → 全部 0，不抛异常")
    void testBatch_NullBody() {
        when(rptMapper.selectById(1L)).thenReturn(report);

        R<Map<String, Object>> resp = rptService.batchItems(1L, null);

        assertEquals(0, resp.getData().get("created"));
    }

    @Test
    @DisplayName("batchItems：reportId 为 null → 抛 BizException")
    void testBatch_NullReportId() {
        assertThrows(BizException.class,
            () -> rptService.batchItems(null, new HashMap<>()));
    }

    @Test
    @DisplayName("batchItems：报表不存在 → 抛 BizException")
    void testBatch_ReportNotFound() {
        when(rptMapper.selectById(99L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
            () -> rptService.batchItems(99L, new HashMap<>()));
        assertTrue(ex.getMessage().contains("报表不存在"));
    }

    @Test
    @DisplayName("batchItems：create 时 parentId=0 视为顶级 → level=1 + path=/code/")
    void testBatch_CreateWithParentZero() {
        when(rptMapper.selectById(1L)).thenReturn(report);

        Map<String, Object> body = new HashMap<>();
        body.put("create", Arrays.asList(itemMap("200", "顶级项", 0, "DECIMAL")));

        when(rptItemMapper.insert(any(RptItem.class))).thenAnswer(inv -> {
            RptItem arg = inv.getArgument(0);
            assertEquals(1, arg.getItemLevel(), "parentId=0 应视为顶级 level=1");
            assertEquals("/200/", arg.getPath());
            return 1;
        });

        R<Map<String, Object>> resp = rptService.batchItems(1L, body);
        assertEquals(1, resp.getData().get("created"));
    }

    @Test
    @DisplayName("batchItems：create 时 coa_node_ids=[1,2,3] → 序列化为 JSON")
    void testBatch_CreateWithCoaNodeIds() {
        when(rptMapper.selectById(1L)).thenReturn(report);

        Map<String, Object> itemMap = itemMap("300", "绑定节点项", null, "DECIMAL");
        itemMap.put("coa_node_ids", Arrays.asList(1, 2, 3));
        Map<String, Object> body = new HashMap<>();
        body.put("create", Arrays.asList(itemMap));

        when(rptItemMapper.insert(any(RptItem.class))).thenAnswer(inv -> {
            RptItem arg = inv.getArgument(0);
            assertEquals("[1,2,3]", arg.getCoaNodeIds());
            return 1;
        });

        rptService.batchItems(1L, body);
    }

    /** 工具：构造 create item map */
    private Map<String, Object> itemMap(String code, String name, Object parentId, String dataType) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("item_code", code);
        m.put("item_name", name);
        if (parentId != null) m.put("parent_id", parentId);
        m.put("data_type", dataType);
        return m;
    }

    /** 工具：构造 update item map */
    private Map<String, Object> updateMap(Long id, String description, String formula) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        if (description != null) m.put("description", description);
        if (formula != null) m.put("formula", formula);
        return m;
    }
}