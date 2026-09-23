package com.prcp.business.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 报表表项（树形）
 * 对应表 prcp_rpt_item
 */
@Data
@TableName("prcp_rpt_item")
public class RptItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long reportId;
    private String category;
    private String itemCode;
    private String itemName;
    private Long parentId;
    private Integer itemLevel;
    private String dataType;
    private String formula;
    private String coaNodeIds;   // JSON 字符串
    private String path;
    private Integer sortOrder;
    private String status;
    private String description;
    private Integer isDeleted;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}