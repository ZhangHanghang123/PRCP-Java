package com.prcp.business.sys.controller;

import com.prcp.business.auth.entity.SysUser;
import com.prcp.business.sys.entity.SysRole;
import com.prcp.business.sys.entity.SysDict;
import com.prcp.business.sys.entity.SysDictItem;
import com.prcp.business.sys.entity.SysOpLog;
import com.prcp.business.sys.entity.SysLoginLog;
import com.prcp.business.sys.service.SysService;
import com.prcp.business.sys.service.AuditLogService;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * <p>系统管理 Controller (用户/角色/字典/审计日志)</p>
 *
 * <p>详细说明:
 * <ul>
 *   <li>业务背景: 系统级管理 - 用户/角色 (RBAC)、字典 (sys_dict + sys_dict_item)、操作/登录审计日志、Dashboard 统计</li>
 *   <li>核心端点:
 *     <ul>
 *       <li>用户: GET/POST/PUT/DELETE /admin/users + POST /admin/users/{uid}/reset-password</li>
 *       <li>角色: GET/POST/PUT/DELETE /admin/roles</li>
 *       <li>字典类型: GET /dict/types、/dict/types/summary、/dict/types/items</li>
 *       <li>字典: GET/POST/PUT/DELETE /dict/{type} 和 /dict/{did}</li>
 *       <li>字典项: GET/POST/PUT/DELETE /dict/{did}/items 和 /dict/items/{itemId}</li>
 *       <li>审计: GET/POST /admin/audit-logs (操作日志)</li>
 *       <li>登录日志: GET/POST /admin/login-logs</li>
 *       <li>统计: GET /admin/stats</li>
 *     </ul>
 *   </li>
 *   <li>关联模块: SysService、AuditLogService</li>
 * </ul>
 * </p>
 *
 * <p>REST 前缀: 无统一前缀, 子路径前缀 {@code /admin} 和 {@code /dict}</p>
 * <p>权限要求: 登录用户 (Bearer Token), 部分端点要求 ADMIN 角色</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 * @see com.prcp.business.sys.service.SysService
 * @see com.prcp.business.sys.service.AuditLogService
 */
@RestController
@RequiredArgsConstructor
public class SysController {

    private final SysService sysService;
    private final AuditLogService auditLogService;

    // ====== 用户 ======
    /**
     * <p>用户列表 (按关键字过滤)</p>
     *
     * <pre>
     * GET /admin/users
     * Query: keyword (String, optional) - 模糊搜索 (username/display_name)
     *
     * Response: R.ok(List&lt;SysUser&gt;)
     * </pre>
     *
     * @param keyword 模糊搜索关键字 (可选)
     * @return R.ok(用户列表)
     */
    @GetMapping("/admin/users")
    public R<List<SysUser>> listUsers(@RequestParam(required = false) String keyword) {
        return R.ok(sysService.listUsers(keyword));
    }

    /**
     * <p>新建用户</p>
     *
     * <pre>
     * POST /admin/users
     * Body: {username, password, display_name, role}
     *
     * Response: R.ok(SysUser) 新建用户
     * </pre>
     *
     * @param body 用户数据
     * @return R.ok(新建用户)
     */
    @PostMapping("/admin/users")
    public R<SysUser> createUser(@RequestBody Map<String, Object> body) {
        String username = (String) body.get("username");
        String password = (String) body.get("password");
        String displayName = (String) body.get("display_name");
        String role = (String) body.get("role");
        return R.ok(sysService.createUser(username, password, displayName, role));
    }

    /**
     * <p>更新用户</p>
     *
     * <pre>
     * PUT /admin/users/{uid}
     * Path: uid (Long, required) - 用户 ID
     * Body: {display_name, role, status}
     *
     * Response: R.ok(SysUser) 更新后用户
     * </pre>
     *
     * @param uid  用户 ID
     * @param body 更新内容
     * @return R.ok(更新后用户)
     */
    @PutMapping("/admin/users/{uid}")
    public R<SysUser> updateUser(@PathVariable Long uid, @RequestBody Map<String, Object> body) {
        String displayName = (String) body.get("display_name");
        String role = (String) body.get("role");
        Integer status = body.get("status") == null ? null : ((Number) body.get("status")).intValue();
        return R.ok(sysService.updateUser(uid, displayName, role, status));
    }

