package com.prcp.business.sim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.sim.entity.SimResult;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * SimResult 基础 CRUD
 *
 * <p>128 桶字段 + 18 元数据列的全量 SELECT/INSERT 由 NewBusinessEngine 用 JdbcTemplate
 * 编程式执行（避免 XML 维护噩梦），本类仅提供标准的 BaseMapper CRUD。
 *
 * @author WorkBuddy Agent
 * @date 2026-09-26
 */
@Mapper
public interface SimResultMapper extends BaseMapper<SimResult> {

    /** 按 runId 软删除（前端"清理"功能） */
    int softDeleteByRunId(@Param("runId") Long runId);

    /** 按 sim_scheme_code 软删除（清理某方案所有结果） */
    int softDeleteBySchemeCode(@Param("simSchemeCode") String simSchemeCode);
}
