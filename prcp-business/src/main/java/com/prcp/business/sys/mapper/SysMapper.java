package com.prcp.business.sys.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.sys.entity.SysUser;
import com.prcp.business.sys.entity.SysRole;
import com.prcp.business.sys.entity.SysDict;
import com.prcp.business.sys.entity.SysDictItem;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SysMapper extends BaseMapper<SysUser> {}