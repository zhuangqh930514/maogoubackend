# 猫狗智投 AI 进化 V2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把现有演示级学习闭环升级为不可变、可复现、可样本外验证的 V2 系统，并在每日自动任务结束后生成可查看的投研日报。

**Architecture:** 保留 V1 读取兼容，新增 V2 数据域和服务，通过影子运行逐步切换。数值引擎负责预测和排序，LLM 只负责解释；每日、每周、每月任务分层执行，日报是每日流水线最终产物。

**Tech Stack:** Java 17、Spring Boot 3、MyBatis-Plus、MySQL 8、JUnit 5、Vue 3、Vite、Element Plus、ECharts；后续训练器使用 Python/LightGBM 并导出 ONNX。

## 2026-07-13 实施状态

- 实施包 A-F 的代码、迁移、自动化编排、每日投研、投研日报和前端入口均已落地；当前契约为 `LABEL_V2.2`、`FACTOR_V2/2.0.0`、T+3 排序模型和沪深 300 基准。
- 每日九步流水线、周五 18:00 策略验证、每月 1 日 19:00 模型训练均已接入。月度训练不混用 T+1/T+5 标签，未通过样本外门槛的模型只能保持 `CANDIDATE`，系统不会自动晋级 Champion。
- 最新本地回归：后端 193 项测试、前端 7 项测试、Python 5 项测试全部通过；前后端生产构建成功，MySQL 8 空库全量 schema 成功，V2 与租约迁移各重复执行两次成功。
- 真实账号完成登录、首页、大盘、自选股、持仓、每日投研、投研日报、分析报告、自动化、聊天和模型配置浏览器检查；关键移动页面在 `390 x 844` 下无横向溢出，检查期间无控制台错误和 HTTP 500。
- 尚未完成的是生产统计验收，而不是代码实现：V2/Challenger 仍需在线影子运行至少 20 个交易日并满足样本量、回撤、覆盖率和置信区间门槛，之后才能由人工决定是否晋级。
- 本实施不自动发布、部署、提交或推送；只有收到用户明确发布指令后才执行上线流程。

---

## 实施包 A：立即止损与语义修复

### Task 1: 修复低历史命中率被错误转换为回避

**Files:**
- Modify: `src/main/java/com/maogou/stock/service/impl/AiDailyInsightScoring.java`
- Test: `src/test/java/com/maogou/stock/service/impl/AiDailyInsightScoringTest.java`

- [ ] 增加失败测试：`WATCH + HISTORY_WEAK` 必须输出 `WATCH`，不能输出 `REDUCE`。
- [ ] 运行 `mvn -Dtest=AiDailyInsightScoringTest test`，确认测试因当前 `HISTORY_WEAK` 分支失败。
- [ ] 将历史可靠性作为置信等级门控；只有当前 AI/数值信号明确负向或风险超限时输出 `AVOID`。
- [ ] 增加 `riskLevel=HIGH`、缺失结构化决策和低数据质量测试。
- [ ] 运行目标测试和全量 `mvn test`。

### Task 2: 隔离旧版因子统计写入

**Files:**
- Modify: `src/main/java/com/maogou/stock/service/impl/AutoClosePipelineServiceImpl.java`
- Modify: `src/main/java/com/maogou/stock/service/impl/AiEvolutionServiceImpl.java`
- Create: `src/test/java/com/maogou/stock/service/impl/AutoClosePipelineDefinitionTest.java`

- [ ] 写失败测试，断言每日流水线不再包含 `VERIFY_REVIEWS` 和旧版 `REFRESH_FACTORS` 写步骤。
- [ ] 运行目标测试并确认失败。
- [ ] 每日流水线只调用新版 `verifyLabels()` 和 V2 因子更新；旧版进化页保持只读兼容。
- [ ] 为旧版刷新接口返回 `LEGACY_READ_ONLY`，阻止覆盖统计表。
- [ ] 运行全量测试。

### Task 3: 缩短事务并去掉重复复盘

**Files:**
- Modify: `src/main/java/com/maogou/stock/service/impl/AiAnalysisServiceImpl.java`
- Modify: `src/main/java/com/maogou/stock/service/impl/AiLearningServiceImpl.java`
- Create: `src/test/java/com/maogou/stock/service/impl/AiLearningRefreshPolicyTest.java`

- [ ] 写失败测试：已完成且行情未变化的标签不进入待验证队列。
- [ ] 提取只读外部调用和短事务持久化边界。
- [ ] `analyzeStock` 的行情、资讯、LLM 调用不得处于数据库事务内。
- [ ] 标签任务只查询已成熟、未验证的预测，不再扫描并重写全部预测。
- [ ] 验证任务日志只出现一次 `VERIFY_LABELS`。

