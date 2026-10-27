# LogPilot · 内网日志采集与智能分析平台

通过日志持续发现并修复系统缺陷。把内网服务器与日志路径配置进平台，平台定时增量采集日志，
自动解析异常与堆栈、按指纹聚合成"异常组"，结合**内置知识库**（26 条常见 Java 异常修复经验）与**可选 AI 大模型**
给出根因分析与修复方案，并通过告警与修复闭环（待处理 → 已分析 → 已修复）驱动缺陷被真正修掉。

## 功能总览

| 模块 | 能力 |
|---|---|
| 服务器管理 | 内网 SSH 服务器（密码/私钥认证，AES-GCM 加密存储）、连通性测试、本机 LOCAL 节点 |
| 日志源管理 | 支持 glob 通配符路径、目录扫描、UTF-8/GBK 编码、采集间隔、手动采集、位点重置 |
| 采集引擎 | 无 Agent（SSH/SFTP）增量读取、只在完整行边界推进位点、日志轮转自动重采、采集审计记录 |
| 日志解析 | 多行堆栈合并（at/Caused by/缩进）、级别/线程/logger/时间戳识别、RAW 逐行模式兜底 |
| 异常分析 | 指纹聚合（异常类 + 归一化消息模板 + 顶层业务堆栈帧）、数字/UUID/IP/ID 脱敏归一 |
| 知识库 | 内置 26 类常见 Java 异常（NPE/OOM/死锁/连接池/Redis/Kafka/SSL…）的原因与修复方案，页面可自定义正则规则 |
| AI 分析 | 可插拔 OpenAI 兼容接口（OpenAI / 智谱 GLM / DeepSeek / 通义 / Ollama 本地模型），一键生成根因分析与修复方案，支持批量分析 |
| 修复闭环 | 待处理 / 已分析 / 已修复 / 已忽略，修复备注留痕，修复后复发自动重开 |
| 告警 | 窗口内事件数 / 新增异常类型阈值规则，Webhook + 邮件通知，冷却防轰炸，触发记录 |
| 仪表盘 | 今日异常、待处理组、已修复数、多日趋势图（ECharts）、Top 异常、各日志源健康 |
| 数据管理 | 事件保留期自动清理（每日凌晨）、H2 嵌入式库零依赖、只读 REST API 供外部集成 |

## 快速开始

要求：JDK 17+，Maven 3.6+（内网无外网时依赖已全部打进 fat-jar，前端资源本地化，离线可用）。

```bash
cd log-pilot
mvn package -DskipTests
java -jar target/log-pilot.jar
# 浏览器打开 http://localhost:18090
```

首次启动会自动：
1. 初始化 26 条内置知识库规则与默认设置；
2. 创建演示数据：本机节点 + `demo-logs/app-demo.log`（含 NPE、SQL 死锁、连接拒绝、超时等典型异常）；
3. 15 秒后自动开始采集，仪表盘即可看到聚合结果（正式使用时删除"演示应用日志"源即可）。

> 默认端口 18090（避免与常见服务冲突），修改：`--server.port=80` 或 `application.yml`。
> 数据存储在运行目录 `./data`（H2 文件库），Web 控制台：`/h2-console`（JDBC URL `jdbc:h2:file:./data/logpilot`，用户 sa，空密码）。

## 接入内网日志（三步）

1. **服务器** 页面 → 添加服务器：主机/端口/账号，密码或私钥认证 → 点"测试"验证连通（SSH 账号需要对日志目录有读权限）。
2. **日志源** 页面 → 添加日志源：选择服务器，填日志路径，例如：
   - `/var/log/myapp/*.log`（通配符）
   - `/app/logs/error.log`（单文件）
   - `/app/logs`（目录下所有 `.log`）
3. 等待首个采集周期（或点"采集"立即执行）→ **异常分析** 页面查看聚合结果与修复建议。

Windows 路径同样支持：`D:/logs/app/*.log`。

## 接入 AI（可选，随时开关）

**系统设置** → 填写并勾选启用，任何 OpenAI Chat Completions 协议兼容服务均可：

| 服务商 | Base URL | 模型示例 |
|---|---|---|
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` |
| 智谱 GLM | `https://open.bigmodel.cn/api/paas/v4` | `glm-4-flash` |
| DeepSeek | `https://api.deepseek.com/v1` | `deepseek-chat` |
| Ollama（本地） | `http://<内网机器>:11434/v1` | `qwen2.5:14b` |