    /**
     * <p>软删用户</p>
     *
     * <pre>
     * DELETE /admin/users/{uid}
     * Path: uid (Long, required) - 用户 ID
     *
     * Response: R.ok()
     * </pre>
     *
     * @param uid 用户 ID
     * @return R.ok()
     */
    @DeleteMapping("/admin/users/{uid}")
    public R<Void> deleteUser(@PathVariable Long uid) {
        sysService.deleteUser(uid);
        return R.ok();
    }

    /**
     * <p>重置用户密码</p>
     *
     * <pre>
     * POST /admin/users/{uid}/reset-password
     * Path: uid (Long, required) - 用户 ID
     * Body: {password}
     *
     * Response: R.ok()
     * </pre>
     *
     * @param uid  用户 ID
     * @param body 新密码
     * @return R.ok()
     */
    @PostMapping("/admin/users/{uid}/reset-password")
    public R<Void> resetPassword(@PathVariable Long uid, @RequestBody Map<String, Object> body) {
        sysService.resetPassword(uid, (String) body.get("password"));
        return R.ok();
    }

    // ====== 角色 ======
    /**
     * <p>角色列表</p>
     *
     * <pre>
     * GET /admin/roles
     * Response: R.ok(List&lt;SysRole&gt;)
     * </pre>
     *
     * @return R.ok(角色列表)
     */
    @GetMapping("/admin/roles")
    public R<List<SysRole>> listRoles() {
        return R.ok(sysService.listRoles());
    }

    /**
     * <p>新建角色</p>
     *
     * <pre>
     * POST /admin/roles
     * Body: {role_code, role_name, description}
     *
     * Response: R.ok(SysRole) 新建角色
     * </pre>
     *
     * @param body 角色数据
     * @return R.ok(新建角色)
     */
    @PostMapping("/admin/roles")
    public R<SysRole> createRole(@RequestBody Map<String, Object> body) {
        return R.ok(sysService.createRole(
                (String) body.get("role_code"),
                (String) body.get("role_name"),
                (String) body.get("description")));
    }

    /**
     * <p>更新角色</p>
     *
     * <pre>
     * PUT /admin/roles/{rid}
     * Path: rid (Long, required) - 角色 ID
     * Body: {role_name, description, status}
     *
     * Response: R.ok(SysRole) 更新后角色
     * </pre>
     *
     * @param rid  角色 ID
     * @param body 更新内容
     * @return R.ok(更新后角色)
     */
    @PutMapping("/admin/roles/{rid}")
    public R<SysRole> updateRole(@PathVariable Long rid, @RequestBody Map<String, Object> body) {
        return R.ok(sysService.updateRole(rid,
                (String) body.get("role_name"),
                (String) body.get("description"),
                body.get("status") == null ? null : ((Number) body.get("status")).intValue()));
    }

    /**
     * <p>软删角色</p>
     *
     * <pre>
     * DELETE /admin/roles/{rid}
     * Path: rid (Long, required) - 角色 ID
     *
     * Response: R.ok()
     * </pre>
     *
     * @param rid 角色 ID
     * @return R.ok()
     */
    @DeleteMapping("/admin/roles/{rid}")
    public R<Void> deleteRole(@PathVariable Long rid) {
        sysService.deleteRole(rid);
        return R.ok();
    }

    // ====== 字典（sys_dict 类型维度）======
    /**
     * <p>字典类型列表 (去重, 按字母序)</p>
     *
     * <pre>
     * GET /dict/types
     * Response: R.ok(List&lt;String&gt;) 字典类型名列表
     * </pre>
     *
     * @return R.ok(字典类型列表)
     */
    @GetMapping("/dict/types")
    public R<List<String>> listDictTypes() {
        List<SysDict> all = sysService.listDicts(null);
        return R.ok(all.stream().map(SysDict::getDictType).distinct().sorted().collect(java.util.stream.Collectors.toList()));
    }

