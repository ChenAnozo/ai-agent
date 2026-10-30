# 数据库准备

阅读器需要 PostgreSQL 与 pgvector。可使用已有安装，也可使用 `scripts/Start-Reading.ps1 -SetupDatabase`。

## 已有数据库

以数据库管理员连接 PostgreSQL，使用自行生成的密码创建应用账号和数据库：

```sql
CREATE ROLE reading LOGIN PASSWORD 'REPLACE_WITH_YOUR_OWN_PASSWORD';
CREATE DATABASE reading OWNER reading;
```

连接 `reading` 数据库后启用扩展：

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

设置 `READING_DATABASE_URL`、`READING_DATABASE_USER`、`READING_DATABASE_PASSWORD` 后启动应用。
Flyway 管理 `reading` schema 的迁移。不要把数据库备份、导入文档或密码文件加入 Git。

## 项目内原生 Windows 数据库

首次初始化需要 Windows PowerShell、下载网络和 Visual Studio C++ 构建工具。
脚本将运行时存入 `.reading-runtime/`，数据库与随机密码存入 `.reading-data/`，不注册系统服务。
本地数据库监听 `127.0.0.1:5432`；如已有服务占用此端口，请使用已有服务并按上一节配置。

```powershell
# 首次初始化并启动后端
.\scripts\Start-Reading.ps1 -SetupDatabase
# 后续启动
.\scripts\Start-Reading.ps1
# 停止项目内数据库
.\scripts\Start-ReadingDatabase.ps1 -Stop
```

原生脚本使用 PostgreSQL 17.11 和 pgvector 0.8.7；这些版本沿用项目配置。
在其他系统上自行安装兼容的 PostgreSQL 和 pgvector 后使用 Maven Wrapper 启动应用。
