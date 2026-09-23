package com.prcp.business.report.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.prcp.business.report.entity.RptItem;
import com.prcp.business.report.entity.RptReport;
import com.prcp.business.report.mapper.RptItemMapper;
import com.prcp.business.report.mapper.RptMapper;
import com.prcp.common.exception.BizException;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RptService extends ServiceImpl<RptMapper, RptReport> {

    private final RptMapper rptMapper;
    private final RptItemMapper rptItemMapper;

    public R<List<Map<String, Object>>> listReports(String reportType, Long schemeId, String keyword) {
        return R.ok(rptMapper.listReports(reportType, schemeId, keyword));
    }

    public R<?> create(RptReport r) {
        if (r.getReportCode() == null || r.getReportCode().isEmpty())
            throw BizException.badRequest("report_code 不能为空");
        if (r.getReportName() == null || r.getReportName().isEmpty())
            throw BizException.badRequest("report_name 不能为空");
        r.setItemCount(0);
        r.setIsDeleted(0);
        r.setStatus(r.getStatus() == null ? "ACTIVE" : r.getStatus());
        boolean ok = save(r);
        return ok ? R.ok(r) : R.fail("创建失败");
    }

    public R<?> update(Long id, RptReport r) {
        if (rptMapper.selectById(id) == null) throw BizException.notFound("报表不存在");
        r.setId(id);
        boolean ok = updateById(r);
        return ok ? R.ok() : R.fail("更新失败");
    }

    public R<?> softDelete(Long id) {
        if (rptMapper.selectById(id) == null) throw BizException.notFound("报表不存在");
        RptReport upd = new RptReport();
        upd.setId(id);
        upd.setIsDeleted(1);
        upd.setUpdatedAt(LocalDateTime.now());
        boolean ok = updateById(upd);
        return ok ? R.ok() : R.fail("删除失败");
    }

    // ============== 表项 ==============

    public R<List<Map<String, Object>>> listItems(Long reportId) {
        if (reportId == null) throw BizException.badRequest("report_id 不能为空");
        return R.ok(rptMapper.treeItems(reportId));
    }

    public R<?> createItem(RptItem item) {
        if (item.getReportId() == null) throw BizException.badRequest("report_id 不能为空");
        if (item.getItemCode() == null || item.getItemCode().isEmpty())
            throw BizException.badRequest("item_code 不能为空");
        if (item.getItemName() == null || item.getItemName().isEmpty())
            throw BizException.badRequest("item_name 不能为空");
        if (item.getParentId() == null || item.getParentId() == 0) {
            item.setItemLevel(1);
            item.setPath("/");
        } else {
            RptItem parent = rptItemMapper.selectById(item.getParentId());
            if (parent == null) throw BizException.badRequest("父表项不存在");
            item.setItemLevel((parent.getItemLevel() == null ? 1 : parent.getItemLevel()) + 1);
            String base = parent.getPath() == null ? "/" : parent.getPath();
            item.setPath(base + item.getItemCode() + "/");
        }
        item.setIsDeleted(0);
        item.setStatus(item.getStatus() == null ? "ACTIVE" : item.getStatus());
        boolean ok = rptItemMapper.insert(item) > 0;
        if (ok) recountItemCount(item.getReportId());
        return ok ? R.ok(item) : R.fail("创建失败");
    }

    public R<?> updateItem(Long id, RptItem item) {
        if (rptItemMapper.selectById(id) == null) throw BizException.notFound("表项不存在");
        item.setId(id);
        item.setItemLevel(null);
        item.setPath(null);
        item.setParentId(null);
        boolean ok = rptItemMapper.updateById(item) > 0;
        return ok ? R.ok() : R.fail("更新失败");
    }

    public R<?> deleteItem(Long id) {
        RptItem existing = rptItemMapper.selectById(id);
        if (existing == null) throw BizException.notFound("表项不存在");
        Long cnt = rptItemMapper.selectCount(
            new LambdaQueryWrapper<RptItem>().eq(RptItem::getParentId, id).eq(RptItem::getIsDeleted, 0));
        if (cnt != null && cnt > 0) throw BizException.badRequest("该表项下存在子项，不能删除");
        RptItem upd = new RptItem();
        upd.setId(id);
        upd.setIsDeleted(1);
        upd.setUpdatedAt(LocalDateTime.now());
        boolean ok = rptItemMapper.updateById(upd) > 0;
        if (ok) recountItemCount(existing.getReportId());
        return ok ? R.ok() : R.fail("删除失败");
    }

    /** 同步报表的 item_count */
    private void recountItemCount(Long reportId) {
        Long cnt = rptItemMapper.selectCount(
            new LambdaQueryWrapper<RptItem>().eq(RptItem::getReportId, reportId).eq(RptItem::getIsDeleted, 0));
        RptReport upd = new RptReport();
        upd.setId(reportId);
        upd.setItemCount(cnt == null ? 0 : cnt.intValue());
        upd.setUpdatedAt(LocalDateTime.now());
        updateById(upd);
    }
}