package com.prcp.business.sys.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 操作日志（PRCP sys_op_log 表）
 * 用于 admin 管理后台审计
 */
@Data
@TableName("sys_op_log")
public class SysOpLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String username;
    private String module;
    private String action;
    private String resourceId;
    private String resourceType;
    private String method;
    private String path;
    private String paramsJson;
    private Integer responseCode;
    private String ipAddress;
    private String userAgent;
    private Integer durationMs;
    private String status;
    private String errorMessage;
    private LocalDateTime createdAt;
}