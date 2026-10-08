# PRCP-Java 项目文档总目录

> 本目录收纳 **PRCP-Java** 项目（Spring Boot 2.7 + Vue3）的全部业务文档，按"需求 / 设计 / 开发"三层组织。

## 一、文档分层约定

| 层级 | 路径 | 内容 | 谁写 | 谁读 |
|---|---|---|---|---|
| **需求** | `docs/requirements/` | 业务背景、用户故事、功能清单、验收标准 | 产品经理 / 业务架构 | 客户、研发、测试 |
| **设计** | `docs/design/` | 技术架构、数据库、接口、UI/UX | 系统架构师 / 主程 | 研发、测试、运维 |
| **开发** | `docs/development/` | 已实现功能、关键决策、踩过的坑、上线复盘 | 主程 / 责任工程师 | 研发、运维、接手者 |

## 二、按模块的文档索引

### 1. 指标管理（KPI）

| 文档 | 路径 |
|---|---|
| 需求规格 | [requirements/01-指标管理_需求.md](./requirements/01-指标管理_需求.md) |
| 设计方案 | [design/01-指标管理_设计.md](./design/01-指标管理_设计.md) |
| 开发纪要 | [development/01-指标管理_开发.md](./development/01-指标管理_开发.md) |

### 2. 模型管理（Model）

| 文档 | 路径 |
|---|---|
| 需求规格 | [requirements/02-模型管理_需求.md](./requirements/02-模型管理_需求.md) |
| 设计方案 | [design/02-模型管理_设计.md](./design/02-模型管理_设计.md) |
| 开发纪要 | [development/02-模型管理_开发.md](./development/02-模型管理_开发.md) |

### 3. 组合反算（Reverse）

| 文档 | 路径 |
|---|---|
| 需求规格 | [requirements/03-组合反算_需求.md](./requirements/03-组合反算_需求.md) |
| 设计方案 | [design/03-组合反算_设计.md](./design/03-组合反算_设计.md) |
| 开发纪要 | [development/03-组合反算_开发.md](./development/03-组合反算_开发.md) |

## 三、项目技术栈速览

| 维度 | 选型 | 版本 |
|---|---|---|
| 后端语言 | Java | 17 |
| 后端框架 | Spring Boot | 2.7.18 |
| ORM | MyBatis-Plus | 3.5.5 |
| 前端 | Vue3 + Element-Plus + ECharts + Vite | Vue 3.4 |
| 数据库 | MySQL | 8.0 |
| 部署 | nginx 1.18 + jar + systemd | — |
| JDK | OpenJDK | 17 |
| 构建 | Maven | 4 模块（common/framework/business/app）|

## 四、模块代码路径速查

| 模块 | 后端 | 前端 |
|---|---|---|
| 指标管理 | `prcp-business/src/main/java/com/prcp/business/kpi/` | `src/views/kpi/Index.vue` |
| 模型管理 | `prcp-business/src/main/java/com/prcp/business/model/` | `src/views/model/Index.vue` |
| 组合反算 | `prcp-business/src/main/java/com/prcp/business/reverse/` | `src/views/reverse/Index.vue` + `reverse/components/DashboardPanels.vue` |