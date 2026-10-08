package com.prcp.business.sim.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 引擎执行记录（prcp_sim_run）— 对位 Python prcp_sim_run 表
 *
 * <p>字段对齐 Python engine.py 中 SQL 的列：
 * <pre>
 *   id, sim_scheme_id, sim_scheme_code, base_data_date, month_count, target_data_date,
 *   status, progress, total_nodes, processed_nodes, configured_node_count,
 *   rolled_node_count, aggregated_node_count, duration_ms, error_message,
 *   started_at, finished_at, created_at, created_by
 * </pre>
 *
 * @author WorkBuddy Agent
 * @date 2026-09-26
 */
@Data
@TableName("prcp_sim_run")
public class SimRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long simSchemeId;
    private String simSchemeCode;
    private LocalDate baseDataDate;
    private Integer monthCount;
    private LocalDate targetDataDate;
    private String status;
    private Integer progress;
    private Integer totalNodes;
    private Integer processedNodes;
    private Integer configuredNodeCount;
    private Integer rolledNodeCount;
    private Integer aggregatedNodeCount;
    private Long durationMs;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private Long createdBy;
}
