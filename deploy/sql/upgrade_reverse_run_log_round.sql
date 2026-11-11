-- ==========================================================
-- PRCP 反算运行日志/轮次结构化字段升级 V1
-- 日期：2026-11-11
-- 用途：对齐 Python 版 V20261111_01_reverse_run_log_round.sql
--       1) prcp_reverse_run_log 扩展 stage / round_no / algo / round_result
--       2) 新建 prcp_reverse_run_round（每轮状态与结果）
-- 幂等：可重复执行
-- ==========================================================

-- ---------- 1. 日志表扩展结构化字段 ----------
-- 阶段：SUBMIT/LOAD_TARGET/LOAD_BALANCE/SOLVE/SAVE_RESULT/DONE/FAILED
ALTER TABLE `prcp_reverse_run_log` ADD COLUMN `stage` VARCHAR(24) NULL
  COMMENT '阶段：SUBMIT/LOAD_TARGET/LOAD_BALANCE/SOLVE/SAVE_RESULT/DONE/FAILED';
-- 轮次序号，1 起；单轮算法恒为 1
ALTER TABLE `prcp_reverse_run_log` ADD COLUMN `round_no` INT NULL
  COMMENT '轮次序号，1 起；单轮算法恒为 1';
-- 算法/引擎标识：CVXPY_QP/CVXPY_LP/HEURISTIC/ACOR/DE/SLSQP/DL
ALTER TABLE `prcp_reverse_run_log` ADD COLUMN `algo` VARCHAR(24) NULL
  COMMENT '算法/引擎标识：CVXPY_QP/CVXPY_LP/HEURISTIC/ACOR/DE/SLSQP/DL';
-- 该轮关键结果快照：score/optimal_value/target_hit/metrics
ALTER TABLE `prcp_reverse_run_log` ADD COLUMN `round_result` JSON NULL
  COMMENT '该轮关键结果快照：score/optimal_value/target_hit/metrics';

-- 阶段过滤索引（配合 GET /reverse/runs/{id}/logs?stage=xxx）
ALTER TABLE `prcp_reverse_run_log` ADD INDEX `idx_run_stage` (`run_id`, `stage`, `id`);

-- ---------- 2. 轮次记录表 ----------
CREATE TABLE IF NOT EXISTS `prcp_reverse_run_round` (
  `id`             BIGINT        NOT NULL AUTO_INCREMENT,
  `run_id`         BIGINT        NOT NULL COMMENT '运行记录 id',
  `round_no`       INT           NOT NULL COMMENT '轮次序号，1 起',
  `algo`           VARCHAR(24)   NOT NULL COMMENT '算法/引擎标识',
  `stage`          VARCHAR(24)   NULL     COMMENT '该轮所处阶段',
  `status`         VARCHAR(16)   NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/SUCCESS/FAILED',
  `progress`       DECIMAL(5,2)  NULL     DEFAULT 0.00 COMMENT '该轮进度 %',
  `score`          DECIMAL(10,2) NULL     COMMENT '该轮综合评分（0~500）',
  `optimal_value`  DECIMAL(20,6) NULL     COMMENT '该轮最优目标函数值',
  `target_hit`     TINYINT(1)    NULL     COMMENT '是否全部目标达成：1 是 0 否',
  `message`        VARCHAR(500)  NULL     COMMENT '该轮结论摘要',
  `metrics`        JSON          NULL     COMMENT '该轮指标实测值快照',
  `started_at`     DATETIME      NULL,
  `ended_at`       DATETIME      NULL,
  `duration_sec`   INT           NULL,
  `total_rounds`   INT           NULL     COMMENT '引擎标称总轮次',
  `eta_total_sec`  INT           NULL     COMMENT '预计总耗时（秒），按已完成轮次均速推算',
  `score_details`  JSON          NULL     COMMENT '逐指标评分明细：{REG_LCR:{target,actual,ratio,score,segment_desc}}',
  `is_deleted`     TINYINT(1)    NOT NULL DEFAULT 0,
  `created_at`     DATETIME      NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     DATETIME      NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_run_round_algo` (`run_id`, `round_no`, `algo`),
  KEY `idx_run` (`run_id`),
  KEY `idx_run_status` (`run_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='组合反算运行轮次记录（每轮状态与结果）';

-- ---------- 3. 运行编码格式说明（数据订正，非结构变更） ----------
-- Java 版原用 RR{millis}（如 RR1790429261131），已对齐 Python 版：
--   R{yyyyMMdd}_{6位大写HEX}（如 R20261111_0794AE）
-- 存量 RR 格式记录保留原值（历史可追溯），仅新建 Run 使用新格式。
