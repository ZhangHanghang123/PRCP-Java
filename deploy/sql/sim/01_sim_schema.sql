-- ============================================================================
--  新业务模拟模块 — 表结构脚本 (DDL)
-- ============================================================================
--  适用数据库: MySQL 8.0+ / prcp_db
--  包含表 (7 张):
--    1. prcp_sim_scheme          新业务模拟方案
--    2. prcp_sim_node_config     节点参数配置 (是否模型测算 / 节奏类型)
--    3. prcp_sim_term_ratio      新业务期限占比 (期限 / 占比 / 新业务利率)
--    4. prcp_sim_rhythm          12 个月投放节奏权重
--    5. prcp_sim_growth_config   新业务增长配置 (增长方式 + 5 年参数)
--    6. prcp_sim_run             引擎运行记录
--    7. prcp_sim_result          引擎结果快照 (128 桶 + 7 追踪列)
--
--  ⚠️ 本脚本含 DROP TABLE, 执行会**清空**这 7 张表的数据
--     测试数据请执行 02_sim_test_data.sql
--  ⚠️ 外键: prcp_sim_scheme.coa_scheme_id → prcp_coa_scheme.id
--     (依赖账户册方案表, 需先建 prcp_coa_scheme)
-- ============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ---------------------------------------------------------------------------
-- 1. 删除旧表 (按外键依赖倒序: 有外键的先删)
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS `prcp_sim_result`;
DROP TABLE IF EXISTS `prcp_sim_run`;
DROP TABLE IF EXISTS `prcp_sim_rhythm`;
DROP TABLE IF EXISTS `prcp_sim_growth_config`;
DROP TABLE IF EXISTS `prcp_sim_term_ratio`;
DROP TABLE IF EXISTS `prcp_sim_node_config`;
DROP TABLE IF EXISTS `prcp_sim_scheme`;


-- ---------------------------------------------------------------------------
-- CREATE TABLE `prcp_sim_scheme`
-- ---------------------------------------------------------------------------
CREATE TABLE `prcp_sim_scheme` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `scheme_code` varchar(32) NOT NULL,
  `scheme_name` varchar(64) NOT NULL,
  `coa_scheme_id` bigint NOT NULL COMMENT 'FK → prcp_coa_scheme.id（创建后不可变更）',
  `description` text,
  `config_node_count` int DEFAULT '0' COMMENT '冗余：已配置节点数',
  `status` varchar(16) DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
  `is_deleted` tinyint(1) DEFAULT '0',
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `data_date` date NOT NULL COMMENT '模拟的基准数据日期（起始月）；引擎从此月开始按月滚动生成未来 N 月快照',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scheme_code` (`scheme_code`,`is_deleted`) COMMENT '软删除 + UNIQUE 复活',
  KEY `idx_coa_scheme` (`coa_scheme_id`),
  KEY `idx_status` (`status`),
  KEY `idx_data_date` (`data_date`),
  CONSTRAINT `fk_sim_scheme_coa` FOREIGN KEY (`coa_scheme_id`) REFERENCES `prcp_coa_scheme` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=9 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='新业务模拟方案';

-- ---------------------------------------------------------------------------
-- CREATE TABLE `prcp_sim_node_config`
-- ---------------------------------------------------------------------------
CREATE TABLE `prcp_sim_node_config` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `scheme_id` bigint NOT NULL COMMENT 'FK → prcp_sim_scheme.id',
  `coa_node_id` bigint NOT NULL COMMENT 'FK → prcp_coa_node.id（仅 L3+ 叶子）',
  `coa_node_code` varchar(32) DEFAULT NULL COMMENT '冗余：节点编码（兼容历史归档）',
  `annual_growth_rate` decimal(8,4) DEFAULT '0.0000' COMMENT '年化目标增长率（%）',
  `term_unit` varchar(8) DEFAULT 'MONTH' COMMENT '期限单位 ENUM（当前固定 MONTH）',
  `term_count` int DEFAULT '0' COMMENT '冗余：当前配置的期限条数',
  `remark` text,
  `is_deleted` tinyint(1) DEFAULT '0',
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_model_calc` tinyint(1) NOT NULL DEFAULT '2' COMMENT '是否模型测算 (1=是, 2=否)',
  `rhythm_type` tinyint(1) NOT NULL DEFAULT '1' COMMENT '节奏配置类型 (1=线性, 2=自定义)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scheme_node` (`scheme_id`,`coa_node_id`,`is_deleted`) COMMENT '软删除 + UNIQUE 复活',
  KEY `idx_scheme` (`scheme_id`),
  KEY `idx_node` (`coa_node_id`),
  CONSTRAINT `fk_sim_cfg_node` FOREIGN KEY (`coa_node_id`) REFERENCES `prcp_coa_node` (`id`),
  CONSTRAINT `fk_sim_cfg_scheme` FOREIGN KEY (`scheme_id`) REFERENCES `prcp_sim_scheme` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=76 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='新业务模拟节点配置';

