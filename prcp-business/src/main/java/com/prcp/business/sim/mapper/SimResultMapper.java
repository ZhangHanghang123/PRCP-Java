package com.prcp.business.sim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.sim.entity.SimResult;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * <p>Mapper: prcp_sim_result 表的 SQL 访问层 (新业务模拟结果)</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>softDeleteByRunId - 按 runId 软删除 (前端清理功能)</li>
 *   <li>softDeleteBySchemeCode - 按 sim_scheme_code 软删除 (清理某方案所有结果)</li>
 * </ul>
 * </p>
 *
 * <p>128 桶字段 + 18 元数据列的全量 SELECT/INSERT 由 {@link com.prcp.business.engines.new_business.NewBusinessEngine}
 * 用 JdbcTemplate 编程式执行 (避免 XML 维护噩梦), 本类仅提供标准的 BaseMapper CRUD + 两个软删方法。</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface SimResultMapper extends BaseMapper<SimResult> {

    /**
     * <p>按 runId 软删除 (前端"清理"功能)</p>
     *
     * @param runId 运行 ID
     * @return 受影响行数
     */
    int softDeleteByRunId(@Param("runId") Long runId);

    /**
     * <p>按 sim_scheme_code 软删除 (清理某方案所有结果)</p>
     *
     * @param simSchemeCode 模拟方案编码
     * @return 受影响行数
     */
    int softDeleteBySchemeCode(@Param("simSchemeCode") String simSchemeCode);
}