    /**
     * <p>字典类别汇总 {dictType, count} 列表 (按字母序)</p>
     *
     * <pre>
     * GET /dict/types/summary
     * Response: R.ok(List&lt;Map&gt;) [{dictType, count}, ...]
     * </pre>
     *
     * @return R.ok(类别汇总)
     */
    @GetMapping("/dict/types/summary")
    public R<List<java.util.Map<String, Object>>> listDictTypeSummary() {
        List<SysDict> all = sysService.listDicts(null);
        java.util.Map<String, Long> cnt = all.stream()
                .collect(java.util.stream.Collectors.groupingBy(SysDict::getDictType, java.util.stream.Collectors.counting()));
        List<java.util.Map<String, Object>> result = cnt.entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(e -> {
                    java.util.Map<String, Object> m = new java.util.HashMap<>();
                    m.put("dictType", e.getKey());
                    m.put("count", e.getValue());
                    return m;
                })
                .collect(java.util.stream.Collectors.toList());
        return R.ok(result);
    }

    /**
     * <p>所有字典项 (含全部类型)</p>
     *
     * <pre>
     * GET /dict/types/items
     * Response: R.ok(List&lt;SysDict&gt;)
     * </pre>
     *
     * @return R.ok(全部字典项)
     */
    @GetMapping("/dict/types/items")
    public R<List<SysDict>> listAllDictItems() {
        return R.ok(sysService.listDicts(null));
    }

    /**
     * <p>按类型查字典项 (支持关键字过滤 + 是否含停用)</p>
     *
     * <pre>
     * GET /dict/{dictType}
     * Path: dictType (String, required) - 字典类型
     * Query: keyword          (String, optional) - 模糊搜索 (dict_key/dict_label)
     *        includeInactive  (boolean, optional, default false) - 是否含停用项
     *
     * Response: R.ok(List&lt;SysDict&gt;)
     * </pre>
     *
     * @param dictType       字典类型
     * @param keyword        模糊搜索关键字 (可选)
     * @param includeInactive 是否含停用项 (默认 false)
     * @return R.ok(字典项列表)
     */
    @GetMapping("/dict/{dictType}")
    public R<List<SysDict>> listByType(@PathVariable String dictType,
                                       @RequestParam(required = false, defaultValue = "") String keyword,
                                       @RequestParam(required = false, defaultValue = "false") boolean includeInactive) {
        List<SysDict> list = sysService.listByType(dictType);
        if (!keyword.isEmpty()) {
            list = list.stream().filter(d -> d.getDictKey().contains(keyword) || d.getDictLabel().contains(keyword))
                    .collect(java.util.stream.Collectors.toList());
        }
        return R.ok(list);
    }

    /**
     * <p>字典列表 (按关键字过滤)</p>
     *
     * <pre>
     * GET /dict/all
     * Query: keyword (String, optional) - 模糊搜索
     *
     * Response: R.ok(List&lt;SysDict&gt;)
     * </pre>
     *
     * @param keyword 模糊搜索关键字 (可选)
     * @return R.ok(字典列表)
     */
    @GetMapping("/dict/all")
    public R<List<SysDict>> listDicts(@RequestParam(required = false) String keyword) {
        return R.ok(sysService.listDicts(keyword));
    }

    /**
     * <p>新建字典</p>
     *
     * <pre>
     * POST /dict
     * Body: {dict_type, dict_key, dict_label, color, sort_order}
     *
     * Response: R.ok(SysDict) 新建字典
     * </pre>
     *
     * @param body 字典数据
     * @return R.ok(新建字典)
     */
    @PostMapping("/dict")
    public R<SysDict> createDict(@RequestBody Map<String, Object> body) {
        Integer sortOrder = body.get("sort_order") == null ? null : ((Number) body.get("sort_order")).intValue();
        return R.ok(sysService.createDict(
                (String) body.get("dict_type"),
                (String) body.get("dict_key"),
                (String) body.get("dict_label"),
                (String) body.get("color"),
                sortOrder));
    }

