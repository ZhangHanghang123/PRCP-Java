package com.prcp.business.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("prcp_model_version")
public class ModelVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long modelId;
    private String versionCode;
    private String versionName;
    private Long parentVersionId;
    private Integer paramCount;
    private String description;
    private String status;
    private Integer isDeleted;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}