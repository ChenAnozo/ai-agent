# 阅读研究室 · yu-ai-agent

基于 Spring Boot、Spring AI、Vue 3 与 PostgreSQL/pgvector 的本地阅读研究应用。
支持 EPUB、PDF、DOC/DOCX、TXT、Markdown 导入、知识库问答、原文引用与协作研究。
阅读器源码、测试和官方研究模板均包含在此仓库中。

## 环境

- JDK 21（设置 `JAVA_HOME`）。后端自带 Maven Wrapper。
- Node.js 20.19+ 或 22+，npm。
- PostgreSQL 与匹配版本的 pgvector。
- 一个 OpenAI 兼容聊天服务的密钥，以及 DashScope 向量服务密钥。

## 配置

仓库不提供真实密钥或数据库密码。根目录 `.env.example` 仅列出变量，Spring Boot **不会自动加载该文件**。
在当前 PowerShell 会话设置环境变量，例如：

```powershell
$env:SPRING_AI_OPENAI_API_KEY = Read-Host '输入聊天服务密钥'
$env:SPRING_AI_DASHSCOPE_API_KEY = Read-Host '输入 DashScope 密钥'
$env:READING_DATABASE_URL = 'jdbc:postgresql://localhost:5432/reading'
$env:READING_DATABASE_USER = 'reading'
$env:READING_DATABASE_PASSWORD = Read-Host '输入数据库密码'
```

上面的输入不会作为密钥字面量保存在命令历史中。环境变量存在于当前会话及其子进程。
默认聊天接口为 `https://api.deepseek.com`；可设置 `SPRING_AI_OPENAI_BASE_URL` 与
`SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL` 使用其他兼容服务。
默认向量模型为 `qwen3.7-text-embedding`，维度为 1024；可用 `READING_EMBEDDING_MODEL` 覆盖模型。

## 启动后端

自行准备数据库的方式见 [数据库说明](docs/database.md)。在已设置环境变量的终端中运行：

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=reading'
```

Windows 也可使用项目内原生 PostgreSQL 脚本。首次运行：

```powershell
.\scripts\Start-Reading.ps1 -SetupDatabase
```

此方式会下载原生 PostgreSQL，在本机使用 Visual Studio C++ 工具构建 pgvector，并生成随机数据库密码。
后续可运行 `Start-Reading.bat`；从另一个窗口双击时需确保模型密钥已设置为用户环境变量。
脚本仅在项目忽略目录 `.reading-runtime/`、`.reading-data/` 中存储运行时和密码，不包含在发布仓库中。

## 启动前端

```powershell
cd yu-ai-agent-frontend
npm ci
npm run dev
```

默认前端 `http://localhost:5173`，后端 `http://localhost:8123/api`。
前端可在被忽略的 `.env.local` 中设置 `VITE_API_BASE_URL`，参照该目录的 `.env.example`。
**不要把密钥写入任何 `VITE_` 变量**，这些变量会进入浏览器代码。

## 验证

```powershell
# 根目录，使用测试占位配置，不需要真实模型密钥
.\mvnw.cmd test

# 前端目录
npm run build
node --test src/utils/researchTimeline.test.js src/utils/readingCitations.test.js
```

数据库集成测试及真实服务测试仅在显式配置相应环境变量后启用；普通测试不会请求真实模型。

## 目录

- `src/`：阅读器后端及测试。
- `yu-ai-agent-frontend/`：阅读器前端。
- `research-templates/official/`：随源码分发的官方模板。
- `research-skills/`：研究行为指导。
- `scripts/`：Windows 本地数据库与后端启动工具。

本项目默认用于本机，后端监听回环地址，不包含用户认证和面向公网部署的访问控制。
发布源码不等于开放本机服务。模型调用和文档向量化可能产生服务商费用。

项目来源与第三方测试素材见 [第三方说明](THIRD_PARTY_NOTICES.md)。
发布副本的处理范围与验证见 [发布检查记录](docs/public-release.md)。
