package com.prcp.business.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.auth.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>Mapper: sys_user 表的 SQL 访问层</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>继承 BaseMapper 提供标准 CRUD (insertById/updateById/selectById/deleteById/selectList 等)</li>
 * </ul>
 * </p>
 *
 * <p>说明: 自定义查询请使用 MyBatis-Plus 的 Wrapper, 无需 XML。</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
