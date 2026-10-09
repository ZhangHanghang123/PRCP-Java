package com.prcp.business.sim.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.sim.entity.SimRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * <p>Mapper: prcp_sim_run 表的 SQL 访问层 (新业务模拟运行记录)</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>selectRunById - 单条查询 (返回 Map 含完整字段, 便于直接转 JSON)</li>
 *   <li>cancelRun - 取消运行 (置 status=CANCELLED, finished_at=NOW)</li>
 *   <li>findLatestSuccessRunId - 按 sim_scheme_code 取最新 SUCCESS run_id</li>
 * </ul>
 * </p>
 *
 * <p>历史查询 (带过滤条件) 由 {@link com.prcp.business.engines.new_business.NewBusinessEngine}
 * 用 JdbcTemplate 编程式执行 (mybatis XML 不支持 if 动态 SQL, @Select 注解的 script 标签在 XML 不渲染)。</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface SimRunMapper extends BaseMapper<SimRun> {

    /**
     * <p>单条查询: 返回 Map 含完整字段 (便于直接转 JSON)</p>
     *
     * @param id 运行 ID
     * @return 单行 Map, 含 simSchemeCode/baseDataDate/monthCount/targetDataDate/status/progress/各计数/durationMs 等
     */
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

    /**
     * <p>取消运行 (置 status=CANCELLED, finished_at=NOW)</p>
     *
     * @param id 运行 ID
     * @return 受影响行数
     */
    @Select("UPDATE prcp_sim_run SET status='CANCELLED', finished_at=NOW() WHERE id = #{id}")
    int cancelRun(@Param("id") Long id);

    /**
     * <p>按 sim_scheme_code 取最新 SUCCESS run_id (供 listResults 自动取最新 run 用)</p>
     *
     * @param simSchemeCode 模拟方案编码
     * @return 最新 SUCCESS run 的 ID, 无返回 null
     */
    @Select("SELECT MAX(id) FROM prcp_sim_run WHERE sim_scheme_code = #{code} AND status = 'SUCCESS'")
    Long findLatestSuccessRunId(@Param("code") String simSchemeCode);
}
