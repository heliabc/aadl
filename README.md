# 后端更新说明 - RAG 功能

## 概述

本次更新新增了 RAG（检索增强生成）功能，支持：
- 内置知识库（启动时自动加载）
- 用户上传知识库
- 安全分析报告自动加入知识库
- 语义搜索

## 新增依赖 (pom.xml)

```xml
<!-- Qdrant 向量数据库客户端 -->
<dependency>
  <groupId>io.qdrant</groupId>
  <artifactId>qdrant-client</artifactId>
  <version>1.12.0</version>
</dependency>

<!-- PDF解析 -->
<dependency>
  <groupId>org.apache.pdfbox</groupId>
  <artifactId>pdfbox</artifactId>
  <version>3.0.2</version>
</dependency>

<!-- OCR文字识别 -->
<dependency>
  <groupId>net.sourceforge.tess4j</groupId>
  <artifactId>tess4j</artifactId>
  <version>5.13.0</version>
</dependency>

<!-- PDF渲染（OCR用） -->
<dependency>
  <groupId>org.icepdf</groupId>
  <artifactId>icepdf-core</artifactId>
  <version>7.1.3</version>
</dependency>
```

## 新增配置 (application.yml)

```yaml
app:
  # 知识库目录
  knowledge-base-dir: ${app.data-dir}/knowledge-base   # 内置知识库
  user-knowledge-dir: ${app.data-dir}/user-knowledge   # 用户上传知识库
  
  # Embedding模型
  embed-model: mxbai-embed-large
  
  # Qdrant配置
  qdrant:
    host: localhost
    port: 6333
    api-key: ""          # 如需认证请填写
    collection: safety-knowledge
```

## 新增模块

```
src/main/java/com/nuaa/aadl/module/rag/
├── controller/
│   └── RagController.java      # REST API控制器
├── dto/
│   └── RagDtos.java             # DTO定义
└── service/
    ├── ChunkingService.java      # 文本分块
    ├── EmbeddingService.java     # 向量化服务
    ├── KnowledgeBaseService.java # 知识库管理
    ├── PdfParserService.java     # PDF解析+OCR
    ├── QdrantService.java        # 向量存储检索
    └── TextParserService.java    # 文本文件解析
```

## API接口

| 接口 | 方法 | 功能 |
|-----|------|------|
| `/api/rag/init` | POST | 手动触发内置知识库初始化 |
| `/api/rag/ingest` | POST | 用户上传文件到知识库 |
| `/api/rag/search` | GET | 语义搜索 |
| `/api/rag/ingest-report` | POST | 将确认的报告加入知识库 |
| `/api/rag/list` | GET | 列出已入库文件 |
| `/api/rag/count` | GET | 获取知识库chunk数量 |

## 使用说明

### 1. 启动前准备

```bash
# 1. 确保 Qdrant 已启动（默认 localhost:6333）
docker run -d -p 6333:6333 qdrant/qdrant

# 2. 确保 embedding 模型已下载
ollama pull mxbai-embed-large

# 3. （可选）放置内置知识库文件
# 将 PDF/TXT/MD 文件放入 data/knowledge-base/ 目录
```

### 2. 内置知识库

启动时自动从 `data/knowledge-base/` 目录加载文件并向量化存储。

### 3. 用户上传知识库

```bash
curl -X POST http://localhost:8081/api/rag/ingest \
  -F "file=@your-document.pdf"
```

### 4. 语义搜索

```bash
curl "http://localhost:8081/api/rag/search?q=安全分析&limit=5"
```

### 5. 将报告加入知识库

```bash
curl -X POST http://localhost:8081/api/rag/ingest-report \
  -H "Content-Type: application/json" \
  -d '{
    "conversationId": "xxx",
    "fmeaContent": "...",
    "ftaContent": "..."
  }'
```

## Metadata设计

每个chunk存储以下元数据：

| 字段 | 说明 |
|-----|------|
| `source` | 来源类型：`builtin`/`user`/`report` |
| `fileName` | 文件名 |
| `fileId` | 文件ID（用户上传时） |
| `chunkIndex` | chunk序号 |
| `pageNumber` | 原始页码 |
| `docType` | 文档类型 |
| `createTime` | 创建时间 |

## ChatService集成

安全分析模块已集成RAG：
- 用户发送消息时，自动检索知识库
- 检索结果追加到 `[Relevant Knowledge Base]` 上下文
- 帮助LLM生成更准确的分析

## 目录结构

```
data/
├── knowledge-base/     # 内置知识库（启动时自动导入）
│   ├── *.pdf
│   └── *.txt / *.md
├── user-knowledge/    # 用户上传的知识库
│   └── *.pdf / *.txt
└── app.db             # SQLite数据库
```

## 注意事项

1. 首次启动会较慢，需要解析并向量化所有内置知识库文件
2. OCR需要Tesseract数据文件，设置环境变量 `TESSDATA_PREFIX`
3. 如果Qdrant未启动，应用仍可运行，但RAG功能不可用
