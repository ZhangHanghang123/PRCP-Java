package com.prcp.common.page;

import lombok.Data;

/**
 * 分页查询参数
 */
@Data
public class PageQuery {

    private Integer pageNum = 1;
    private Integer pageSize = 20;
    private String keyword;
    private String orderBy;

    public Integer getOffset() {
        return (Math.max(pageNum, 1) - 1) * getPageSize();
    }
}
