package com.prcp.business.sys.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * <p>Mapper: 跨表统计 (Admin Dashboard 用, 不绑定单一表)</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>用户/角色/字典统计: countUsers / countActiveUsers / countRoles / countActiveRoles / countDicts / countDictItems / countDictTypes</li>
 *   <li>操作日志统计: countOpLogs / countOpLogsLast24h</li>
 *   <li>登录日志统计: countLoginLogs / loginSuccessLast24h / loginFailLast24h / topUsers / loginActionsLast7d</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface SysStatsMapper {

    /**
     * <p>用户总数 (含 ACTIVE + DISABLED)</p>
     *
     * @return 用户数 (未软删)
     */
    @Select("SELECT COUNT(*) FROM sys_user WHERE is_deleted = 0")
    int countUsers();

    /**
     * <p>激活用户数 (status=1)</p>
     *
     * @return 激活用户数
     */
    @Select("SELECT COUNT(*) FROM sys_user WHERE is_deleted = 0 AND status = 1")
    int countActiveUsers();

    /**
     * <p>角色总数</p>
     *
     * @return 角色数 (未软删)
     */
    @Select("SELECT COUNT(*) FROM sys_role WHERE is_deleted = 0")
    int countRoles();

    /**
     * <p>激活角色数 (status=1)</p>
     *
     * @return 激活角色数
     */
    @Select("SELECT COUNT(*) FROM sys_role WHERE is_deleted = 0 AND status = 1")
    int countActiveRoles();

    /**
     * <p>字典主表总数</p>
     *
     * @return 字典数 (未软删)
     */
    @Select("SELECT COUNT(*) FROM sys_dict WHERE is_deleted = 0")
    int countDicts();

    /**
     * <p>字典项总数</p>
     *
     * @return 字典项数 (未软删)
     */
    @Select("SELECT COUNT(*) FROM sys_dict_item WHERE is_deleted = 0")
    int countDictItems();

    /**
     * <p>字典类型数 (DISTINCT dict_type)</p>
     *
     * @return 唯一 dict_type 数
     */
    @Select("SELECT COUNT(DISTINCT dict_type) FROM sys_dict WHERE is_deleted = 0")
    int countDictTypes();

    /**
     * <p>操作日志总数</p>
     *
     * @return op_log 条数
     */
    @Select("SELECT COUNT(*) FROM sys_op_log")
    int countOpLogs();

    /**
     * <p>近 24h 操作日志数</p>
     *
     * @return 近 24h op_log 条数
     */
    @Select("SELECT COUNT(*) FROM sys_op_log WHERE created_at >= DATE_SUB(NOW(), INTERVAL 1 DAY)")
    int countOpLogsLast24h();

    /**
     * <p>登录日志总数</p>
     *
     * @return login_log 条数
     */
    @Select("SELECT COUNT(*) FROM sys_login_log")
    int countLoginLogs();

    /**
     * <p>登录活跃 TOP 5 用户 (按成功登录次数)</p>
     *
     * @return [{username, cnt}, ...]
     */
    @Select("SELECT username, COUNT(*) AS cnt FROM sys_login_log WHERE success = 1 GROUP BY username ORDER BY cnt DESC LIMIT 5")
    List<Map<String, Object>> topUsers();

    /**
     * <p>近 7 天登录动作统计</p>
     *
     * @return [{action, cnt}, ...]
     */
    @Select({
        "SELECT action, COUNT(*) AS cnt FROM sys_login_log",
        " WHERE created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)",
        " GROUP BY action ORDER BY cnt DESC"
    })
    List<Map<String, Object>> loginActionsLast7d();

    /**
     * <p>近 24h 登录成功次数</p>
     *
     * @return 成功登录数
     */
    @Select("SELECT COUNT(*) FROM sys_login_log WHERE success = 1 AND created_at >= DATE_SUB(NOW(), INTERVAL 1 DAY)")
    int loginSuccessLast24h();

    /**
     * <p>近 24h 登录失败次数</p>
     *
     * @return 失败登录数
     */
    @Select("SELECT COUNT(*) FROM sys_login_log WHERE success = 0 AND created_at >= DATE_SUB(NOW(), INTERVAL 1 DAY)")
    int loginFailLast24h();
}