## 实施包 B：V2 数据基座

### Task 4: 创建 V2 数据库迁移

**Files:**
- Create: `src/main/resources/db/20260712_ai_evolution_v2.sql`
- Modify: `src/main/resources/db/schema.sql`
- Create: `src/test/java/com/maogou/stock/schema/AiEvolutionV2SchemaContractTest.java`

- [ ] 编写模式契约测试，验证 Tasks 7-18 所需表、不可变唯一键、窗口边界、生成列唯一约束、血缘、外键和关键索引。
- [ ] 创建样本/预测/标签基座：`ai_data_batch`、`ai_sample_v2`、`ai_factor_value_v2`、`ai_trading_calendar`、`ai_prediction_v2`、`ai_label_v2`、`ai_label_cost_evidence`、`ai_factor_performance_v2`。
- [ ] 创建训练/实验/回测基座：`ai_training_dataset`、`ai_training_dataset_item`、`ai_model_version`、`ai_walk_forward_run`、`ai_walk_forward_fold`、`ai_walk_forward_baseline`、`ai_portfolio_backtest_run`、`ai_portfolio_backtest_daily`、`ai_portfolio_backtest_trade`、`ai_portfolio_backtest_position`。
- [ ] 创建运行/治理基座：`ai_strategy_release`、`ai_pipeline_run`、`ai_pipeline_step`、`ai_shadow_evaluation`、`ai_shadow_evaluation_item`、`ai_drift_event`、`ai_strategy_governance_event`、`ai_research_daily_report`。
- [ ] 必需父关联使用非零真实 ID，可选父关联显式为 `NULL`；不得出现 `*_id BIGINT ... DEFAULT 0`。
- [ ] `schema.sql` 只保留一个 V2 区块，并通过契约测试保证与增量脚本完全一致。
- [ ] 所有迁移使用 `CREATE TABLE IF NOT EXISTS` 和可重复执行的兼容过程。
- [ ] 用 MySQL 8 容器执行迁移两次，第二次必须成功且表结构不变。

### Task 5: 实现不可变样本与数据批次

**Files:**
- Create: `src/main/java/com/maogou/stock/domain/entity/v2/AiDataBatch.java`
- Create: `src/main/java/com/maogou/stock/domain/entity/v2/AiSampleV2.java`
- Create: `src/main/java/com/maogou/stock/mapper/v2/AiDataBatchMapper.java`
- Create: `src/main/java/com/maogou/stock/mapper/v2/AiSampleV2Mapper.java`
- Create: `src/main/java/com/maogou/stock/service/v2/AiSampleSnapshotService.java`
- Create: `src/main/java/com/maogou/stock/service/impl/v2/AiSampleSnapshotServiceImpl.java`
- Test: `src/test/java/com/maogou/stock/service/impl/v2/AiSampleSnapshotServiceImplTest.java`

- [ ] 测试同一幂等键返回同一快照，不修改原始特征。
- [ ] 测试新的 `asOfTime` 创建新样本。
- [ ] 测试核心行情过期时样本状态为 `UNAVAILABLE`。
- [ ] 实现数据批次质量评分和来源时间记录。
- [ ] 批量构建股票池样本，单票失败隔离。

### Task 6: 实现版本化因子引擎

**Files:**
- Create: `src/main/java/com/maogou/stock/domain/entity/v2/AiFactorValueV2.java`
- Create: `src/main/java/com/maogou/stock/service/v2/AiFactorEngineV2.java`
- Create: `src/main/java/com/maogou/stock/service/impl/v2/AiFactorEngineV2Impl.java`
- Test: `src/test/java/com/maogou/stock/service/impl/v2/AiFactorEngineV2ImplTest.java`

- [ ] 测试正负连续因子保留符号。
- [ ] 测试截面 Winsorize 与 Z-Score 不使用未来样本。
- [ ] 测试缺失财务、板块和资讯产生缺失原因而不是零值伪数据。
- [ ] 第一版实现趋势、动量、量价、波动、市场、板块、基本面和交易约束因子。
- [ ] 因子值按 `sample_id + factor_code + factor_version` 幂等写入。

### Task 7: 实现不可变预测与决策引擎

**Files:**
- Create: `src/main/java/com/maogou/stock/domain/entity/v2/AiPredictionV2.java`
- Create: `src/main/java/com/maogou/stock/service/v2/AiPredictionEngineV2.java`
- Create: `src/main/java/com/maogou/stock/service/impl/v2/AiPredictionEngineV2Impl.java`
- Create: `src/main/java/com/maogou/stock/service/v2/AiDecisionPolicy.java`
- Test: `src/test/java/com/maogou/stock/service/impl/v2/AiPredictionEngineV2ImplTest.java`

