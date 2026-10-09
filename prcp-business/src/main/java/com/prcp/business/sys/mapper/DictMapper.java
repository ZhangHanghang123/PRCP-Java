package com.prcp.business.sys.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.sys.entity.SysDict;
import com.prcp.business.sys.entity.SysDictItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>Mapper: sys_dict 表的 SQL 访问层</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>继承 BaseMapper 提供标准 CRUD</li>
 * </ul>
 * </p>
 *
 * <p>说明: 字典主表 (dict_type 唯一), 子表见 {@link DictItemMapper}。</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface DictMapper extends BaseMapper<SysDict> {}