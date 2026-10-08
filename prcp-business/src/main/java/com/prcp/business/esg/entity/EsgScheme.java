package com.prcp.business.esg.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * ESG 场景工厂方案（HJM-PCA 经济情景生成）
 * 对齐 Python prcp_esg_scheme 表（2026-09-22 upgrade_esg_v1.sql）
 */
@Data
@TableName("prcp_esg_scheme")
public class EsgScheme {

    @TableId(type = IdType.AUTO)
    private Long id;

    @JsonAlias({"scheme_code"})
    private String schemeCode;

    @JsonAlias({"scheme_name"})
    private String schemeName;

    private String description;

    @JsonAlias({"data_source"})
    private String dataSource;       // ECB / FRB / CUSTOM / BANK

    @JsonAlias({"start_date"})
    private LocalDate startDate;

    @JsonAlias({"end_date"})
    private LocalDate endDate;

    @JsonAlias({"n_factors"})
    private Integer nFactors;        // 1~6

    @JsonAlias({"maturities_json"})
    private String maturitiesJson;   // JSON 字符串: List<Integer>

    @JsonAlias({"n_scenarios"})
    private Integer nScenarios;      // 10~10000

    @JsonAlias({"n_steps"})
    private Integer nSteps;          // 12~360

    private Integer seed;

    @JsonAlias({"initial_yields_json"})
    private String initialYieldsJson;// JSON 字符串: List<Double>

    private String status;           // DRAFT / READY / ARCHIVED

    @JsonAlias({"is_deleted"})
    private Integer isDeleted;

    @JsonAlias({"created_by"})
    private Long createdBy;

    @JsonAlias({"updated_by"})
    private Long updatedBy;

    @JsonAlias({"created_at"})
    private LocalDateTime createdAt;

    @JsonAlias({"updated_at"})
    private LocalDateTime updatedAt;
}