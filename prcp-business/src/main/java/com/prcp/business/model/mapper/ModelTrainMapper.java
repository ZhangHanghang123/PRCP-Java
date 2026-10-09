package com.prcp.business.model.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.prcp.business.model.entity.ModelTrain;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * <p>Mapper: prcp_model_train 表的 SQL 访问层 (模型训练记录)</p>
 *
 * <p>主要 SQL 操作:
 * <ul>
 *   <li>listTrains - 训练记录列表 (JOIN model + version 取名称)</li>
 * </ul>
 * </p>
 *
 * @author zhanghh
 * @since 2026-10-09
 */
@Mapper
public interface ModelTrainMapper extends BaseMapper<ModelTrain> {

    /**
     * <p>训练记录列表 (JOIN prcp_model 取 modelCode/modelName, JOIN prcp_model_version 取 versionCode/versionName)</p>
     *
     * @return 训练记录 Map 列表, 含 progress/status/durationSec/metrics, 按 ID DESC
     */
    @Select({
        "SELECT t.id, t.train_code AS trainCode, t.model_id AS modelId, t.version_id AS versionId,",
        "       t.coa_scheme_id AS coaSchemeId,",
        "       t.balance_date_from AS balanceDateFrom, t.balance_date_to AS balanceDateTo,",
        "       t.status, t.progress, t.start_at AS startAt, t.end_at AS endAt,",
        "       t.duration_sec AS durationSec, t.metrics, t.error_message AS errorMessage,",
        "       t.description, t.created_at AS createdAt, t.updated_at AS updatedAt,",
        "       m.model_code AS modelCode, m.model_name AS modelName,",
        "       v.version_code AS versionCode, v.version_name AS versionName",
        "  FROM prcp_model_train t",
        "  JOIN prcp_model m ON m.id=t.model_id",
        "  JOIN prcp_model_version v ON v.id=t.version_id",
        " WHERE t.is_deleted=0",
        " ORDER BY t.id DESC"
    })
    List<Map<String, Object>> listTrains();
}