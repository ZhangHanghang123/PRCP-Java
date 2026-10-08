package com.prcp.business.esg.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ESG 情景集持久化索引（含 BLOB .npz 字节流 + 9 个预计算 JSON 统计）
 * 对齐 Python prcp_esg_scenario 表（2026-09-22 upgrade_esg_v2.sql B+D 方案）
 *
 * 字段命名说明：避免 p10Json/p50Json 等「数字开头驼峰」字段名（Lombok setter 歧义）
 *              统一改为 percentile10Json 等命名
 */
@Data
@TableName("prcp_esg_scenario")
public class EsgScenario {

    @TableId(type = IdType.AUTO)
    private Long id;

    @JsonAlias({"scheme_id"})
    private Long schemeId;

    @JsonAlias({"last_run_id"})
    private Long lastRunId;

    @JsonAlias({"scenario_code"})
    private String scenarioCode;

    @JsonAlias({"scenario_type"})
    private String scenarioType;   // 默认 esg_factory

    @JsonAlias({"file_path"})
    private String filePath;

    @JsonAlias({"n_scenarios"})
    private Integer nScenarios;

    @JsonAlias({"n_steps"})
    private Integer nSteps;

    @JsonAlias({"n_maturities"})
    private Integer nMaturities;

    private Integer seed;

    @JsonAlias({"maturities_json"})
    private String maturitiesJson;

    @JsonAlias({"file_size_bytes"})
    private Long fileSizeBytes;

    private String description;

    @JsonAlias({"created_by"})
    private Long createdBy;

    @JsonAlias({"created_at"})
    private LocalDateTime createdAt;

    @JsonAlias({"updated_at"})
    private LocalDateTime updatedAt;

    @JsonAlias({"is_deleted"})
    private Integer isDeleted;

    // -------- B+D v2 派生字段（不持久化为列，SELECT 计算） --------

    /** paths_blob 是否非空 */
    @JsonProperty("has_blob")
    private Boolean hasBlob;

    @JsonAlias({"n_zeros"})
    private Integer nZeros;

    @JsonAlias({"n_negatives"})
    private Integer nNegatives;

    // -------- BLOB + 9 个派生 JSON 列（写库字段）--------

    /** .npz 字节流 */
    private byte[] pathsBlob;

    @JsonAlias({"p10_json"})
    private String percentile10Json;

    @JsonAlias({"p50_json"})
    private String percentile50Json;

    @JsonAlias({"p90_json"})
    private String percentile90Json;

    @JsonAlias({"final_mean_json"})
    private String finalMeanJson;

    @JsonAlias({"final_std_json"})
    private String finalStdJson;

    @JsonAlias({"final_min_json"})
    private String finalMinJson;

    @JsonAlias({"final_max_json"})
    private String finalMaxJson;

    @JsonAlias({"vol_per_maturity_json"})
    private String volPerMaturityJson;
}