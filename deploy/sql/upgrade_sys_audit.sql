-- ==========================================================
-- PRCP 系统管理升级 V1：sys_op_log + sys_login_log 表
-- 日期：2026-09-27
-- 用途：admin 管理后台审计/监控
-- ==========================================================

-- 操作日志表：记录所有 POST/PUT/DELETE 操作
CREATE TABLE IF NOT EXISTS sys_op_log (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id         BIGINT       NULL COMMENT '操作用户ID',
    username        VARCHAR(64)  NULL COMMENT '用户名（冗余）',
    module          VARCHAR(48)  NOT NULL COMMENT '模块名（admin/dict/scheme/...）',
    action          VARCHAR(48)  NOT NULL COMMENT '动作（CREATE/UPDATE/DELETE/...）',
    resource_id     VARCHAR(64)  NULL COMMENT '资源ID（如方案ID）',
    resource_type   VARCHAR(48)  NULL COMMENT '资源类型',
    method          VARCHAR(8)   NULL COMMENT 'HTTP 方法',
    path            VARCHAR(255) NULL COMMENT '请求路径',
    params_json     TEXT         NULL COMMENT '请求参数 JSON',
    response_code   INT          NULL COMMENT '响应 code',
    ip_address      VARCHAR(64)  NULL COMMENT 'IP 地址',
    user_agent      VARCHAR(255) NULL COMMENT 'User-Agent',
    duration_ms     INT          NULL COMMENT '耗时 ms',
    status          VARCHAR(16)  NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS/FAILURE',
    error_message   VARCHAR(1024) NULL COMMENT '错误信息',
    created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    KEY idx_user_id (user_id),
    KEY idx_module_action (module, action),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统操作日志';

-- 登录日志表：记录登录登出
CREATE TABLE IF NOT EXISTS sys_login_log (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    username        VARCHAR(64)  NOT NULL COMMENT '用户名',
    action          VARCHAR(16)  NOT NULL COMMENT 'LOGIN/LOGOUT/LOGIN_FAIL',
    success         TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否成功',
    ip_address      VARCHAR(64)  NULL COMMENT 'IP',
    user_agent      VARCHAR(255) NULL COMMENT 'UA',
    error_message   VARCHAR(255) NULL COMMENT '失败原因',
    token_id        VARCHAR(64)  NULL COMMENT 'JWT ID',
    created_at      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    KEY idx_username (username),
    KEY idx_action (action),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统登录日志';