package com.prcp.business.sys.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 登录日志（PRCP sys_login_log 表）
 */
@Data
@TableName("sys_login_log")
public class SysLoginLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;
    private String action;       // LOGIN/LOGOUT/LOGIN_FAIL
    private Integer success;
    private String ipAddress;
    private String userAgent;
    private String errorMessage;
    private String tokenId;
    private LocalDateTime createdAt;
}