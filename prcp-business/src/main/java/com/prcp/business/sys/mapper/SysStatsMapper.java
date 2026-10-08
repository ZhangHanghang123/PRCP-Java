package com.prcp.business.sys.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 跨表统计（admin Dashboard 用）
 */
@Mapper
public interface SysStatsMapper {

    @Select("SELECT COUNT(*) FROM sys_user WHERE is_deleted = 0")
    int countUsers();

    @Select("SELECT COUNT(*) FROM sys_user WHERE is_deleted = 0 AND status = 1")
    int countActiveUsers();

    @Select("SELECT COUNT(*) FROM sys_role WHERE is_deleted = 0")
    int countRoles();

    @Select("SELECT COUNT(*) FROM sys_role WHERE is_deleted = 0 AND status = 1")
    int countActiveRoles();

    @Select("SELECT COUNT(*) FROM sys_dict WHERE is_deleted = 0")
    int countDicts();

    @Select("SELECT COUNT(*) FROM sys_dict_item WHERE is_deleted = 0")
    int countDictItems();

    @Select("SELECT COUNT(DISTINCT dict_type) FROM sys_dict WHERE is_deleted = 0")
    int countDictTypes();

    @Select("SELECT COUNT(*) FROM sys_op_log")
    int countOpLogs();

    @Select("SELECT COUNT(*) FROM sys_op_log WHERE created_at >= DATE_SUB(NOW(), INTERVAL 1 DAY)")
    int countOpLogsLast24h();

    @Select("SELECT COUNT(*) FROM sys_login_log")
    int countLoginLogs();

    @Select("SELECT username, COUNT(*) AS cnt FROM sys_login_log WHERE success = 1 GROUP BY username ORDER BY cnt DESC LIMIT 5")
    List<Map<String, Object>> topUsers();

    @Select({
        "SELECT action, COUNT(*) AS cnt FROM sys_login_log",
        " WHERE created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)",
        " GROUP BY action ORDER BY cnt DESC"
    })
    List<Map<String, Object>> loginActionsLast7d();

    @Select("SELECT COUNT(*) FROM sys_login_log WHERE success = 1 AND created_at >= DATE_SUB(NOW(), INTERVAL 1 DAY)")
    int loginSuccessLast24h();

    @Select("SELECT COUNT(*) FROM sys_login_log WHERE success = 0 AND created_at >= DATE_SUB(NOW(), INTERVAL 1 DAY)")
    int loginFailLast24h();
}