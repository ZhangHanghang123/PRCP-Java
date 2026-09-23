package com.prcp.business.coa.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.prcp.business.coa.entity.CoaNode;
import com.prcp.business.coa.mapper.CoaNodeMapper;
import com.prcp.common.exception.BizException;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CoaNodeService extends ServiceImpl<CoaNodeMapper, CoaNode> {

    private final CoaNodeMapper coaNodeMapper;

    /** 列出某方案下所有节点（平铺） */
    public R<List<Map<String, Object>>> listByScheme(Long schemeId) {
        if (schemeId == null) throw BizException.badRequest("scheme_id 不能为空");
        return R.ok(coaNodeMapper.listByScheme(schemeId));
    }

    /** 构造树形结构（前端展示用） */
    public R<List<Map<String, Object>>> listTree(Long schemeId) {
        List<Map<String, Object>> flat = coaNodeMapper.listByScheme(schemeId);
        return R.ok(buildTree(flat));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildTree(List<Map<String, Object>> flat) {
        Map<Long, Map<String, Object>> map = new HashMap<>();
        for (Map<String, Object> n : flat) {
            n.put("children", new ArrayList<>());
            map.put(((Number) n.get("id")).longValue(), n);
        }
        List<Map<String, Object>> roots = new ArrayList<>();
        for (Map<String, Object> n : flat) {
            Object pid = n.get("parentId");
            if (pid != null && map.containsKey(((Number) pid).longValue())) {
                ((List<Map<String, Object>>) map.get(((Number) pid).longValue()).get("children")).add(n);
            } else {
                roots.add(n);
            }
        }
        return roots;
    }

    @Transactional
    public R<?> create(CoaNode node) {
        if (node.getSchemeId() == null) throw BizException.badRequest("scheme_id 不能为空");
        if (node.getNodeCode() == null || node.getNodeCode().isEmpty())
            throw BizException.badRequest("node_code 不能为空");
        if (node.getNodeName() == null || node.getNodeName().isEmpty())
            throw BizException.badRequest("node_name 不能为空");

        // 计算 level 和 path
        if (node.getParentId() == null || node.getParentId() == 0) {
            node.setNodeLevel(1);
            node.setPath("/");
        } else {
            CoaNode parent = coaNodeMapper.selectById(node.getParentId());
            if (parent == null) throw BizException.badRequest("父节点不存在");
            node.setNodeLevel((parent.getNodeLevel() == null ? 1 : parent.getNodeLevel()) + 1);
            String base = parent.getPath() == null ? "/" : parent.getPath();
            node.setPath(base + node.getNodeCode() + "/");
        }
        node.setIsDeleted(0);
        node.setStatus("ACTIVE");
        boolean ok = save(node);
        return ok ? R.ok(node) : R.fail("创建失败");
    }

    public R<?> update(Long id, CoaNode node) {
        CoaNode existing = coaNodeMapper.selectById(id);
        if (existing == null) throw BizException.notFound("节点不存在");
        node.setId(id);
        // 不允许修改 level/path/parent（防止破坏树）
        node.setNodeLevel(null);
        node.setPath(null);
        node.setParentId(null);
        boolean ok = updateById(node);
        return ok ? R.ok() : R.fail("更新失败");
    }

    @Transactional
    public R<?> softDelete(Long id) {
        CoaNode existing = coaNodeMapper.selectById(id);
        if (existing == null) throw BizException.notFound("节点不存在");
        if (coaNodeMapper.countChildren(id) > 0)
            throw BizException.badRequest("该节点下存在子节点，不能删除");
        CoaNode upd = new CoaNode();
        upd.setId(id);
        upd.setIsDeleted(1);
        upd.setUpdatedAt(LocalDateTime.now());
        boolean ok = updateById(upd);
        return ok ? R.ok() : R.fail("删除失败");
    }
}
