package com.prcp.business.sys.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.sys.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>Mapper: sys_role 表的 SQL 访问层</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>继承 BaseMapper 提供标准 CRUD</li>
 * </ul>
 * </p>
 *
 * <p>说明: 角色表, 与 sys_user 通过 user_role 关联表多对多。</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface RoleMapper extends BaseMapper<SysRole> {}