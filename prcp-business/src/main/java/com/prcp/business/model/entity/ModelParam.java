package com.prcp.business.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("prcp_model_param")
public class ModelParam {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long versionId;
    private Long kpiId;
    private String kpiCode;
    private String paramCode;
    private String paramName;
    private String paramType;
    private String paramCategory;
    private BigDecimal paramValue;
    private String paramValueStr;
    private String unit;
    private String formula;
    private String formulaDesc;
    private Integer sortOrder;
    private String description;
    private Integer isDeleted;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}