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
 * 系统管理 Controller
 * - /admin/*：用户/角色
 * - /dict/*：字典（含 sys_dict + sys_dict_item）
 */
@RestController
@RequiredArgsConstructor
public class SysController {

    private final SysService sysService;
    private final AuditLogService auditLogService;

    // ====== 用户 ======
    @GetMapping("/admin/users")
    public R<List<SysUser>> listUsers(@RequestParam(required = false) String keyword) {
        return R.ok(sysService.listUsers(keyword));
    }

    @PostMapping("/admin/users")
    public R<SysUser> createUser(@RequestBody Map<String, Object> body) {
        String username = (String) body.get("username");
        String password = (String) body.get("password");
        String displayName = (String) body.get("display_name");
        String role = (String) body.get("role");
        return R.ok(sysService.createUser(username, password, displayName, role));
    }

    @PutMapping("/admin/users/{uid}")
    public R<SysUser> updateUser(@PathVariable Long uid, @RequestBody Map<String, Object> body) {
        String displayName = (String) body.get("display_name");
        String role = (String) body.get("role");
        Integer status = body.get("status") == null ? null : ((Number) body.get("status")).intValue();
        return R.ok(sysService.updateUser(uid, displayName, role, status));
    }

    @DeleteMapping("/admin/users/{uid}")
    public R<Void> deleteUser(@PathVariable Long uid) {
        sysService.deleteUser(uid);
        return R.ok();
    }

    @PostMapping("/admin/users/{uid}/reset-password")
    public R<Void> resetPassword(@PathVariable Long uid, @RequestBody Map<String, Object> body) {
        sysService.resetPassword(uid, (String) body.get("password"));
        return R.ok();
    }

    // ====== 角色 ======
    @GetMapping("/admin/roles")
    public R<List<SysRole>> listRoles() {
        return R.ok(sysService.listRoles());
    }

    @PostMapping("/admin/roles")
    public R<SysRole> createRole(@RequestBody Map<String, Object> body) {
        return R.ok(sysService.createRole(
                (String) body.get("role_code"),
                (String) body.get("role_name"),
                (String) body.get("description")));
    }

    @PutMapping("/admin/roles/{rid}")
    public R<SysRole> updateRole(@PathVariable Long rid, @RequestBody Map<String, Object> body) {
        return R.ok(sysService.updateRole(rid,
                (String) body.get("role_name"),
                (String) body.get("description"),
                body.get("status") == null ? null : ((Number) body.get("status")).intValue()));
    }

    @DeleteMapping("/admin/roles/{rid}")
    public R<Void> deleteRole(@PathVariable Long rid) {
        sysService.deleteRole(rid);
        return R.ok();
    }

    // ====== 字典（sys_dict 类型维度）======
    @GetMapping("/dict/types")
    public R<List<String>> listDictTypes() {
        List<SysDict> all = sysService.listDicts(null);
        return R.ok(all.stream().map(SysDict::getDictType).distinct().sorted().collect(java.util.stream.Collectors.toList()));
    }

    /** GET /dict/types/summary — 字典类别汇总 {dictType, count} 列表（按字母序） */
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

    @GetMapping("/dict/types/items")
    public R<List<SysDict>> listAllDictItems() {
        return R.ok(sysService.listDicts(null));
    }

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

    @GetMapping("/dict/all")
    public R<List<SysDict>> listDicts(@RequestParam(required = false) String keyword) {
        return R.ok(sysService.listDicts(keyword));
    }

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

    @PutMapping("/dict/{did}")
    public R<SysDict> updateDict(@PathVariable Long did, @RequestBody Map<String, Object> body) {
        Integer sortOrder = body.get("sort_order") == null ? null : ((Number) body.get("sort_order")).intValue();
        return R.ok(sysService.updateDict(did,
                (String) body.get("dict_label"),
                (String) body.get("color"),
                sortOrder,
                (String) body.get("status")));
    }

    @DeleteMapping("/dict/{did}")
    public R<Void> deleteDict(@PathVariable Long did) {
        sysService.deleteDict(did);
        return R.ok();
    }

    // ====== 字典项 sys_dict_item ======
    @GetMapping("/dict/{did}/items")
    public R<List<SysDictItem>> listDictItems(@PathVariable Long did) {
        return R.ok(sysService.listDictItems(did));
    }

    @PostMapping("/dict/{did}/items")
    public R<SysDictItem> createDictItem(@PathVariable Long did, @RequestBody Map<String, Object> body) {
        Integer sortOrder = body.get("sort_order") == null ? null : ((Number) body.get("sort_order")).intValue();
        return R.ok(sysService.createDictItem(did,
                (String) body.get("item_code"),
                (String) body.get("item_name"),
                (String) body.get("item_value"),
                sortOrder));
    }

    @DeleteMapping("/dict/items/{itemId}")
    public R<Void> deleteDictItem(@PathVariable Long itemId) {
        sysService.deleteDictItem(itemId);
        return R.ok();
    }

    /** 修复 Python 端缺失的 PUT 端点（之前只有 POST/DELETE） */
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
    @GetMapping("/admin/audit-logs")
    public R<Map<String, Object>> listOpLogs(@RequestParam(required = false) String module,
                                              @RequestParam(required = false) String action,
                                              @RequestParam(required = false) String username,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "20") int pageSize) {
        return auditLogService.listOpLogs(module, action, username, status, page, Math.min(pageSize, 200));
    }

    /** 内部业务调用：记录操作日志 */
    @PostMapping("/admin/audit-logs")
    public R<Map<String, Object>> writeOpLog(@RequestBody SysOpLog log) {
        Long id = auditLogService.writeOpLog(log);
        return R.ok(Map.of("id", id, "ok", true));
    }

    // ====== 登录日志 sys_login_log ======
    @GetMapping("/admin/login-logs")
    public R<Map<String, Object>> listLoginLogs(@RequestParam(required = false) String username,
                                                 @RequestParam(required = false) String action,
                                                 @RequestParam(required = false) Integer success,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int pageSize) {
        return auditLogService.listLoginLogs(username, action, success, page, Math.min(pageSize, 200));
    }

    /** 内部业务调用：记录登录日志 */
    @PostMapping("/admin/login-logs")
    public R<Map<String, Object>> writeLoginLog(@RequestBody SysLoginLog log) {
        Long id = auditLogService.writeLoginLog(log);
        return R.ok(Map.of("id", id, "ok", true));
    }

    // ====== Dashboard 统计 ======
    @GetMapping("/admin/stats")
    public R<Map<String, Object>> stats() {
        return auditLogService.stats();
    }
}
