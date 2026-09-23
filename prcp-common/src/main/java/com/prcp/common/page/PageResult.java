package com.prcp.common.page;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * 分页结果
 * <p>不依赖 MyBatis-Plus，可在 common 模块使用</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    private List<T> items;
    private Long total;
    private Integer pageNum;
    private Integer pageSize;

    /**
     * 业务模块需要从 MyBatis-Plus IPage 转换时，调用本方法的同名重载。
     * 这里只保留简单构造，避免 common 模块依赖 MP。
     */
    public static <T> PageResult<T> of(List<T> items, long total, int pageNum, int pageSize) {
        return new PageResult<>(items, total, pageNum, pageSize);
    }

    public static <T> PageResult<T> empty() {
        return new PageResult<>(Collections.emptyList(), 0L, 1, 20);
    }
}
