package com.prcp.business.sys.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.sys.entity.SysOpLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * <p>Mapper: sys_op_log 表的 SQL 访问层 (操作日志)</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>insertOpLog - 插入操作日志 (useGeneratedKeys 返回自增 ID)</li>
 *   <li>listLogs / countLogs - 操作日志列表 (按 module/action/username/status 过滤, 分页)</li>
 *   <li>countAll / countLast24h - 全部/近 24h 操作日志计数</li>
 *   <li>groupByModule - 按模块统计 TOP 10</li>
 *   <li>recentActions - 近 7 天按 action 统计</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface SysOpLogMapper extends BaseMapper<SysOpLog> {

    /**
     * <p>插入操作日志 (useGeneratedKeys 返回自增 ID)</p>
     *
     * @param log 操作日志实体 (含 userId/username/module/action/resourceId/method/path/paramsJson/responseCode 等)
     * @return 受影响行数 (通常 1)
     */
    @Insert({
        "INSERT INTO sys_op_log",
        "  (user_id, username, module, action, resource_id, resource_type,",
        "   method, path, params_json, response_code, ip_address, user_agent,",
        "   duration_ms, status, error_message)",
        "VALUES",
        "  (#{userId}, #{username}, #{module}, #{action}, #{resourceId}, #{resourceType},",
        "   #{method}, #{path}, #{paramsJson}, #{responseCode}, #{ipAddress}, #{userAgent},",
        "   #{durationMs}, #{status}, #{errorMessage})"
    })
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertOpLog(SysOpLog log);

    /**
     * <p>操作日志列表 (按 module/action/username/status 过滤, 分页)</p>
     *
     * @param module   模块 (可选)
     * @param action   动作 (可选)
     * @param username 用户名 (可选)
     * @param status   状态 SUCCESS/FAILED (可选)
     * @param pageSize 每页条数
     * @param offset   偏移量
     * @return 日志 Map 列表, 按 ID DESC
     */
    @Select({
        "<script>",
        "SELECT id, user_id AS userId, username, module, action,",
        "       resource_id AS resourceId, resource_type AS resourceType,",
        "       method, path, response_code AS responseCode,",
        "       ip_address AS ipAddress, duration_ms AS durationMs,",
        "       status, error_message AS errorMessage, created_at AS createdAt",
        "  FROM sys_op_log",
        " WHERE 1=1",
        "   <if test='module != null'> AND module = #{module} </if>",
        "   <if test='action != null'> AND action = #{action} </if>",
        "   <if test='username != null'> AND username = #{username} </if>",
        "   <if test='status != null'> AND status = #{status} </if>",
        " ORDER BY id DESC",
        " LIMIT #{pageSize} OFFSET #{offset}",
        "</script>"
    })
    List<Map<String, Object>> listLogs(String module, String action, String username,
                                       String status, int pageSize, int offset);

    /**
     * <p>操作日志总数 (配合 listLogs 分页)</p>
     *
     * @param module   模块 (可选)
     * @param action   动作 (可选)
     * @param username 用户名 (可选)
     * @param status   状态 (可选)
     * @return 总行数
     */
    @Select({
        "<script>",
        "SELECT COUNT(*) FROM sys_op_log WHERE 1=1",
        "   <if test='module != null'> AND module = #{module} </if>",
        "   <if test='action != null'> AND action = #{action} </if>",
        "   <if test='username != null'> AND username = #{username} </if>",
        "   <if test='status != null'> AND status = #{status} </if>",
        "</script>"
    })
    int countLogs(String module, String action, String username, String status);

    /**
     * <p>全部操作日志计数 (Admin Dashboard 用)</p>
     *
     * @return 总条数
     */
    @Select("SELECT COUNT(*) FROM sys_op_log")
    int countAll();

    /**
     * <p>近 24h 操作日志计数</p>
     *
     * @return 近 24h 条数
     */
    @Select("SELECT COUNT(*) FROM sys_op_log WHERE created_at >= DATE_SUB(NOW(), INTERVAL 1 DAY)")
    int countLast24h();

    /**
     * <p>按模块统计 TOP 10</p>
     *
     * @return [{module, cnt}, ...] 按 cnt DESC
     */
    @Select("SELECT module, COUNT(*) AS cnt FROM sys_op_log GROUP BY module ORDER BY cnt DESC LIMIT 10")
    List<Map<String, Object>> groupByModule();

    /**
     * <p>近 7 天按 action 统计</p>
     *
     * @return [{action, cnt}, ...]
     */
    @Select("SELECT action, COUNT(*) AS cnt FROM sys_op_log WHERE created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY) GROUP BY action")
    List<Map<String, Object>> recentActions();
}