保存后点"测试 AI 连通"。之后在异常详情页点 **🤖 AI 根因分析**，平台会把该异常组的脱敏模板、
顶层堆栈、最近一次原始日志发给模型，生成结构化报告（问题概述 / 根因分析 / 修复方案 / 预防措施）。
也可在异常列表页"批量 AI 分析"一次处理最多 20 个待处理组。

不接入 AI 也完全可用：内置知识库会覆盖绝大多数常见异常的修复建议。

## 告警配置

**告警** 页面 → 添加规则：
- 指标：窗口内异常事件数 / 窗口内新增异常类型；
- 通知渠道：Webhook（POST JSON，可直接对接企业微信/钉钉/飞书机器人网关）与邮件（系统设置中配 SMTP）；
- 冷却时间防止重复轰炸；触发历史可查。

## 配置项（application.yml）

```yaml
logpilot:
  crypto-key: "logpilot-please-change-me"   # 凭据加密密钥，部署时务必修改
  collect:
    ticker-seconds: 30        # 采集调度心跳
    max-chunk-bytes: 16777216 # 单文件单次最大读取（防大文件冲击）
    max-events-per-run: 5000  # 单次采集入库事件上限（防日志风暴）
  retention-days: 30          # 事件保留天数
  seed-demo: true             # 首次启动是否创建演示数据
```

## 只读 API（外部集成）

```
GET /api/health
GET /api/dashboard/summary            # 今日事件/待处理组/已修复等汇总
GET /api/dashboard/trend?days=7       # 每日异常趋势
GET /api/dashboard/top-groups?days=7&limit=10
GET /api/dashboard/recent-events?limit=20
GET /api/groups?status=OPEN&page=0    # 异常组
GET /api/events?groupId=1             # 异常事件
POST /sources/{id}/collect            # 手动触发采集
```

## 架构与目录

```
com.logpilot
├── domain/        10 个实体：ServerNode/LogSource/SourceFileOffset/ErrorEvent/ErrorGroup/
│                  KnowledgeRule/AlertRule/AlertRecord/CollectLog/SystemSetting
├── repo/          Spring Data JPA
├── service/
│   ├── SshService          SSH/SFTP 通道（连接测试/列目录/增量读块）
│   ├── CollectService      采集编排：文件解析→位点推进→解析→分析→告警
│   ├── LogParser           多行记录合并、字段提取、异常判定
│   ├── FingerprintUtil     指纹归一化（数字/UUID/IP/引号串→占位符）与顶层业务帧提取
│   ├── AnalyzeService      事件入库、聚合组 upsert、知识库关联、修复后复发重开
│   ├── KnowledgeService    内置 26 条知识规则（可扩展）
│   ├── AiService           OpenAI 兼容调用、结果落库
│   ├── AlertService        规则评估、Webhook/邮件发送、触发记录
│   ├── ScheduleService     心跳调度 + 每日数据清理
│   └── DashboardService    仪表盘聚合
├── web/           页面控制器 + REST API + 全局异常处理
└── config/        启动初始化（知识库种子、默认设置、演示数据）
resources/
├── templates/     9 个 Thymeleaf 页面 + 布局碎片
└── static/        本地化 Bootstrap 5 / ECharts / 自定义样式与脚本（离线可用）
```

技术栈：Spring Boot 3.2 / Java 17 / H2 / Spring Data JPA / JSch(mwiede) / Thymeleaf / Bootstrap 5 / ECharts。

## 日志格式兼容说明

- AUTO 模式：兼容 logback/log4j2 默认 pattern 及任何"时间戳开头"的格式，自动合并 `at ...`/`Caused by`/缩进行为同一记录；支持 `yyyy-MM-dd HH:mm:ss[.SSS]`（点/逗号毫秒）与 ISO `T` 分隔。
- RAW 模式：逐行处理，适合非标准格式（含 ERROR/Exception 关键词的行判为异常）。
- 编码：UTF-8 / GBK / GB18030 可按日志源配置。

## 安全说明（内网定位）

- 未内置登录鉴权，请勿暴露到公网；SSH 凭据使用 AES-256-GCM 加密存储（`logpilot.crypto-key` 可换密钥）。
- 服务器密码在页面上只写不改读（留空表示保持不变）。
- 建议内网部署后通过 Nginx 增加统一认证，或限制访问来源 IP。

## Roadmap（后续可扩展）

- 用户体系与操作审计、多租户
- 采集侧 Agent（长连接推送，降低 SSH 开销）
- 与 Jira/GitLab Issue 联动，一键建单
- 日志上下文回放（异常前后 N 行）
- 更丰富的统计维度（接口维度、版本维度对比）
