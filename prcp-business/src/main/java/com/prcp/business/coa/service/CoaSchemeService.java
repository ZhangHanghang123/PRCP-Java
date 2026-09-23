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

@Service
@RequiredArgsConstructor
public class CoaSchemeService extends ServiceImpl<CoaSchemeMapper, CoaScheme> {

    private final CoaSchemeMapper coaSchemeMapper;

    /** 列出所有 ACTIVE 方案（对应 Python GET /coa/schemes） */
    public R<List<CoaScheme>> listActive() {
        QueryWrapper<CoaScheme> qw = new QueryWrapper<>();
        qw.eq("is_deleted", 0).eq("status", "ACTIVE")
          .orderByDesc("id");
        return R.ok(coaSchemeMapper.selectList(qw));
    }

    /** 列出所有未删除的方案（含 INACTIVE），供方案维护 Modal 用 */
    public R<List<CoaScheme>> listAll() {
        QueryWrapper<CoaScheme> qw = new QueryWrapper<>();
        qw.eq("is_deleted", 0).orderByDesc("id");
        return R.ok(coaSchemeMapper.selectList(qw));
    }

    public R<?> getById(Long id) {
        CoaScheme scheme = coaSchemeMapper.selectById(id);
        if (scheme == null) throw BizException.notFound("方案不存在");
        return R.ok(scheme);
    }

    public R<?> create(CoaScheme scheme) {
        scheme.setIsDeleted(0);
        scheme.setStatus("ACTIVE");
        boolean ok = save(scheme);
        return ok ? R.ok(scheme) : R.fail("创建失败");
    }

    public R<?> update(Long id, CoaScheme scheme) {
        scheme.setId(id);
        boolean ok = updateById(scheme);
        return ok ? R.ok() : R.fail("更新失败");
    }

    public R<?> softDelete(Long id) {
        CoaScheme scheme = new CoaScheme();
        scheme.setId(id);
        scheme.setIsDeleted(1);
        boolean ok = updateById(scheme);
        return ok ? R.ok() : R.fail("删除失败");
    }
}