-- ---------------------------------------------------------------------------
-- CREATE TABLE `prcp_sim_term_ratio`
-- ---------------------------------------------------------------------------
CREATE TABLE `prcp_sim_term_ratio` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `config_id` bigint NOT NULL COMMENT 'FK → prcp_sim_node_config.id',
  `term_value` int NOT NULL COMMENT '期限值（1~60 月）',
  `term_unit` varchar(8) DEFAULT 'MONTH' COMMENT '期限单位（与 config.term_unit 一致）',
  `business_ratio` decimal(8,4) NOT NULL COMMENT '业务占比（%）',
  `sort_order` int DEFAULT '0' COMMENT '显示顺序',
  `remark` varchar(255) DEFAULT NULL,
  `is_deleted` tinyint(1) DEFAULT '0',
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `interest_rate` decimal(8,4) NOT NULL DEFAULT '0.0000' COMMENT '新业务利率（%），0~100',
  PRIMARY KEY (`id`),
  KEY `idx_config` (`config_id`),
  KEY `idx_term` (`term_value`,`term_unit`),
  CONSTRAINT `fk_sim_ratio_cfg` FOREIGN KEY (`config_id`) REFERENCES `prcp_sim_node_config` (`id`),
  CONSTRAINT `chk_business_ratio` CHECK (((`business_ratio` >= 0) and (`business_ratio` <= 100))),
  CONSTRAINT `chk_interest_rate` CHECK (((`interest_rate` >= 0) and (`interest_rate` <= 100))),
  CONSTRAINT `chk_term_value` CHECK ((`term_value` between 1 and 60))
) ENGINE=InnoDB AUTO_INCREMENT=259 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='新业务期限占比明细（应用层校验求和=100%）';

-- ---------------------------------------------------------------------------
-- CREATE TABLE `prcp_sim_rhythm`
-- ---------------------------------------------------------------------------
CREATE TABLE `prcp_sim_rhythm` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `config_id` bigint NOT NULL COMMENT 'FK → prcp_sim_node_config.id',
  `scheme_id` bigint NOT NULL DEFAULT '0' COMMENT '冗余：方案 id（便于按方案查询）',
  `coa_node_id` bigint NOT NULL DEFAULT '0' COMMENT '冗余：账户册节点 id',
  `month_no` int NOT NULL COMMENT '月份序号（1~12）',
  `month_label` varchar(16) DEFAULT NULL COMMENT '月份标签（如 1月 / M1）',
  `weight` decimal(8,4) NOT NULL DEFAULT '0.0000' COMMENT '权重（%），12 个月之和必须 = 100',
  `sort_order` int DEFAULT '0' COMMENT '显示顺序（= month_no）',
  `remark` varchar(255) DEFAULT NULL,
  `is_deleted` tinyint(1) DEFAULT '0',
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_config_month` (`config_id`,`month_no`),
  KEY `idx_config` (`config_id`,`is_deleted`),
  KEY `idx_scheme` (`scheme_id`,`is_deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=1039 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='新业务模拟 — 节奏配置（12 个月权重分布）';

