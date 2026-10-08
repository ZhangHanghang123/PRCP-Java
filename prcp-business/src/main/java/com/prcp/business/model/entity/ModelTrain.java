package com.prcp.business.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("prcp_model_train")
public class ModelTrain {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String trainCode;
    private Long modelId;
    private Long versionId;
    private Long coaSchemeId;
    private LocalDate balanceDateFrom;
    private LocalDate balanceDateTo;
    private String status;
    private BigDecimal progress;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private Integer durationSec;
    /** JSON metrics */
    private String metrics;
    private String errorMessage;
    private String description;
    private Integer isDeleted;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}