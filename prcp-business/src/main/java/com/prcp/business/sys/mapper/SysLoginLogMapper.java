package com.prcp.business.sys.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.sys.entity.SysLoginLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface SysLoginLogMapper extends BaseMapper<SysLoginLog> {

    @Insert({
        "INSERT INTO sys_login_log",
        "  (username, action, success, ip_address, user_agent, error_message, token_id)",
        "VALUES",
        "  (#{username}, #{action}, #{success}, #{ipAddress}, #{userAgent}, #{errorMessage}, #{tokenId})"
    })
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertLoginLog(SysLoginLog log);

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

    @Select({
        "<script>",
        "SELECT COUNT(*) FROM sys_login_log WHERE 1=1",
        "   <if test='username != null'> AND username = #{username} </if>",
        "   <if test='action != null'> AND action = #{action} </if>",
        "   <if test='success != null'> AND success = #{success} </if>",
        "</script>"
    })
    int countLogs(String username, String action, Integer success);

    @Select("SELECT COUNT(*) FROM sys_login_log WHERE success = 1 AND created_at >= DATE_SUB(NOW(), INTERVAL 1 DAY)")
    int loginSuccessLast24h();

    @Select("SELECT COUNT(*) FROM sys_login_log WHERE success = 0 AND created_at >= DATE_SUB(NOW(), INTERVAL 1 DAY)")
    int loginFailLast24h();
}