    /**
     * <p>更新字典</p>
     *
     * <pre>
     * PUT /dict/{did}
     * Path: did (Long, required) - 字典 ID
     * Body: {dict_label, color, sort_order, status}
     *
     * Response: R.ok(SysDict) 更新后字典
     * </pre>
     *
     * @param did  字典 ID
     * @param body 更新内容
     * @return R.ok(更新后字典)
     */
    @PutMapping("/dict/{did}")
    public R<SysDict> updateDict(@PathVariable Long did, @RequestBody Map<String, Object> body) {
        Integer sortOrder = body.get("sort_order") == null ? null : ((Number) body.get("sort_order")).intValue();
        return R.ok(sysService.updateDict(did,
                (String) body.get("dict_label"),
                (String) body.get("color"),
                sortOrder,
                (String) body.get("status")));
    }

    /**
     * <p>软删字典</p>
     *
     * <pre>
     * DELETE /dict/{did}
     * Path: did (Long, required) - 字典 ID
     *
     * Response: R.ok()
     * </pre>
     *
     * @param did 字典 ID
     * @return R.ok()
     */
    @DeleteMapping("/dict/{did}")
    public R<Void> deleteDict(@PathVariable Long did) {
        sysService.deleteDict(did);
        return R.ok();
    }

    // ====== 字典项 sys_dict_item ======
    /**
     * <p>字典项列表 (按字典 ID)</p>
     *
     * <pre>
     * GET /dict/{did}/items
     * Path: did (Long, required) - 字典 ID
     *
     * Response: R.ok(List&lt;SysDictItem&gt;)
     * </pre>
     *
     * @param did 字典 ID
     * @return R.ok(字典项列表)
     */
    @GetMapping("/dict/{did}/items")
    public R<List<SysDictItem>> listDictItems(@PathVariable Long did) {
        return R.ok(sysService.listDictItems(did));
    }

    /**
     * <p>新增字典项</p>
     *
     * <pre>
     * POST /dict/{did}/items
     * Path: did (Long, required) - 字典 ID
     * Body: {item_code, item_name, item_value, sort_order}
     *
     * Response: R.ok(SysDictItem) 新建字典项
     * </pre>
     *
     * @param did  字典 ID
     * @param body 字典项数据
     * @return R.ok(新建字典项)
     */
    @PostMapping("/dict/{did}/items")
    public R<SysDictItem> createDictItem(@PathVariable Long did, @RequestBody Map<String, Object> body) {
        Integer sortOrder = body.get("sort_order") == null ? null : ((Number) body.get("sort_order")).intValue();
        return R.ok(sysService.createDictItem(did,
                (String) body.get("item_code"),
                (String) body.get("item_name"),
                (String) body.get("item_value"),
                sortOrder));
    }

    /**
     * <p>软删字典项</p>
     *
     * <pre>
     * DELETE /dict/items/{itemId}
     * Path: itemId (Long, required) - 字典项 ID
     *
     * Response: R.ok()
     * </pre>
     *
     * @param itemId 字典项 ID
     * @return R.ok()
     */
    @DeleteMapping("/dict/items/{itemId}")
    public R<Void> deleteDictItem(@PathVariable Long itemId) {
        sysService.deleteDictItem(itemId);
        return R.ok();
    }

    /**
     * <p>更新字典项 (修复 Python 端缺失的 PUT 端点)</p>
     *
     * <pre>
     * PUT /dict/items/{itemId}
     * Path: itemId (Long, required) - 字典项 ID
     * Body: {item_name, item_value, sort_order, status}
     *
     * Response: R.ok(SysDictItem) 更新后字典项
     * </pre>
     *
     * @param itemId 字典项 ID
     * @param body   更新内容
     * @return R.ok(更新后字典项)
     */
    @PutMapping("/dict/items/{itemId}")
    public R<SysDictItem> updateDictItem(@PathVariable Long itemId, @RequestBody Map<String, Object> body) {
        Integer sortOrder = body.get("sort_order") == null ? null : ((Number) body.get("sort_order")).intValue();
        Integer status = body.get("status") == null ? null : ((Number) body.get("status")).intValue();
        return R.ok(sysService.updateDictItem(itemId,
                (String) body.get("item_name"),
                (String) body.get("item_value"),
                sortOrder,
                status));
    }

