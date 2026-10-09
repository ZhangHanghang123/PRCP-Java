package com.prcp.business.sys.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.sys.entity.SysDictItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>Mapper: sys_dict_item 表的 SQL 访问层</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>继承 BaseMapper 提供标准 CRUD</li>
 * </ul>
 * </p>
 *
 * <p>说明: 字典项子表 (dict_key/dict_label/sort_order), 由 Dict 1:N 关联。</p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface DictItemMapper extends BaseMapper<SysDictItem> {}