package com.prcp.business.sim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.sim.entity.SimRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * SimRun 基础 CRUD（增删改查）+ 单条查询
 *
 * <p>历史查询（带过滤条件）由 NewBusinessEngine 用 JdbcTemplate 编程式执行
 * （mybatis XML 不支持 &lt;if&gt; 动态 SQL，@Select 注解的 &lt;script&gt; 标签在 XML 不渲染）。
 *
 * @author WorkBuddy Agent
 * @date 2026-09-26
 */
@Mapper
public interface SimRunMapper extends BaseMapper<SimRun> {

    /** 单条查询：返回 Map 含完整字段（便于直接转 JSON） */
    @Select({
        "SELECT id, sim_scheme_id AS simSchemeId, sim_scheme_code AS simSchemeCode,",
        "       base_data_date AS baseDataDate, month_count AS monthCount,",
        "       target_data_date AS targetDataDate, status, progress,",
        "       total_nodes AS totalNodes, processed_nodes AS processedNodes,",
        "       configured_node_count AS configuredNodeCount,",
        "       rolled_node_count AS rolledNodeCount,",
        "       aggregated_node_count AS aggregatedNodeCount,",
        "       duration_ms AS durationMs, error_message AS errorMessage,",
        "       started_at AS startedAt, finished_at AS finishedAt,",
        "       created_at AS createdAt, created_by AS createdBy",
        "  FROM prcp_sim_run WHERE id = #{id}"
    })
    Map<String, Object> selectRunById(@Param("id") Long id);

    /** 软删除（清理某 run） */
    @Select("UPDATE prcp_sim_run SET status='CANCELLED', finished_at=NOW() WHERE id = #{id}")
    int cancelRun(@Param("id") Long id);

    /** 按 sim_scheme_code 取最新 SUCCESS run_id（供 listResults 自动取最新 run 用） */
    @Select("SELECT MAX(id) FROM prcp_sim_run WHERE sim_scheme_code = #{code} AND status = 'SUCCESS'")
    Long findLatestSuccessRunId(@Param("code") String simSchemeCode);
}
