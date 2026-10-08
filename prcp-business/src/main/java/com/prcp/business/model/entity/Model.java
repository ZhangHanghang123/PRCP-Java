package com.prcp.business.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("prcp_model")
public class Model {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String modelCode;
    private String modelName;
    private String modelType;
    private String bizDomain;
    private Long kpiSchemeId;
    private String description;
    /** JSON 字符串（map 结构） */
    private String algoConfig;
    private String status;
    private Integer isDeleted;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}