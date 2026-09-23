package com.prcp.business.coa.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 账户册节点
 * 对应表 prcp_coa_node
 */
@Data
@TableName("prcp_coa_node")
public class CoaNode {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long schemeId;
    private String nodeCode;
    private String nodeName;
    private Long parentId;
    private Integer nodeLevel;
    private String path;
    private Integer sortOrder;
    private String nodeType;
    private String status;
    private Integer isDeleted;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