    // ====== 操作日志 sys_op_log ======
    /**
     * <p>操作日志列表 (分页 + 多条件过滤)</p>
     *
     * <pre>
     * GET /admin/audit-logs
     * Query: module   (String, optional) - 模块
     *        action   (String, optional) - 操作类型
     *        username (String, optional) - 用户名
     *        status   (String, optional) - 状态 (SUCCESS/FAIL)
     *        page     (int, default 1) - 页码
     *        pageSize (int, default 20, max 200) - 页大小
     *
     * Response: R.ok({items, total, page, pageSize})
     * </pre>
     *
     * @param module   模块 (可选)
     * @param action   操作类型 (可选)
     * @param username 用户名 (可选)
     * @param status   状态 (可选)
     * @param page     页码 (默认 1)
     * @param pageSize 页大小 (默认 20, 最大 200)
     * @return R.ok(操作日志列表)
     */
    @GetMapping("/admin/audit-logs")
    public R<Map<String, Object>> listOpLogs(@RequestParam(required = false) String module,
                                              @RequestParam(required = false) String action,
                                              @RequestParam(required = false) String username,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "20") int pageSize) {
        return auditLogService.listOpLogs(module, action, username, status, page, Math.min(pageSize, 200));
    }

    /**
     * <p>内部业务调用: 记录操作日志</p>
     *
     * <pre>
     * POST /admin/audit-logs
     * Body: SysOpLog (module, action, username, status, request_uri, ip, ...)
     *
     * Response: R.ok({id, ok: true})
     * </pre>
     *
     * @param log SysOpLog 实体
     * @return R.ok({id, ok: true})
     */
    @PostMapping("/admin/audit-logs")
    public R<Map<String, Object>> writeOpLog(@RequestBody SysOpLog log) {
        Long id = auditLogService.writeOpLog(log);
        return R.ok(Map.of("id", id, "ok", true));
    }

    // ====== 登录日志 sys_login_log ======
    /**
     * <p>登录日志列表 (分页 + 多条件过滤)</p>
     *
     * <pre>
     * GET /admin/login-logs
     * Query: username (String, optional) - 用户名
     *        action   (String, optional) - 操作 (LOGIN/LOGOUT)
     *        success  (Integer, optional) - 是否成功 (0/1)
     *        page     (int, default 1) - 页码
     *        pageSize (int, default 20, max 200) - 页大小
     *
     * Response: R.ok({items, total, page, pageSize})
     * </pre>
     *
     * @param username 用户名 (可选)
     * @param action   操作 (可选)
     * @param success  是否成功 (可选)
     * @param page     页码 (默认 1)
     * @param pageSize 页大小 (默认 20, 最大 200)
     * @return R.ok(登录日志列表)
     */
    @GetMapping("/admin/login-logs")
    public R<Map<String, Object>> listLoginLogs(@RequestParam(required = false) String username,
                                                 @RequestParam(required = false) String action,
                                                 @RequestParam(required = false) Integer success,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int pageSize) {
        return auditLogService.listLoginLogs(username, action, success, page, Math.min(pageSize, 200));
    }

    /**
     * <p>内部业务调用: 记录登录日志</p>
     *
     * <pre>
     * POST /admin/login-logs
     * Body: SysLoginLog (username, action, success, ip, user_agent, ...)
     *
     * Response: R.ok({id, ok: true})
     * </pre>
     *
     * @param log SysLoginLog 实体
     * @return R.ok({id, ok: true})
     */
    @PostMapping("/admin/login-logs")
    public R<Map<String, Object>> writeLoginLog(@RequestBody SysLoginLog log) {
        Long id = auditLogService.writeLoginLog(log);
        return R.ok(Map.of("id", id, "ok", true));
    }

    // ====== Dashboard 统计 ======
    /**
     * <p>Dashboard 统计 (总用户数/活跃用户/今日登录/今日操作 等)</p>
     *
     * <pre>
     * GET /admin/stats
     * Response: R.ok({user_total, active_user, login_today, op_today, ...})
     * </pre>
     *
     * @return R.ok(统计指标)
     */
    @GetMapping("/admin/stats")
    public R<Map<String, Object>> stats() {
        return auditLogService.stats();
    }
}