- [ ] 测试每个交易日独立排名，Top K 不包含 `rankNo=null`。
- [ ] 测试低置信度输出 `WATCH/ABSTAIN`。
- [ ] 测试不可交易和数据过期输出 `UNAVAILABLE`。
- [ ] 实现规则基线，输出预期收益、风险、方向概率和理由。
- [ ] 预测插入后禁止更新，重复幂等键返回原预测。
- [ ] 持久化交易日、样本阶段、推理模式和输入指纹；业务唯一键固定为用户 + 股票 + 交易日 + 阶段 + 周期 + 策略发布 + 推理模式 + 输入指纹。
- [ ] `strategy_release_id` 是必需外键；规则推理没有模型时 `model_version_id` 使用 `NULL`，不得写入 `0`。

## 实施包 C：真实标签、实验与回测

### Task 8: 实现交易日和可执行标签

**Files:**
- Create: `src/main/java/com/maogou/stock/domain/entity/v2/AiLabelV2.java`
- Create: `src/main/java/com/maogou/stock/service/v2/AiLabelServiceV2.java`
- Create: `src/main/java/com/maogou/stock/service/impl/v2/AiLabelServiceV2Impl.java`
- Test: `src/test/java/com/maogou/stock/service/impl/v2/AiLabelServiceV2ImplTest.java`

- [ ] 测试收盘预测以下一交易日开盘入场。
- [ ] 测试 T+1/T+3/T+5 使用真实交易日而非自然日。
- [ ] 测试手续费、印花税、滑点、指数收益和超额收益。
- [ ] 测试涨停买不进、跌停卖不出、停牌和 ST 状态。
- [ ] 测试标签首次创建后不覆盖，修正生成新版本。
- [ ] `ai_trading_calendar` 按市场 + 日期 + 日历版本唯一，并保存来源时间与指纹。
- [ ] 标签关联入场/出场日历记录；`ai_label_cost_evidence` 固化成本模型版本、费率、滑点、逐项金额、总成本和证据 JSON。

### Task 9: 实现因子表现与漂移监控

**Files:**
- Create: `src/main/java/com/maogou/stock/service/v2/AiFactorPerformanceService.java`
- Create: `src/main/java/com/maogou/stock/service/impl/v2/AiFactorPerformanceServiceImpl.java`
- Test: `src/test/java/com/maogou/stock/service/impl/v2/AiFactorPerformanceServiceImplTest.java`

- [ ] 测试按因子版本、周期、市场环境和滚动窗口隔离统计。
- [ ] 实现 RankIC、平均超额收益、Wilson 下界、最大不利波动和稳定性。
- [ ] 样本少于 20 标记 `LOW_SAMPLE`，不参与调权。
- [ ] 实现 PSI/命中率漂移告警。
- [ ] 因子表现唯一键同时包含窗口起止日期；`ai_drift_event` 用事件指纹幂等保存窗口、指标、阈值、严重度、状态和证据。

### Task 10: 实现 Walk-forward 实验

**Files:**
- Create: `src/main/java/com/maogou/stock/service/v2/AiWalkForwardService.java`
- Create: `src/main/java/com/maogou/stock/service/impl/v2/AiWalkForwardServiceImpl.java`
- Test: `src/test/java/com/maogou/stock/service/impl/v2/AiWalkForwardServiceImplTest.java`

- [ ] 测试训练、验证、测试按日期切分且不重叠。
- [ ] 测试 T+N purge/embargo 清除边界泄漏样本。
- [ ] 计算真实指数、等权池、动量和 Champion 基线。
- [ ] 输出多个窗口及聚合置信区间。
- [ ] `ai_walk_forward_run` 固化数据集、策略/模型、随机种子、输入指纹和配置；fold 保存不重叠日期窗口；baseline 按 fold + baseline key 唯一。

### Task 11: 实现组合回测

**Files:**
- Create: `src/main/java/com/maogou/stock/service/v2/AiPortfolioBacktestService.java`
- Create: `src/main/java/com/maogou/stock/service/impl/v2/AiPortfolioBacktestServiceImpl.java`
- Test: `src/test/java/com/maogou/stock/service/impl/v2/AiPortfolioBacktestServiceImplTest.java`

