package com.prcp.business.sys.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.prcp.business.sys.entity.SysRole;
import com.prcp.business.sys.entity.SysDict;
import com.prcp.business.sys.entity.SysDictItem;
import com.prcp.business.auth.entity.SysUser;
import com.prcp.business.sys.mapper.SysMapper;
import com.prcp.business.sys.mapper.RoleMapper;
import com.prcp.business.sys.mapper.DictMapper;
import com.prcp.business.sys.mapper.DictItemMapper;
import com.prcp.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.util.List;

/**
 * 系统管理服务（用户/角色/字典 CRUD）
 */
@Service
@RequiredArgsConstructor
public class SysService {

    private final SysMapper userMapper;
    private final RoleMapper roleMapper;
    private final DictMapper dictMapper;
    private final DictItemMapper dictItemMapper;

    // ============== 用户 ==============

    public List<SysUser> listUsers(String keyword) {
        QueryWrapper<SysUser> qw = new QueryWrapper<>();
        qw.eq("is_deleted", 0).orderByDesc("id");
        if (keyword != null && !keyword.isEmpty()) {
            qw.and(w -> w.like("username", keyword).or().like("display_name", keyword));
        }
        // 隐藏 passwordHash
        List<SysUser> list = userMapper.selectList(qw);
        list.forEach(u -> u.setPasswordHash(null));
        return list;
    }

