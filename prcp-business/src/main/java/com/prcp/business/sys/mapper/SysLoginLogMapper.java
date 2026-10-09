package com.prcp.business.sys.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.sys.entity.SysLoginLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * <p>Mapper: sys_login_log 表的 SQL 访问层 (登录日志)</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>insertLoginLog - 插入登录日志 (useGeneratedKeys 返回自增 ID)</li>
 *   <li>listLogs / countLogs - 登录日志列表 (按 username/action/success 过滤, 分页)</li>
 *   <li>loginSuccessLast24h - 近 24h 登录成功次数</li>
 *   <li>loginFailLast24h - 近 24h 登录失败次数</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface SysLoginLogMapper extends BaseMapper<SysLoginLog> {

    /**
     * <p>插入登录日志 (useGeneratedKeys 返回自增 ID)</p>
     *
     * @param log 登录日志实体 (含 username/action/success/ipAddress/userAgent/errorMessage/tokenId)
     * @return 受影响行数 (通常 1)
     */
    @Insert({
        "INSERT INTO sys_login_log",
        "  (username, action, success, ip_address, user_agent, error_message, token_id)",
        "VALUES",
        "  (#{username}, #{action}, #{success}, #{ipAddress}, #{userAgent}, #{errorMessage}, #{tokenId})"
    })
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertLoginLog(SysLoginLog log);

    /**
     * <p>登录日志列表 (按 username/action/success 过滤, 分页)</p>
     *
     * @param username 用户名 (可选)
     * @param action   动作 LOGIN/LOGOUT (可选)
     * @param success  成功标记 1/0 (可选)
     * @param pageSize 每页条数
     * @param offset   偏移量
     * @return 日志 Map 列表, 按 ID DESC
     */
    @Select({
        "<script>",
        "SELECT id, username, action, success,",
        "       ip_address AS ipAddress, user_agent AS userAgent,",
        "       error_message AS errorMessage, token_id AS tokenId,",
        "       created_at AS createdAt",
        "  FROM sys_login_log",
        " WHERE 1=1",
        "   <if test='username != null'> AND username = #{username} </if>",
        "   <if test='action != null'> AND action = #{action} </if>",
        "   <if test='success != null'> AND success = #{success} </if>",
        " ORDER BY id DESC",
        " LIMIT #{pageSize} OFFSET #{offset}",
        "</script>"
    })
    List<Map<String, Object>> listLogs(String username, String action, Integer success,
                                       int pageSize, int offset);

    /**
     * <p>登录日志总数 (配合 listLogs 分页)</p>
     *
     * @param username 用户名 (可选)
     * @param action   动作 (可选)
     * @param success  成功标记 (可选)
     * @return 总行数
     */
    @Select({
        "<script>",
        "SELECT COUNT(*) FROM sys_login_log WHERE 1=1",
        "   <if test='username != null'> AND username = #{username} </if>",
        "   <if test='action != null'> AND action = #{action} </if>",
        "   <if test='success != null'> AND success = #{success} </if>",
        "</script>"
    })
    int countLogs(String username, String action, Integer success);

    /**
     * <p>近 24h 登录成功次数 (统计用)</p>
     *
     * @return 成功登录数
     */
    @Select("SELECT COUNT(*) FROM sys_login_log WHERE success = 1 AND created_at >= DATE_SUB(NOW(), INTERVAL 1 DAY)")
    int loginSuccessLast24h();

    /**
     * <p>近 24h 登录失败次数 (统计用)</p>
     *
     * @return 失败登录数
     */
    @Select("SELECT COUNT(*) FROM sys_login_log WHERE success = 0 AND created_at >= DATE_SUB(NOW(), INTERVAL 1 DAY)")
    int loginFailLast24h();
}