- [ ] 测试每天最多持有 Top K 且按日期调仓。
- [ ] 测试组合收益复利，不直接相加逐笔收益。
- [ ] 测试 T+1、涨跌停、停牌、仓位、手续费和滑点。
- [ ] 输出净值、基准净值、Alpha、Sharpe、Calmar、最大回撤、换手率和单票贡献。
- [ ] 相同数据和版本重复运行结果完全一致。
- [ ] 使用 run/daily/trade/position 四级存储；run 固化引擎/配置/输入指纹和随机种子，daily 保存复利净值，trade 保存执行成本，position 保存每日仓位与贡献。

### Task 12: 实现 Champion/Challenger 治理

**Files:**
- Create: `src/main/java/com/maogou/stock/service/v2/AiStrategyGovernanceService.java`
- Create: `src/main/java/com/maogou/stock/service/impl/v2/AiStrategyGovernanceServiceImpl.java`
- Test: `src/test/java/com/maogou/stock/service/impl/v2/AiStrategyGovernanceServiceImplTest.java`

- [ ] 测试未达到最小天数、样本数、交易数或窗口数时禁止晋级。
- [ ] 测试回撤超限、单票贡献超限和置信区间不达标时禁止晋级。
- [ ] 测试 Challenger 只能影子运行，人工确认后成为 Champion。
- [ ] 测试退化告警触发上一版回滚。
- [ ] `ai_strategy_governance_event` 幂等记录晋级、拒绝、人工确认和回滚，并关联 Walk-forward、回测与影子证据。
- [ ] 使用 `active_champion_guard` 生成列唯一键在数据库层保证每用户最多一个 active Champion。

## 实施包 D：自动化与投研日报

### Task 13: 实现可恢复流水线

**Files:**
- Create: `src/main/java/com/maogou/stock/service/v2/AiDailyPipelineServiceV2.java`
- Create: `src/main/java/com/maogou/stock/service/impl/v2/AiDailyPipelineServiceV2Impl.java`
- Modify: `src/main/java/com/maogou/stock/scheduler/AutoClosePipelineScheduler.java`
- Test: `src/test/java/com/maogou/stock/service/impl/v2/AiDailyPipelineServiceV2ImplTest.java`

- [ ] 测试步骤顺序：数据、质量、标签、因子、样本、预测、报告、每日投研、日报。
- [ ] 测试步骤失败后从失败点恢复，不重复已完成预测。
- [ ] 测试单票失败不终止整个股票池。
- [ ] 每周实验/回测和月度训练不进入每日重任务。
- [ ] 外部请求不持有数据库事务。
- [ ] 流水线 run 保存输入指纹及可选数据批次/策略/模型外键；step 保存检查点和输出指纹，所有可选关联使用 `NULL`。

### Task 14: 生成结构化投研日报

**Files:**
- Create: `src/main/java/com/maogou/stock/domain/entity/v2/AiResearchDailyReport.java`
- Create: `src/main/java/com/maogou/stock/dto/ai/AiResearchDailyReportPayloads.java`
- Create: `src/main/java/com/maogou/stock/service/AiResearchDailyReportService.java`
- Create: `src/main/java/com/maogou/stock/service/impl/AiResearchDailyReportServiceImpl.java`
- Create: `src/main/java/com/maogou/stock/controller/AiResearchDailyReportController.java`
- Test: `src/test/java/com/maogou/stock/service/impl/AiResearchDailyReportServiceImplTest.java`

- [ ] 测试日报幂等：每用户每交易日只有一份当前版本。
- [ ] 测试成功流水线生成市场摘要、推荐、观察、回避、持仓风险、因子、策略表现和新鲜度。
- [ ] 测试 LLM 不可用时仍生成模板日报。
- [ ] 测试流水线失败时生成异常日报并指出失败步骤。
- [ ] 提供 `GET /api/ai/research-daily-reports/latest`、列表、详情和手工重建接口。
- [ ] 日报保存调用幂等键和替代链；`current_report_guard` 生成列唯一键保证每用户每交易日只有一个 current 版本。

## 实施包 E：前端产品收敛

### Task 15: 新增投研日报页面

**Files:**
- Create: `../frontend/src/services/researchDailyReport.js`
- Create: `../frontend/src/views/ResearchDailyReportView.vue`
- Modify: `../frontend/src/router/index.js`
- Modify: `../frontend/src/components/ShellLayout.vue`
- Test: `../frontend/src/views/__tests__/ResearchDailyReportView.spec.js`

- [ ] 写失败组件测试：成功、空数据、失败日报和数据过期四种状态。
- [ ] 在 `AI分析` 下新增“投研日报”，位于“每日投研”之后。
- [ ] 展示市场摘要、今日结论、推荐、观察、回避、持仓风险、关键因子、策略表现、数据新鲜度和任务状态。
- [ ] 支持按交易日查看历史日报和跳转股票报告。
- [ ] 保证移动端和桌面端无文本重叠。

