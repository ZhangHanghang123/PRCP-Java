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

/**
 * <p>账户册节点 Service (COA Tree 节点)</p>
 *
 * <p>核心职责:
 * <ol>
 *   <li>按方案列出节点 (平铺/树形)</li>
 *   <li>创建节点 (自动计算 node_level/path)</li>
 *   <li>更新节点 (禁止改 level/path/parent 防破坏树)</li>
 *   <li>软删除 (有子节点不可删)</li>
 *   <li>提供 node_code 查询和节点+最新余额表组合</li>
 * </ol>
 * </p>
 *
 * <p>关键约定:
 * <ul>
 *   <li>软删除: is_deleted=1, 不物理删除</li>
 *   <li>状态字段: status='ACTIVE'/'INACTIVE'</li>
 *   <li>路径: path = "/L1_CODE/L2_CODE/..." (自动拼接)</li>
 *   <li>层级: L1=大类, L2=中类, L3=账户</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.coa.mapper.CoaNodeMapper
 * @see com.prcp.business.coa.entity.CoaNode
 */
@Service
@RequiredArgsConstructor
public class CoaNodeService extends ServiceImpl<CoaNodeMapper, CoaNode> {

    private final CoaNodeMapper coaNodeMapper;

    /**
     * <p>列出某方案下所有节点 (平铺)</p>
     *
     * @param schemeId 方案 ID (必填)
     * @return 节点 Map 列表 (含 id/node_code/node_name/parent_id/node_level/path 等)
     */
    public R<List<Map<String, Object>>> listByScheme(Long schemeId) {
        if (schemeId == null) throw BizException.badRequest("scheme_id 不能为空");
        return R.ok(coaNodeMapper.listByScheme(schemeId));
    }

    /**
     * <p>构造节点树形结构 (前端展示用, 含 children 子节点列表)</p>
     *
     * @param schemeId 方案 ID
     * @return 根节点列表, 每个节点带 children 字段 (递归嵌套)
     */
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

    /**
     * <p>创建账户册节点 (自动计算 node_level/path)</p>
     *
     * <p>当 parent_id 为空或 0 时, 创建为 L1 (path=/); 否则继承父节点的 path + node_code</p>
     *
     * @param node 节点实体 (schemeId/nodeCode/nodeName 必填, parentId 可选)
     * @return R.ok(node) 或 R.fail; 父节点不存在时抛 badRequest
     */
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

    /**
     * <p>更新节点 (强制清空 level/path/parent 以防破坏树)</p>
     *
     * @param id   节点 ID (必填)
     * @param node 待更新的字段 (nodeCode/nodeName/description/status 等)
     * @return R.ok() 或 R.fail; 节点不存在时抛 notFound
     */
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

    /**
     * <p>软删除节点 (有子节点时禁止删除)</p>
     *
     * @param id 节点 ID (必填)
     * @return R.ok() 或 R.fail; 不存在时抛 notFound, 有子节点时抛 badRequest
     */
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

    /**
     * <p>取节点 nodeCode (sim 模块调用)</p>
     *
     * @param id 节点 ID
     * @return node_code 字符串; id 为空或节点不存在时返回 null
     */
    public String codeById(Long id) {
        if (id == null) return null;
        CoaNode n = coaNodeMapper.selectById(id);
        return n == null ? null : n.getNodeCode();
    }

    /**
     * <p>节点基础信息 + 最新一条余额 (sim 模块对齐 Python node_info)</p>
     *
     * <p>节点信息 JOIN 方案信息, 余额来自 prcp_data_balance 最近一条</p>
     *
     * @param coaNodeId 节点 ID (为空或不存在时返回 null)
     * @return Map 含 node 信息 + current_balance_date + current_balance_amount
     */
    public Map<String, Object> nodeWithLatestBalance(Long coaNodeId) {
        if (coaNodeId == null) return null;
        Map<String, Object> node = coaNodeMapper.nodeWithScheme(coaNodeId);
        if (node == null) return null;
        Map<String, Object> bal = coaNodeMapper.latestBalance(coaNodeId);
        node.put("current_balance_date", bal == null ? null : bal.get("dataDate"));
        node.put("current_balance_amount", bal == null ? 0.0 : bal.get("currentAmount"));
        return node;
    }
}
