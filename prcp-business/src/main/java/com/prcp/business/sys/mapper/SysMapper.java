package com.prcp.business.sys.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.auth.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>Mapper: sys_user 表的 SQL 访问层 (别名/旧接口)</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>继承 BaseMapper 提供标准 CRUD</li>
 * </ul>
 * </p>
 *
 * <p>说明: 与 {@link com.prcp.business.auth.mapper.SysUserMapper} 等价, 保留作为历史别名。</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface SysMapper extends BaseMapper<SysUser> {}