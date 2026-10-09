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
 * <p>审计日志 Service (5 端点 + Dashboard 统计)</p>
 *
 * <p>核心职责:
 * <ol>
 *   <li>操作日志查询 (按 module/action/username/status 过滤, 分页)</li>
 *   <li>写操作日志 (供内部业务调用, 默认 status='SUCCESS')</li>
 *   <li>登录日志查询 (按 username/action/success 过滤, 分页)</li>
 *   <li>写登录日志</li>
 *   <li>Dashboard 统计 (用户/角色/字典/日志计数 + Top 5 登录用户 + 7 天登录动作分布)</li>
 * </ol>
 * </p>
 *
 * <p>关键约定:
 * <ul>
 *   <li>表: sys_op_log / sys_login_log / 统计走 sys_stats_mapper</li>
 *   <li>对齐 Python admin.py 缺失的 sys_op_log + sys_login_log</li>
 *   <li>默认操作日志状态: status='SUCCESS'</li>
 *   <li>空串视为无过滤条件</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.sys.mapper.SysOpLogMapper
 * @see com.prcp.business.sys.mapper.SysLoginLogMapper
 * @see com.prcp.business.sys.mapper.SysStatsMapper
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final SysOpLogMapper opLogMapper;
    private final SysLoginLogMapper loginLogMapper;
    private final SysStatsMapper statsMapper;

    /**
     * <p>查询操作日志 (按 module/action/username/status 过滤, 分页)</p>
     *
     * @param module   模块 (可选)
     * @param action   动作 (可选)
     * @param username 用户名 (可选)
     * @param status   状态 (可选)
     * @param page     页码 (从 1 开始)
     * @param pageSize 每页条数
     * @return R.ok(Map.of("items"/"total"/"page"/"pageSize", ...))
     */
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

    /**
     * <p>写操作日志 (内部业务调用, 如反算引擎执行后; 默认 status='SUCCESS')</p>
     *
     * @param log 操作日志实体
     * @return 写入的日志 ID
     */
    public Long writeOpLog(SysOpLog log) {
        if (log.getStatus() == null) log.setStatus("SUCCESS");
        opLogMapper.insertOpLog(log);
        return log.getId();
    }

    /**
     * <p>查询登录日志 (按 username/action/success 过滤, 分页)</p>
     *
     * @param username 用户名 (可选)
     * @param action   动作 (LOGIN/LOGOUT, 可选)
     * @param success  是否成功 (1/0, 可选)
     * @param page     页码 (从 1 开始)
     * @param pageSize 每页条数
     * @return R.ok(Map.of("items"/"total"/"page"/"pageSize", ...))
     */
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

    /**
     * <p>写登录日志</p>
     *
     * @param log 登录日志实体
     * @return 写入的日志 ID
     */
    public Long writeLoginLog(SysLoginLog log) {
        loginLogMapper.insertLoginLog(log);
        return log.getId();
    }

    /**
     * <p>Dashboard 统计 (用户/角色/字典/日志计数 + Top 5 登录用户 + 7 天登录动作分布)</p>
     *
     * @return R.ok(Map) 含 userCount/userActiveCount/roleCount/dictCount/opLogCount/... 等
     */
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