    public SysUser createUser(String username, String password, String displayName, String role) {
        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            throw new BizException("用户名和密码不能为空");
        }
        Long exist = userMapper.selectCount(new QueryWrapper<SysUser>().eq("username", username));
        if (exist != null && exist > 0) {
            throw new BizException("用户名已存在");
        }
        SysUser u = new SysUser();
        u.setUsername(username);
        u.setPasswordHash(sha256(password));
        u.setDisplayName(displayName);
        u.setRole(role == null ? "user" : role);
        u.setStatus(1);
        u.setIsDeleted(0);
        u.setCreatedAt(java.time.LocalDateTime.now());
        u.setUpdatedAt(java.time.LocalDateTime.now());
        userMapper.insert(u);
        u.setPasswordHash(null);
        return u;
    }

    public SysUser updateUser(Long uid, String displayName, String role, Integer status) {
        SysUser u = userMapper.selectById(uid);
        if (u == null) throw new BizException("用户不存在");
        if (displayName != null) u.setDisplayName(displayName);
        if (role != null) u.setRole(role);
        if (status != null) u.setStatus(status);
        u.setUpdatedAt(java.time.LocalDateTime.now());
        userMapper.updateById(u);
        u.setPasswordHash(null);
        return u;
    }

    @Transactional
    public void deleteUser(Long uid) {
        SysUser u = userMapper.selectById(uid);
        if (u == null) throw new BizException("用户不存在");
        u.setIsDeleted(1);
        u.setUpdatedAt(java.time.LocalDateTime.now());
        userMapper.updateById(u);
    }

    public void resetPassword(Long uid, String newPassword) {
        if (newPassword == null || newPassword.isEmpty()) {
            throw new BizException("新密码不能为空");
        }
        SysUser u = userMapper.selectById(uid);
        if (u == null) throw new BizException("用户不存在");
        u.setPasswordHash(sha256(newPassword));
        u.setUpdatedAt(java.time.LocalDateTime.now());
        userMapper.updateById(u);
    }

    // ============== 角色 ==============

    public List<SysRole> listRoles() {
        return roleMapper.selectList(new QueryWrapper<SysRole>().eq("is_deleted", 0).orderByAsc("id"));
    }

    public SysRole createRole(String roleCode, String roleName, String description) {
        Long exist = roleMapper.selectCount(new QueryWrapper<SysRole>().eq("role_code", roleCode));
        if (exist != null && exist > 0) {
            throw new BizException("角色编码已存在");
        }
        SysRole r = new SysRole();
        r.setRoleCode(roleCode);
        r.setRoleName(roleName);
        r.setDescription(description);
        r.setStatus(1);
        r.setIsDeleted(0);
        r.setCreatedAt(java.time.LocalDateTime.now());
        r.setUpdatedAt(java.time.LocalDateTime.now());
        roleMapper.insert(r);
        return r;
    }

    public SysRole updateRole(Long rid, String roleName, String description, Integer status) {
        SysRole r = roleMapper.selectById(rid);
        if (r == null) throw new BizException("角色不存在");
        if (roleName != null) r.setRoleName(roleName);
        if (description != null) r.setDescription(description);
        if (status != null) r.setStatus(status);
        r.setUpdatedAt(java.time.LocalDateTime.now());
        roleMapper.updateById(r);
        return r;
    }

    public void deleteRole(Long rid) {
        SysRole r = roleMapper.selectById(rid);
        if (r == null) throw new BizException("角色不存在");
        r.setIsDeleted(1);
        r.setUpdatedAt(java.time.LocalDateTime.now());
        roleMapper.updateById(r);
    }

    // ============== 字典 ==============

    public List<SysDict> listDicts(String keyword) {
        QueryWrapper<SysDict> qw = new QueryWrapper<>();
        qw.eq("is_deleted", 0).orderByAsc("dict_type", "sort_order");
        if (keyword != null && !keyword.isEmpty()) {
            qw.and(w -> w.like("dict_type", keyword).or().like("dict_key", keyword).or().like("dict_label", keyword));
        }
        return dictMapper.selectList(qw);
    }

    public List<SysDict> listByType(String dictType) {
        return dictMapper.selectList(new QueryWrapper<SysDict>()
                .eq("dict_type", dictType)
                .eq("status", "ACTIVE")
                .eq("is_deleted", 0)
                .orderByAsc("sort_order"));
    }

    public SysDict createDict(String dictType, String dictKey, String dictLabel, String color, Integer sortOrder) {
        SysDict d = new SysDict();
        d.setDictType(dictType);
        d.setDictKey(dictKey);
        d.setDictLabel(dictLabel);
        d.setColor(color);
        d.setSortOrder(sortOrder == null ? 0 : sortOrder);
        d.setStatus("ACTIVE");
        d.setIsDeleted(0);
        d.setCreatedAt(java.time.LocalDateTime.now());
        d.setUpdatedAt(java.time.LocalDateTime.now());
        dictMapper.insert(d);
        return d;
    }

    public SysDict updateDict(Long did, String dictLabel, String color, Integer sortOrder, String status) {
        SysDict d = dictMapper.selectById(did);
        if (d == null) throw new BizException("字典不存在");
        if (dictLabel != null) d.setDictLabel(dictLabel);
        if (color != null) d.setColor(color);
        if (sortOrder != null) d.setSortOrder(sortOrder);
        if (status != null) d.setStatus(status);
        d.setUpdatedAt(java.time.LocalDateTime.now());
        dictMapper.updateById(d);
        return d;
    }

    public void deleteDict(Long did) {
        SysDict d = dictMapper.selectById(did);
        if (d == null) throw new BizException("字典不存在");
        d.setIsDeleted(1);
        d.setUpdatedAt(java.time.LocalDateTime.now());
        dictMapper.updateById(d);
    }

    public List<SysDictItem> listDictItems(Long dictId) {
        return dictItemMapper.selectList(new QueryWrapper<SysDictItem>()
                .eq("dict_id", dictId)
                .eq("is_deleted", 0)
                .orderByAsc("sort_order"));
    }

    public SysDictItem createDictItem(Long dictId, String itemCode, String itemName, String itemValue, Integer sortOrder) {
        SysDictItem item = new SysDictItem();
        item.setDictId(dictId);
        item.setItemCode(itemCode);
        item.setItemName(itemName);
        item.setItemValue(itemValue);
        item.setSortOrder(sortOrder == null ? 0 : sortOrder);
        item.setStatus(1);
        item.setIsDeleted(0);
        item.setCreatedAt(java.time.LocalDateTime.now());
        item.setUpdatedAt(java.time.LocalDateTime.now());
        dictItemMapper.insert(item);
        return item;
    }

    public void deleteDictItem(Long itemId) {
        SysDictItem item = dictItemMapper.selectById(itemId);
        if (item == null) throw new BizException("字典项不存在");
        item.setIsDeleted(1);
        item.setUpdatedAt(java.time.LocalDateTime.now());
        dictItemMapper.updateById(item);
    }

    // ============== 工具 ==============

    private String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new BizException("密码加密失败");
        }
    }
}
