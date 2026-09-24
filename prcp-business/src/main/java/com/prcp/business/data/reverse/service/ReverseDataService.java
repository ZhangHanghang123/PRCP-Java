package com.prcp.business.data.reverse.service;

import com.prcp.business.data.reverse.BasicDataBuckets;
import com.prcp.business.data.reverse.mapper.ReverseDataMapper;
import com.prcp.common.exception.BizException;
import com.prcp.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ReverseDataService {

    private final ReverseDataMapper mapper;
    private static final List<String> BUCKET_KEYS = BasicDataBuckets.keys();

    public R<List<Map<String, Object>>> list(Long schemeId, String dataDate, String category, String nodeKw) {
        // schemeId 暂时忽略（reverse 表无 schemeId），前端可传 schemeCode 替代
        return R.ok(mapper.list(null, emptyToNull(dataDate), emptyToNull(category), emptyToNull(nodeKw)));
    }

    public R<List<String>> dates(Long schemeId) {
        return R.ok(mapper.dates(null));
    }

    public R<Map<String, Object>> matrix(Long schemeId, String dataDate) {
        List<Map<String, Object>> raw = mapper.matrix(null, emptyToNull(dataDate));
        String[] buckets = { "m1", "m3", "m6", "m12", "y10", "y15", "y20", "y30" };
        Map<String, Map<String, Object>> rowMap = new LinkedHashMap<>();
        Set<String> dateSet = new LinkedHashSet<>();
        for (Map<String, Object> r : raw) {
            String nodeKey = (String) r.get("nodeCode");
            if (nodeKey == null) continue;
            dateSet.add((String) r.get("dataDate"));
            Map<String, Object> row = rowMap.computeIfAbsent(nodeKey, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("nodeCode", nodeKey);
                m.put("nodeName", r.get("nodeName"));
                m.put("category", r.get("category"));
                m.put("nodeLevel", r.get("nodeLevel"));
                m.put("cells", new ArrayList<Map<String, Object>>());
                return m;
            });
            List<Map<String, Object>> cells = (List<Map<String, Object>>) row.get("cells");
            for (String b : buckets) {
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("date", r.get("dataDate"));
                cell.put("bucket", b);
                cell.put("orig", r.get("orig" + b.charAt(0) + b.substring(1)));
                cell.put("rem", r.get("rem" + b.charAt(0) + b.substring(1)));
                cells.add(cell);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("buckets", Arrays.asList(buckets));
        result.put("dates", new ArrayList<>(dateSet));
        result.put("rows", new ArrayList<>(rowMap.values()));
        result.put("totalRows", rowMap.size());
        return R.ok(result);
    }

    @Transactional
    public R<?> upsert(Map<String, Object> body) {
        Object nid = body.get("coaNodeId");
        Object dt = body.get("dataDate");
        Object sc = body.get("schemeCode");
        if (nid == null) throw BizException.badRequest("coaNodeId 必填");
        if (dt == null) throw BizException.badRequest("dataDate 必填");
        if (sc == null) throw BizException.badRequest("schemeCode 必填");

        Long nodeId = ((Number) nid).longValue();
        String dataDate = dt.toString();
        String schemeCode = sc.toString();

        Long id = mapper.findId(nodeId, dataDate, schemeCode);
        StringBuilder setOrValues = new StringBuilder();
        List<Object> args = new ArrayList<>();
        for (String k : BUCKET_KEYS) {
            String origKey = "orig_" + k;
            String remKey = "rem_" + k;
            if (body.containsKey(origKey)) {
                if (setOrValues.length() > 0) setOrValues.append(", ");
                setOrValues.append("orig_").append(k).append(" = ?");
                args.add(toBigDecimal(body.get(origKey)));
            }
            if (body.containsKey(remKey)) {
                if (setOrValues.length() > 0) setOrValues.append(", ");
                setOrValues.append("rem_").append(k).append(" = ?");
                args.add(toBigDecimal(body.get(remKey)));
            }
        }
        if (setOrValues.length() == 0) throw BizException.badRequest("至少传一个桶字段");

        if (id != null) {
            mapper.updateByDynamic(id, setOrValues.toString(), args);
        } else {
            mapper.insertByDynamic(
                schemeCode + "_" + nodeId + "_" + dataDate.replace("-", ""),
                schemeCode,
                0L,
                null,
                dataDate,
                0,
                "M",
                nodeId,
                null, null, null, null, null, null,
                setOrValues.toString(), args
            );
        }
        return R.ok(Map.of("id", id, "mode", id == null ? "insert" : "update"));
    }

    public R<?> delete(Long id) {
        mapper.deleteById(id);
        return R.ok();
    }

    private static BigDecimal toBigDecimal(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal) return (BigDecimal) o;
        if (o instanceof Number) return new BigDecimal(o.toString());
        try { return new BigDecimal(o.toString().trim()); } catch (Exception e) { return BigDecimal.ZERO; }
    }
    private static String emptyToNull(String s) { return (s == null || s.isEmpty()) ? null : s; }
}
