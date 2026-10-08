package com.prcp.business.rate.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("prcp_rate_scheme")
public class RateScheme {

    @TableId(type = IdType.AUTO)
    private Long id;

    @JsonAlias({"curve_code"})
    private String curveCode;
    @JsonAlias({"curve_name"})
    private String curveName;
    private String curveType;
    // 数据库列名 ccy，但前端用 currency — 接受两种命名
    @JsonProperty("currency")
    @JsonAlias({"ccy"})
    private String ccy;
    @JsonAlias({"data_source"})
    private String dataSource;
    private String description;
    private String status;
    private Integer isDeleted;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}