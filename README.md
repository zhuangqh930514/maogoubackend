# Maogou Stock Backend

Spring Boot 3.x 后端骨架，用于承接当前 Vue 3 + Element Plus + ECharts 前端页面。

## 技术栈

- Spring Boot 3.x
- MyBatis-Plus
- MySQL 8.0
- Spring Task
- RestTemplate
- OpenAI-compatible local model API, such as Ollama or vLLM

## 模块结构

```text
backend/src/main/java/com/maogou/stock
├── common              # 统一响应和异常处理
├── config              # 配置属性、RestTemplate
├── controller          # /api HTTP 接口
├── domain              # 实体和枚举
├── dto                 # 前端接口请求/响应模型
├── infrastructure      # 行情源、AI 模型外部适配
├── mapper              # MyBatis-Plus Mapper
├── scheduler           # 财经资讯和 AI 分析定时任务
└── service             # 业务服务接口与实现
```

## 当前 API 合同

| 前端页面 | 后端接口 |
| --- | --- |
| 注册登录 | `POST /api/auth/register`, `POST /api/auth/login`, `GET /api/auth/me` |
| 资讯首页 | `GET /api/news/latest` |
| 大盘数据 | `GET /api/market/indexes`, `GET /api/market/indexes/{code}/intraday` |
| 个股详情 | `GET /api/stocks/{code}`, `GET /api/stocks/{code}/kline` |
| 自选股 | `GET /api/watchlist`, `POST /api/watchlist`, `DELETE /api/watchlist/{code}` |
| 持仓记录 | `GET /api/portfolio/trades`, `POST /api/portfolio/trades`, `GET /api/portfolio/positions` |
| AI 分析报告 | `GET /api/ai/reports`, `POST /api/ai/analyze`, `POST /api/ai/analyze-watchlist` |
| 模型配置中心 | `GET /api/settings/model`, `PUT /api/settings/model`, `POST /api/settings/model/test` |

除注册和登录外，业务接口使用 `Authorization: Bearer <token>` 访问。

## 本地启动

1. 创建数据库并执行建表脚本：

```bash
mysql -uroot -p < backend/src/main/resources/db/schema.sql
```

如果是已有数据库，先执行一次增量脚本：

```bash
mysql -uroot -p maogou < backend/src/main/resources/db/20260522_auth_upgrade.sql
```

2. 按需配置 MySQL 和本地模型：

```bash
export MYSQL_URL='jdbc:mysql://127.0.0.1:3306/maogou_stock?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
export MYSQL_USERNAME='root'
export MYSQL_PASSWORD='root'
export MAOGOU_AI_API_BASE_URL='http://localhost:11434/v1'
export MAOGOU_AI_MODEL_NAME='qwen3.6'
export MAOGOU_AI_API_KEY='sk-local-dev-key'
export MAOGOU_JWT_SECRET='replace-with-a-long-random-secret'
```

3. 启动后端：

```bash
mvn -f backend/pom.xml spring-boot:run
```

4. 前端开发代理已配置：

```bash
npm run dev
```

Vite 会把 `/api` 代理到 `http://127.0.0.1:8081`，也可以通过 `VITE_API_PROXY_TARGET` 覆盖。

## 后续接真实行情源的位置

当前默认使用 `MockMarketDataClient`，用于让接口合同先稳定下来。

后续可以新增：

- `AkShareMarketDataClient`：对接本地 AkShare HTTP 服务
- `SinaMarketDataClient`：对接新浪财经公开接口
- `EastMoneyMarketDataClient`：对接东方财富公开接口

只需要实现 `MarketDataClient`，并通过 `maogou.market.provider` 切换实现。
