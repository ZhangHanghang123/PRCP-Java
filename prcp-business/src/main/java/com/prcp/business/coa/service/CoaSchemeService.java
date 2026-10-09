package com.prcp.business.coa.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.prcp.business.coa.entity.CoaScheme;
import com.prcp.business.coa.mapper.CoaSchemeMapper;
import com.prcp.common.exception.BizException;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>账户册方案 Service (COA Scheme)</p>
 *
 * <p>核心职责:
 * <ol>
 *   <li>列出 ACTIVE / 所有未删除方案</li>
 *   <li>判断方案是否 ACTIVE (sim 模块校验)</li>
 *   <li>方案 CRUD (创建/更新/软删)</li>
 *   <li>按 ID 查询</li>
 * </ol>
 * </p>
 *
 * <p>关键约定:
 * <ul>
 *   <li>软删除: is_deleted=1, 不物理删除</li>
 *   <li>状态字段: status='ACTIVE'/'INACTIVE'</li>
 *   <li>创建时强制 status='ACTIVE', is_deleted=0</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.coa.mapper.CoaSchemeMapper
 * @see com.prcp.business.coa.entity.CoaScheme
 */
@Service
@RequiredArgsConstructor
public class CoaSchemeService extends ServiceImpl<CoaSchemeMapper, CoaScheme> {

    private final CoaSchemeMapper coaSchemeMapper;

    /**
     * <p>列出所有 ACTIVE 方案 (对应 Python GET /coa/schemes)</p>
     *
     * @return ACTIVE 且未删除的方案列表 (按 id 倒序)
     */
    public R<List<CoaScheme>> listActive() {
        QueryWrapper<CoaScheme> qw = new QueryWrapper<>();
        qw.eq("is_deleted", 0).eq("status", "ACTIVE")
          .orderByDesc("id");
        return R.ok(coaSchemeMapper.selectList(qw));
    }

    /**
     * <p>判断方案是否 ACTIVE (sim 模块调用)</p>
     *
     * @param id 方案 ID
     * @return true=ACTIVE 且未删除; false=不存在/已删除/INACTIVE
     */
    public boolean isActive(Long id) {
        if (id == null) return false;
        CoaScheme s = coaSchemeMapper.selectById(id);
        return s != null && Integer.valueOf(0).equals(s.getIsDeleted()) && "ACTIVE".equals(s.getStatus());
    }

    /**
     * <p>列出所有未删除的方案 (含 INACTIVE), 供方案维护 Modal 用</p>
     *
     * @return 未删除的方案列表 (按 id 倒序)
     */
    public R<List<CoaScheme>> listAll() {
        QueryWrapper<CoaScheme> qw = new QueryWrapper<>();
        qw.eq("is_deleted", 0).orderByDesc("id");
        return R.ok(coaSchemeMapper.selectList(qw));
    }

    /**
     * <p>按 ID 查询方案</p>
     *
     * @param id 方案 ID (必填)
     * @return R.ok(scheme); 不存在时抛 notFound
     */
    public R<?> getById(Long id) {
        CoaScheme scheme = coaSchemeMapper.selectById(id);
        if (scheme == null) throw BizException.notFound("方案不存在");
        return R.ok(scheme);
    }

    /**
     * <p>创建方案 (强制 is_deleted=0, status='ACTIVE')</p>
     *
     * @param scheme 方案实体
     * @return R.ok(scheme) 或 R.fail
     */
    public R<?> create(CoaScheme scheme) {
        scheme.setIsDeleted(0);
        scheme.setStatus("ACTIVE");
        boolean ok = save(scheme);
        return ok ? R.ok(scheme) : R.fail("创建失败");
    }

    /**
     * <p>更新方案 (按 id 全字段覆盖)</p>
     *
     * @param id     方案 ID (必填)
     * @param scheme 待更新的方案实体
     * @return R.ok() 或 R.fail
     */
    public R<?> update(Long id, CoaScheme scheme) {
        scheme.setId(id);
        boolean ok = updateById(scheme);
        return ok ? R.ok() : R.fail("更新失败");
    }

    /**
     * <p>软删除方案 (is_deleted=1)</p>
     *
     * @param id 方案 ID (必填)
     * @return R.ok() 或 R.fail
     */
    public R<?> softDelete(Long id) {
        CoaScheme scheme = new CoaScheme();
        scheme.setId(id);
        scheme.setIsDeleted(1);
        boolean ok = updateById(scheme);
        return ok ? R.ok() : R.fail("删除失败");
    }
}