### Task 16: 收敛研究菜单与自动化页面

**Files:**
- Modify: `../frontend/src/components/ShellLayout.vue`
- Modify: `../frontend/src/views/AutomationTasksView.vue`
- Modify: `../frontend/src/views/AiLearningEvolutionView.vue`
- Modify: `../frontend/src/views/AiFactorHubView.vue`
- Modify: `../frontend/src/views/AiStrategyValidationView.vue`

- [ ] 普通菜单只展示每日投研、投研日报、分析报告和研究实验室。
- [ ] 自动化页面默认只展示总开关、最近运行、数据质量、失败步骤和重试。
- [ ] 手工样本/因子/回测按钮移入专家模式。
- [ ] 旧版指标标记为 `LEGACY`，不展示为当前有效胜率。

## 实施包 F：模型训练与影子验证

### Task 17: 建立训练数据导出和模型版本

**Files:**
- Create: `src/main/java/com/maogou/stock/service/v2/AiTrainingDatasetService.java`
- Create: `src/main/java/com/maogou/stock/service/impl/v2/AiTrainingDatasetServiceImpl.java`
- Create: `ml/train_ranker.py`
- Create: `ml/requirements.txt`
- Test: `src/test/java/com/maogou/stock/service/impl/v2/AiTrainingDatasetServiceImplTest.java`
- Test: `ml/tests/test_train_ranker.py`

- [ ] 测试导出数据只包含样本时点可见特征。
- [ ] 测试训练/验证/测试按日期切分。
- [ ] 训练逻辑回归基线和 LightGBM Ranker，记录随机种子与参数。
- [ ] 执行概率校准并导出 ONNX、特征清单和指标 JSON。
- [ ] 注册 `ai_model_version`，不达标模型保持 `CANDIDATE`。
- [ ] `ai_training_dataset` 固化来源查询、选择规则、日期窗口、可见性截止时间和血缘指纹；item 逐行关联不可变样本与成熟标签并保存 split 和双指纹。
- [ ] `ai_model_version.training_dataset_id` 是必需外键，同时保存训练器版本、随机种子及特征清单校验和。

### Task 18: 接入影子推理和漂移监控

**Files:**
- Create: `src/main/java/com/maogou/stock/infrastructure/ml/OnnxPredictionClient.java`
- Create: `src/main/java/com/maogou/stock/service/v2/AiShadowEvaluationService.java`
- Test: `src/test/java/com/maogou/stock/infrastructure/ml/OnnxPredictionClientTest.java`

- [ ] 测试 ONNX 特征顺序和缺失值处理。
- [ ] Champion 与 Challenger 对同一不可变样本同时预测。
- [ ] 影子预测不影响用户结果。
- [ ] 记录覆盖率、校准、收益、回撤和特征漂移。
- [ ] 满足治理门槛后生成晋级候选，不自动启用。
- [ ] `ai_shadow_evaluation` 保存窗口汇总，`ai_shadow_evaluation_item` 将 Champion/Challenger 预测配对到同一样本和周期，并校验两条预测不能相同。
- [ ] 漂移告警写入 `ai_drift_event`，晋级候选只写治理证据，不直接更新 active Champion。

## 完整回归与验收

### Task 19: 后端回归

- [ ] `mvn test`
- [ ] `mvn -DskipTests package`
- [ ] MySQL 8 空库执行全量 schema。
- [ ] 现有生产结构执行增量迁移两次。
- [ ] 使用固定黄金数据集验证预测不可变、标签、回测和日报。
- [ ] 验证登录、自选股、持仓、行情、报告、聊天和模型配置没有回归。

### Task 20: 前端与浏览器回归

- [ ] `npm test -- --run`
- [ ] `npm run build`
- [ ] 启动本地前后端并使用真实账户走登录、自选股、持仓、每日投研、投研日报、报告、自动化和聊天。
- [ ] 使用 Playwright 验证桌面和移动端截图、空状态、错误状态和导航。
- [ ] 检查浏览器控制台无未处理错误，关键接口无 500。

### Task 21: 影子运行验收

- [ ] 本地和测试环境连续执行完整流水线。
- [ ] 验证每次流水线结束后都有日报。
- [ ] 验证失败流水线也有异常日报。
- [ ] V2 至少影子运行 20 个交易日后才允许替换 V1 用户结果。
- [ ] 发布必须等待用户明确指令，不在实现完成后自动部署。
