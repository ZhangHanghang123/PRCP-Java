package com.prcp.business.sys.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.sys.entity.SysOpLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface SysOpLogMapper extends BaseMapper<SysOpLog> {

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

    @Select("SELECT COUNT(*) FROM sys_op_log")
    int countAll();

    @Select("SELECT COUNT(*) FROM sys_op_log WHERE created_at >= DATE_SUB(NOW(), INTERVAL 1 DAY)")
    int countLast24h();

    @Select("SELECT module, COUNT(*) AS cnt FROM sys_op_log GROUP BY module ORDER BY cnt DESC LIMIT 10")
    List<Map<String, Object>> groupByModule();

    @Select("SELECT action, COUNT(*) AS cnt FROM sys_op_log WHERE created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY) GROUP BY action")
    List<Map<String, Object>> recentActions();
}