-- ---------------------------------------------------------------------------
-- CREATE TABLE `prcp_sim_growth_config`
-- ---------------------------------------------------------------------------
CREATE TABLE `prcp_sim_growth_config` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `scheme_id` bigint NOT NULL COMMENT '方案 id（→ prcp_sim_scheme.id）',
  `scheme_code` varchar(32) DEFAULT NULL COMMENT '方案编码（冗余，如 SIM_BOND_INTERBANK）',
  `scheme_name` varchar(64) DEFAULT NULL COMMENT '方案名称（冗余）',
  `coa_node_id` bigint NOT NULL COMMENT '账户册节点 id（→ prcp_coa_node.id）',
  `coa_node_code` varchar(32) DEFAULT NULL COMMENT '账户册节点编码（冗余，如 S010101010000）',
  `coa_node_name` varchar(128) DEFAULT NULL COMMENT '账户册节点名称（冗余）',
  `config_id` bigint NOT NULL COMMENT '节点配置 id（→ prcp_sim_node_config.id）',
  `growth_type` tinyint(1) NOT NULL DEFAULT '1' COMMENT '增长方式 (1=目标增长百分比 2=目前增长绝对值 3=增长百分比 4=增长值)',
  `year1_value` decimal(20,4) NOT NULL DEFAULT '0.0000' COMMENT '第 1 年增长参数',
  `year2_value` decimal(20,4) NOT NULL DEFAULT '0.0000' COMMENT '第 2 年增长参数',
  `year3_value` decimal(20,4) NOT NULL DEFAULT '0.0000' COMMENT '第 3 年增长参数',
  `year4_value` decimal(20,4) NOT NULL DEFAULT '0.0000' COMMENT '第 4 年增长参数',
  `year5_value` decimal(20,4) NOT NULL DEFAULT '0.0000' COMMENT '第 5 年增长参数',
  `unit` varchar(16) DEFAULT '%' COMMENT '参数单位 (% / ABS 绝对值 / WAN 万元)',
  `amount_unit` varchar(16) DEFAULT NULL COMMENT '绝对值单位 (WAN=万元 / YI=亿元)，growth_type=2/4 生效',
  `remark` varchar(255) DEFAULT NULL,
  `is_deleted` tinyint(1) DEFAULT '0',
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_config` (`config_id`),
  KEY `idx_scheme` (`scheme_id`,`is_deleted`),
  KEY `idx_coa_node` (`coa_node_id`,`is_deleted`),
  KEY `idx_growth_type` (`growth_type`)
) ENGINE=InnoDB AUTO_INCREMENT=128 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='新业务模拟 — 新业务增长配置（5 年分期增长参数）';

-- ---------------------------------------------------------------------------
-- CREATE TABLE `prcp_sim_run`
-- ---------------------------------------------------------------------------
CREATE TABLE `prcp_sim_run` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `sim_scheme_id` bigint NOT NULL COMMENT 'FK → prcp_sim_scheme.id',
  `sim_scheme_code` varchar(32) NOT NULL COMMENT '冗余：方案编码',
  `base_data_date` date NOT NULL COMMENT '基准月（=scheme.data_date）',
  `month_count` int DEFAULT '24' COMMENT '生成月份数（默认 24）',
  `target_data_date` date DEFAULT NULL COMMENT '目标月（=base + month_count 月）',
  `status` varchar(16) DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCESS/FAILED/CANCELLED',
  `progress` int DEFAULT '0' COMMENT '进度 0-100',
  `total_nodes` int DEFAULT '0' COMMENT '本次执行的节点总数',
  `configured_node_count` int NOT NULL DEFAULT '0' COMMENT '已配置节点数（参与新业务模拟）',
  `rolled_node_count` int NOT NULL DEFAULT '0' COMMENT '未配置节点数（只做桶滚动）',
  `aggregated_node_count` int NOT NULL DEFAULT '0' COMMENT '汇总节点数（从子叶子聚合）',
  `processed_nodes` int DEFAULT '0' COMMENT '已处理节点数',
  `duration_ms` bigint DEFAULT NULL COMMENT '执行耗时（毫秒）',
  `error_message` text,
  `started_at` datetime DEFAULT NULL,
  `finished_at` datetime DEFAULT NULL,
  `created_by` bigint DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_sim_scheme` (`sim_scheme_id`),
  KEY `idx_status` (`status`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=22 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='新业务模拟执行记录';

-- ---------------------------------------------------------------------------
-- CREATE TABLE `prcp_sim_result`
-- ---------------------------------------------------------------------------
CREATE TABLE `prcp_sim_result` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `sim_scheme_code` varchar(32) NOT NULL COMMENT '业务量方案编码',
  `sim_scheme_id` bigint NOT NULL COMMENT 'FK → prcp_sim_scheme.id',
  `run_id` bigint NOT NULL COMMENT 'FK → prcp_sim_run.id',
  `data_date` date NOT NULL COMMENT '偏移日期',
  `date_offset` int NOT NULL COMMENT '月份数（1..24）',
  `coa_scheme_id` bigint NOT NULL,
  `coa_node_id` bigint NOT NULL,
  `node_code` varchar(32) DEFAULT NULL,
  `node_name` varchar(64) DEFAULT NULL,
  `node_level` int DEFAULT NULL,
  `parent_code` varchar(32) DEFAULT NULL,
  `is_leaf` tinyint(1) DEFAULT '0',
  `category` varchar(8) DEFAULT NULL,
  `is_configured` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否有节点配置（1=已配置 → 新业务模拟；0=未配置 → 纯桶滚动）',
  `is_aggregated` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否为汇总节点（1=从后代叶子聚合 / 0=叶子节点）',
  `orig_m1` decimal(20,4) DEFAULT '0.0000',
  `orig_m2` decimal(20,4) DEFAULT '0.0000',
  `orig_m3` decimal(20,4) DEFAULT '0.0000',
  `orig_m4` decimal(20,4) DEFAULT '0.0000',
  `orig_m5` decimal(20,4) DEFAULT '0.0000',
  `orig_m6` decimal(20,4) DEFAULT '0.0000',
  `orig_m7` decimal(20,4) DEFAULT '0.0000',
  `orig_m8` decimal(20,4) DEFAULT '0.0000',
  `orig_m9` decimal(20,4) DEFAULT '0.0000',
  `orig_m10` decimal(20,4) DEFAULT '0.0000',
  `orig_m11` decimal(20,4) DEFAULT '0.0000',
  `orig_m12` decimal(20,4) DEFAULT '0.0000',
  `orig_m13` decimal(20,4) DEFAULT '0.0000',
  `orig_m14` decimal(20,4) DEFAULT '0.0000',
  `orig_m15` decimal(20,4) DEFAULT '0.0000',
  `orig_m16` decimal(20,4) DEFAULT '0.0000',
  `orig_m17` decimal(20,4) DEFAULT '0.0000',
  `orig_m18` decimal(20,4) DEFAULT '0.0000',
  `orig_m19` decimal(20,4) DEFAULT '0.0000',
  `orig_m20` decimal(20,4) DEFAULT '0.0000',
  `orig_m21` decimal(20,4) DEFAULT '0.0000',
  `orig_m22` decimal(20,4) DEFAULT '0.0000',
  `orig_m23` decimal(20,4) DEFAULT '0.0000',
  `orig_m24` decimal(20,4) DEFAULT '0.0000',
  `orig_m25` decimal(20,4) DEFAULT '0.0000',
  `orig_m26` decimal(20,4) DEFAULT '0.0000',
  `orig_m27` decimal(20,4) DEFAULT '0.0000',
  `orig_m28` decimal(20,4) DEFAULT '0.0000',
  `orig_m29` decimal(20,4) DEFAULT '0.0000',
  `orig_m30` decimal(20,4) DEFAULT '0.0000',
  `orig_m31` decimal(20,4) DEFAULT '0.0000',
  `orig_m32` decimal(20,4) DEFAULT '0.0000',
  `orig_m33` decimal(20,4) DEFAULT '0.0000',
  `orig_m34` decimal(20,4) DEFAULT '0.0000',
  `orig_m35` decimal(20,4) DEFAULT '0.0000',
  `orig_m36` decimal(20,4) DEFAULT '0.0000',
  `orig_m37` decimal(20,4) DEFAULT '0.0000',
  `orig_m38` decimal(20,4) DEFAULT '0.0000',
  `orig_m39` decimal(20,4) DEFAULT '0.0000',
  `orig_m40` decimal(20,4) DEFAULT '0.0000',
  `orig_m41` decimal(20,4) DEFAULT '0.0000',
  `orig_m42` decimal(20,4) DEFAULT '0.0000',
  `orig_m43` decimal(20,4) DEFAULT '0.0000',
  `orig_m44` decimal(20,4) DEFAULT '0.0000',
  `orig_m45` decimal(20,4) DEFAULT '0.0000',
  `orig_m46` decimal(20,4) DEFAULT '0.0000',
  `orig_m47` decimal(20,4) DEFAULT '0.0000',
  `orig_m48` decimal(20,4) DEFAULT '0.0000',
  `orig_m49` decimal(20,4) DEFAULT '0.0000',
  `orig_m50` decimal(20,4) DEFAULT '0.0000',
  `orig_m51` decimal(20,4) DEFAULT '0.0000',
  `orig_m52` decimal(20,4) DEFAULT '0.0000',
  `orig_m53` decimal(20,4) DEFAULT '0.0000',
  `orig_m54` decimal(20,4) DEFAULT '0.0000',
  `orig_m55` decimal(20,4) DEFAULT '0.0000',
  `orig_m56` decimal(20,4) DEFAULT '0.0000',
  `orig_m57` decimal(20,4) DEFAULT '0.0000',
  `orig_m58` decimal(20,4) DEFAULT '0.0000',
  `orig_m59` decimal(20,4) DEFAULT '0.0000',
  `orig_m60` decimal(20,4) DEFAULT '0.0000',
  `orig_y10` decimal(20,4) DEFAULT '0.0000',
  `orig_y15` decimal(20,4) DEFAULT '0.0000',
  `orig_y20` decimal(20,4) DEFAULT '0.0000',
  `orig_y30` decimal(20,4) DEFAULT '0.0000',
  `rem_m1` decimal(20,4) DEFAULT '0.0000',
  `rem_m2` decimal(20,4) DEFAULT '0.0000',
  `rem_m3` decimal(20,4) DEFAULT '0.0000',
  `rem_m4` decimal(20,4) DEFAULT '0.0000',
  `rem_m5` decimal(20,4) DEFAULT '0.0000',
  `rem_m6` decimal(20,4) DEFAULT '0.0000',
  `rem_m7` decimal(20,4) DEFAULT '0.0000',
  `rem_m8` decimal(20,4) DEFAULT '0.0000',
  `rem_m9` decimal(20,4) DEFAULT '0.0000',
  `rem_m10` decimal(20,4) DEFAULT '0.0000',
  `rem_m11` decimal(20,4) DEFAULT '0.0000',
  `rem_m12` decimal(20,4) DEFAULT '0.0000',
  `rem_m13` decimal(20,4) DEFAULT '0.0000',
  `rem_m14` decimal(20,4) DEFAULT '0.0000',
  `rem_m15` decimal(20,4) DEFAULT '0.0000',
  `rem_m16` decimal(20,4) DEFAULT '0.0000',
  `rem_m17` decimal(20,4) DEFAULT '0.0000',
  `rem_m18` decimal(20,4) DEFAULT '0.0000',
  `rem_m19` decimal(20,4) DEFAULT '0.0000',
  `rem_m20` decimal(20,4) DEFAULT '0.0000',
  `rem_m21` decimal(20,4) DEFAULT '0.0000',
  `rem_m22` decimal(20,4) DEFAULT '0.0000',
  `rem_m23` decimal(20,4) DEFAULT '0.0000',
  `rem_m24` decimal(20,4) DEFAULT '0.0000',
  `rem_m25` decimal(20,4) DEFAULT '0.0000',
  `rem_m26` decimal(20,4) DEFAULT '0.0000',
  `rem_m27` decimal(20,4) DEFAULT '0.0000',
  `rem_m28` decimal(20,4) DEFAULT '0.0000',
  `rem_m29` decimal(20,4) DEFAULT '0.0000',
  `rem_m30` decimal(20,4) DEFAULT '0.0000',
  `rem_m31` decimal(20,4) DEFAULT '0.0000',
  `rem_m32` decimal(20,4) DEFAULT '0.0000',
  `rem_m33` decimal(20,4) DEFAULT '0.0000',
  `rem_m34` decimal(20,4) DEFAULT '0.0000',
  `rem_m35` decimal(20,4) DEFAULT '0.0000',
  `rem_m36` decimal(20,4) DEFAULT '0.0000',
  `rem_m37` decimal(20,4) DEFAULT '0.0000',
  `rem_m38` decimal(20,4) DEFAULT '0.0000',
  `rem_m39` decimal(20,4) DEFAULT '0.0000',
  `rem_m40` decimal(20,4) DEFAULT '0.0000',
  `rem_m41` decimal(20,4) DEFAULT '0.0000',
  `rem_m42` decimal(20,4) DEFAULT '0.0000',
  `rem_m43` decimal(20,4) DEFAULT '0.0000',
  `rem_m44` decimal(20,4) DEFAULT '0.0000',
  `rem_m45` decimal(20,4) DEFAULT '0.0000',
  `rem_m46` decimal(20,4) DEFAULT '0.0000',
  `rem_m47` decimal(20,4) DEFAULT '0.0000',
  `rem_m48` decimal(20,4) DEFAULT '0.0000',
  `rem_m49` decimal(20,4) DEFAULT '0.0000',
  `rem_m50` decimal(20,4) DEFAULT '0.0000',
  `rem_m51` decimal(20,4) DEFAULT '0.0000',
  `rem_m52` decimal(20,4) DEFAULT '0.0000',
  `rem_m53` decimal(20,4) DEFAULT '0.0000',
  `rem_m54` decimal(20,4) DEFAULT '0.0000',
  `rem_m55` decimal(20,4) DEFAULT '0.0000',
  `rem_m56` decimal(20,4) DEFAULT '0.0000',
  `rem_m57` decimal(20,4) DEFAULT '0.0000',
  `rem_m58` decimal(20,4) DEFAULT '0.0000',
  `rem_m59` decimal(20,4) DEFAULT '0.0000',
  `rem_m60` decimal(20,4) DEFAULT '0.0000',
  `rem_y10` decimal(20,4) DEFAULT '0.0000',
  `rem_y15` decimal(20,4) DEFAULT '0.0000',
  `rem_y20` decimal(20,4) DEFAULT '0.0000',
  `rem_y30` decimal(20,4) DEFAULT '0.0000',
  `asf_rsf` varchar(8) DEFAULT NULL,
  `hqla_factor` decimal(8,4) DEFAULT NULL,
  `current_balance` decimal(20,4) DEFAULT '0.0000',
  `avg_balance` decimal(20,4) DEFAULT '0.0000',
  `weighted_rate` decimal(10,6) DEFAULT '0.000000',
  `interest_amount` decimal(20,4) DEFAULT '0.0000',
  `risk_weight` decimal(10,6) DEFAULT '0.000000',
  `calc_note` text,
  `is_deleted` tinyint(1) DEFAULT '0',
  `created_by` bigint DEFAULT NULL,
  `updated_by` bigint DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `growth_type` tinyint(1) DEFAULT NULL COMMENT '增长方式 (1=目标增长百分比 2=目标增长绝对值 3=增长百分比 4=增长值)',
  `year_target` decimal(20,4) DEFAULT NULL COMMENT '当年目标增量（元）',
  `rhythm_weight` decimal(8,4) DEFAULT NULL COMMENT '当月节奏权重（%）',
  `month_add` decimal(20,4) DEFAULT NULL COMMENT '当月纯新增投放（元，可为负=缩表）',
  `month_release` decimal(20,4) DEFAULT NULL COMMENT '当月到期释放（元）',
  `planned_due` decimal(20,4) DEFAULT NULL COMMENT '当月计划到期金额（绝对到期月口径：存量+当月新增，含未来月份投放本就会到期的部分）',
  `cum_planned` decimal(20,4) DEFAULT NULL COMMENT '截至当月的累计计划投放额',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sim_node_date` (`sim_scheme_code`,`run_id`,`coa_node_id`,`data_date`,`date_offset`),
  KEY `idx_sim_scheme` (`sim_scheme_code`),
  KEY `idx_run` (`run_id`),
  KEY `idx_date` (`data_date`),
  KEY `idx_node` (`coa_node_id`),
  KEY `idx_is_configured` (`is_configured`),
  KEY `idx_is_aggregated` (`is_aggregated`)
) ENGINE=InnoDB AUTO_INCREMENT=47365 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='新业务模拟快照结果';

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================================
--  执行完毕
-- ============================================================================
