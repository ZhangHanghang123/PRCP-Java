package com.prcp.business.esg.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ESG 工厂运行历史
 * 对齐 Python prcp_esg_run 表
 */
@Data
@TableName("prcp_esg_run")
public class EsgRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    @JsonAlias({"scheme_id"})
    private Long schemeId;

    @JsonAlias({"scheme_code"})
    private String schemeCode;

    @JsonAlias({"run_type"})
    private String runType;     // PCA_FIT / HJM_GENERATE / SCENARIO_GENERATE

    private String status;       // SUCCESS / FAILED / RUNNING

    @JsonAlias({"params_json"})
    private String paramsJson;

    @JsonAlias({"output_json"})
    private String outputJson;

    @JsonAlias({"file_path"})
    private String filePath;

    @JsonAlias({"duration_ms"})
    private Integer durationMs;

    @JsonAlias({"error_message"})
    private String errorMessage;

    @JsonAlias({"created_by"})
    private Long createdBy;

    @JsonAlias({"created_at"})
    private LocalDateTime createdAt;
}