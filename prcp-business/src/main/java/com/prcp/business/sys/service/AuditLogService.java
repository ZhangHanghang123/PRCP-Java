package com.prcp.business.sys.service;

import com.prcp.business.sys.entity.SysOpLog;
import com.prcp.business.sys.entity.SysLoginLog;
import com.prcp.business.sys.mapper.SysOpLogMapper;
import com.prcp.business.sys.mapper.SysLoginLogMapper;
import com.prcp.business.sys.mapper.SysStatsMapper;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 审计日志服务（5 端点）
 * 对齐 Python admin.py 缺失的 sys_op_log + sys_login_log
 *
 * 端点：
 *   GET    /admin/audit-logs?module=&action=&username=&status=&page=&pageSize=
 *   POST   /admin/audit-logs                （记录操作日志，供内部业务调用）
 *   GET    /admin/login-logs?username=&action=&success=&page=&pageSize=
 *   POST   /admin/login-logs                （记录登录日志）
 *   GET    /admin/stats                     （Dashboard 统计）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final SysOpLogMapper opLogMapper;
    private final SysLoginLogMapper loginLogMapper;
    private final SysStatsMapper statsMapper;

    // ============ 操作日志 ============

    public R<Map<String, Object>> listOpLogs(String module, String action, String username,
                                              String status, int page, int pageSize) {
        module = emptyToNull(module);
        action = emptyToNull(action);
        username = emptyToNull(username);
        status = emptyToNull(status);
        int offset = (page - 1) * pageSize;
        List<Map<String, Object>> items = opLogMapper.listLogs(module, action, username, status, pageSize, offset);
        int total = opLogMapper.countLogs(module, action, username, status);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        resp.put("total", total);
        resp.put("page", page);
        resp.put("pageSize", pageSize);
        return R.ok(resp);
    }

    /** 内部业务调用（如反算引擎执行后）记录审计日志 */
    public Long writeOpLog(SysOpLog log) {
        if (log.getStatus() == null) log.setStatus("SUCCESS");
        opLogMapper.insertOpLog(log);
        return log.getId();
    }

    // ============ 登录日志 ============

    public R<Map<String, Object>> listLoginLogs(String username, String action, Integer success,
                                                 int page, int pageSize) {
        username = emptyToNull(username);
        action = emptyToNull(action);
        int offset = (page - 1) * pageSize;
        List<Map<String, Object>> items = loginLogMapper.listLogs(username, action, success, pageSize, offset);
        int total = loginLogMapper.countLogs(username, action, success);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("items", items);
        resp.put("total", total);
        resp.put("page", page);
        resp.put("pageSize", pageSize);
        return R.ok(resp);
    }

    public Long writeLoginLog(SysLoginLog log) {
        loginLogMapper.insertLoginLog(log);
        return log.getId();
    }

    // ============ Dashboard 统计 ============

    public R<Map<String, Object>> stats() {
        Map<String, Object> resp = new LinkedHashMap<>();

        // 用户/角色/字典
        resp.put("userCount", statsMapper.countUsers());
        resp.put("userActiveCount", statsMapper.countActiveUsers());
        resp.put("roleCount", statsMapper.countRoles());
        resp.put("roleActiveCount", statsMapper.countActiveRoles());
        resp.put("dictCount", statsMapper.countDicts());
        resp.put("dictItemCount", statsMapper.countDictItems());
        resp.put("dictTypeCount", statsMapper.countDictTypes());

        // 操作日志
        resp.put("opLogCount", statsMapper.countOpLogs());
        resp.put("opLogLast24h", statsMapper.countOpLogsLast24h());
        resp.put("loginLogCount", statsMapper.countLoginLogs());
        resp.put("loginSuccessLast24h", statsMapper.loginSuccessLast24h());
        resp.put("loginFailLast24h", statsMapper.loginFailLast24h());

        // Top 5 登录用户
        resp.put("topUsers", statsMapper.topUsers());
        // 最近 7 天登录动作分布
        resp.put("loginActionsLast7d", statsMapper.loginActionsLast7d());

        return R.ok(resp);
    }

    private static String emptyToNull(String s) { return (s == null || s.isEmpty()) ? null : s; }
}