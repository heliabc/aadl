# 当前实现接入“失效项点击跳转”说明

## 目标

在当前项目实现中，为安全分析结果增加可追溯跳转能力：

- FMEA 表格中的每条失效模式可以对应到文件或 AADL 构件。
- FTA 故障树中的节点可以对应到文件或 AADL 构件。
- 前端点击定位入口后，可以跳转到 AADL 模型行号、上传文件片段，或选中构件树节点。

当前仓库主要是后端实现，前端代码不在本仓库中。因此本文档把后端改造点写到具体 Java 文件，并给出前端需要对接的数据结构和交互逻辑。

## 当前后端现状

当前安全分析链路大致是：

```text
ChatService.streamSafety()
        |
        v
SafetyAnalysisChain.execute()
        |
        v
步骤 1-5: 结构、功能、失效、影响原因、风险分析
        |
        v
generateFmea()
        |
        v
generateFta()
        |
        v
persistSafetyDocs()
        |
        v
SSE 推送 fmea_update / fta_ready / report_ready / done
```

相关文件：

| 位置 | 当前作用 |
| --- | --- |
| `src/main/java/com/nuaa/aadl/chat/ChatService.java` | 安全分析入口，构造附件上下文和 AADL 上下文 |
| `src/main/java/com/nuaa/aadl/module/safety/service/chain/SafetyAnalysisChain.java` | 安全分析主链路，生成 FMEA/FTA 并推送 SSE |
| `src/main/java/com/nuaa/aadl/module/safety/service/chain/SafetyAnalysisState.java` | 安全分析过程状态 |
| `src/main/java/com/nuaa/aadl/module/safety/dto/FmeaRow.java` | FMEA 行结构 |
| `src/main/java/com/nuaa/aadl/module/safety/dto/FtaNode.java` | FTA 节点结构 |
| `src/main/java/com/nuaa/aadl/shared/file/FileService.java` | 文件上传、附件内容读取 |
| `src/main/java/com/nuaa/aadl/shared/file/FileController.java` | 文件上传接口 |
| `src/main/java/com/nuaa/aadl/module/aadl/AadlController.java` | AADL 下载/生成/导入接口 |

当前缺口：

1. `FmeaRow` 和 `FtaNode` 没有来源引用字段。
2. `ChatService.streamSafety()` 没有把 `attachmentIds` 传入 `SafetyAnalysisChain`。
3. `FileService` 可以读取附件内容构造 prompt，但没有公开“按 fileId 获取文件内容和行号片段”的能力。
4. `FileController` 没有文件预览接口。
5. 前端目前只能展示失效文本，无法知道点击后应该跳到哪里。

## 推荐接入方式

不要把跳转地址写进 `failure` 文本，也不要让大模型直接输出前端路由。

推荐做法：

```text
FMEA/FTA 仍然由当前链路生成
        |
        v
后端新增 SafetyReferenceResolver
        |
        v
根据 component/failure/label 匹配 AADL 构件和上传文件
        |
        v
给 FmeaRow/FtaNode 增加 sourceRefs
        |
        v
前端点击 sourceRefs 执行跳转
```

## 后端修改一：新增 SourceReference DTO

新增文件：

```text
src/main/java/com/nuaa/aadl/module/safety/dto/SourceReference.java
```

建议结构：

```java
package com.nuaa.aadl.module.safety.dto;

public record SourceReference(
    String targetType,
    String fileId,
    String fileName,
    String componentName,
    Integer lineStart,
    Integer lineEnd,
    String snippet,
    String routeHint
) {}
```

字段含义：

| 字段 | 说明 |
| --- | --- |
| `targetType` | 目标类型：`aadl_model`、`uploaded_file`、`component` |
| `fileId` | 上传文件 id，跳上传文件时使用 |
| `fileName` | 展示用文件名 |
| `componentName` | AADL 构件名 |
| `lineStart` | 起始行号 |
| `lineEnd` | 结束行号 |
| `snippet` | 命中的上下文片段 |
| `routeHint` | 前端跳转提示：`aadl-editor`、`file-preview`、`component-tree` |

## 后端修改二：扩展 FmeaRow

修改：

```text
src/main/java/com/nuaa/aadl/module/safety/dto/FmeaRow.java
```

增加字段：

```java
List<SourceReference> sourceRefs
```

为了减少当前 `new FmeaRow(...)` 调用点的改动，建议给 record 增加一个兼容旧参数的构造方法。

示意：

```java
public record FmeaRow(
  String component,
  String function,
  @NotBlank String failure,
  @NotBlank String effect,
  String cause,
  @Min(1) @Max(10) int severity,
  @Min(1) @Max(10) int occurrence,
  @Min(1) @Max(10) int detection,
  Integer rpn,
  String recommendedActions,
  Integer newRpn,
  String optimizationMethod,
  String optimizationEffect,
  List<SourceReference> sourceRefs
) {
  public FmeaRow {
    if (rpn == null || rpn <= 0) {
      rpn = severity * occurrence * detection;
    }
    if (newRpn == null || newRpn <= 0) {
      newRpn = rpn;
    }
    if (sourceRefs == null) {
      sourceRefs = List.of();
    }
  }

  public FmeaRow(
    String component,
    String function,
    String failure,
    String effect,
    String cause,
    int severity,
    int occurrence,
    int detection,
    Integer rpn,
    String recommendedActions,
    Integer newRpn,
    String optimizationMethod,
    String optimizationEffect
  ) {
    this(
      component,
      function,
      failure,
      effect,
      cause,
      severity,
      occurrence,
      detection,
      rpn,
      recommendedActions,
      newRpn,
      optimizationMethod,
      optimizationEffect,
      List.of()
    );
  }
}
```

这样当前 `SafetyAnalysisChain` 里已有的 FMEA 构造逻辑可以先不大改，后面由 resolver 统一补 `sourceRefs`。

## 后端修改三：扩展 FtaNode

修改：

```text
src/main/java/com/nuaa/aadl/module/safety/dto/FtaNode.java
```

增加字段：

```java
List<SourceReference> sourceRefs
```

同样建议保留一个旧参数兼容构造方法，否则 `new FtaNode(...)` 的调用点都要同步改。

示意：

```java
public record FtaNode(
    @NotBlank String id,
    @NotBlank String label,
    FtaNodeType type,
    GateType parentGate,
    Integer voteThreshold,
    String inhibitCondition,
    @Valid List<FtaNode> children,
    List<SourceReference> sourceRefs
) {
    public FtaNode {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString().substring(0, 8);
        }
        if (type == null) {
            type = FtaNodeType.INTERMEDIATE;
        }
        if (children == null) {
            children = List.of();
        }
        if (sourceRefs == null) {
            sourceRefs = List.of();
        }
        if (parentGate == GateType.VOTE) {
            if (voteThreshold == null || voteThreshold < 1) {
                voteThreshold = 2;
            }
        } else {
            voteThreshold = null;
        }
        if (parentGate != GateType.INHIBIT) {
            inhibitCondition = null;
        }
    }

    public FtaNode(
        String id,
        String label,
        FtaNodeType type,
        GateType parentGate,
        Integer voteThreshold,
        String inhibitCondition,
        List<FtaNode> children
    ) {
        this(id, label, type, parentGate, voteThreshold, inhibitCondition, children, List.of());
    }
}
```

注意：`leaf()`、`basic()`、`gate()` 等静态方法也要确认是否需要传 `List.of()`，如果保留旧参数构造方法，它们可以基本不变。

## 后端修改四：让安全分析链路拿到 attachmentIds

当前：

```text
ChatService.streamSafety()
```

会调用：

```java
safetyAnalysisChain.execute(
  request.conversationId(),
  request.message(),
  safetyContext,
  aadlContext,
  emitter,
  reply -> ...
);
```

问题是 `SafetyAnalysisChain` 只拿到了附件拼接后的文本，没有拿到原始 `attachmentIds`，因此后面无法知道要跳哪个文件。

建议修改 `SafetyAnalysisChain.execute()` 签名：

```java
public void execute(
    String conversationId,
    String userMessage,
    String attachmentContext,
    String aadlContext,
    List<String> attachmentIds,
    SseEmitter emitter,
    Consumer<String> onAssistantReply
)
```

然后 `ChatService.streamSafety()` 改为：

```java
safetyAnalysisChain.execute(
  request.conversationId(),
  request.message(),
  safetyContext,
  aadlContext,
  request.attachmentIds(),
  emitter,
  reply -> messageRepository.appendMessage(...)
);
```

同时修改 `SafetyAnalysisState`，增加：

```java
private final List<String> attachmentIds;

public List<String> getAttachmentIds() {
    return attachmentIds;
}
```

构造 `SafetyAnalysisState` 时传入 `attachmentIds`。

## 后端修改五：新增 SafetyReferenceResolver

新增：

```text
src/main/java/com/nuaa/aadl/module/safety/service/SafetyReferenceResolver.java
```

这个类负责把分析结果映射回文件或构件。

建议职责：

```java
@Service
public class SafetyReferenceResolver {

  public List<FmeaRow> attachFmeaReferences(
      List<FmeaRow> rows,
      String aadlContext,
      List<String> attachmentIds
  ) {
      // 1. 解析 AADL 构件索引
      // 2. 解析上传文件索引
      // 3. 给每个 FmeaRow 补 sourceRefs
  }

  public FtaDoc attachFtaReferences(
      FtaDoc ftaDoc,
      List<FmeaRow> fmeaRows,
      String aadlContext,
      List<String> attachmentIds
  ) {
      // 递归遍历 FtaNode，给节点补 sourceRefs
  }
}
```

### AADL 构件匹配规则

先按行扫描 `aadlContext`，识别：

```text
system Xxx
system implementation Xxx.impl
process Xxx
process implementation Xxx.impl
thread Xxx
thread implementation Xxx.impl
device Xxx
device implementation Xxx.impl
data Xxx
bus Xxx
subcomponents 中的 instance_name
```

建立索引：

```text
normalizedComponentName -> SourceReference
```

匹配优先级：

1. `row.component()` 完全匹配。
2. 去掉 `.impl` 后匹配。
3. 忽略大小写匹配。
4. `A~B`、`_1~5` 这种范围表达拆开匹配。
5. 如果 FTA 节点 label 包含某个 FMEA 的 failure，则复用对应 FMEA 的 `sourceRefs`。

### 上传文件匹配规则

当前 `FileService` 的 `getFilesInRequestedOrder()` 和 `readStoredFileContent()` 是私有方法。为了 resolver 能读取文件，建议增加公开方法：

```java
public List<StoredFileRecord> getFilesByIdsInOrder(List<String> fileIds)

public Optional<String> readFileContent(String fileId)
```

然后 resolver 根据 `attachmentIds` 读取文件内容，按行搜索：

1. `component`
2. `failure`
3. `cause`
4. `effect`

命中后生成：

```java
new SourceReference(
  "uploaded_file",
  file.id(),
  file.originalName(),
  row.component(),
  lineStart,
  lineEnd,
  snippet,
  "file-preview"
)
```

## 后端修改六：在 SafetyAnalysisChain 中接入 resolver

修改：

```text
src/main/java/com/nuaa/aadl/module/safety/service/chain/SafetyAnalysisChain.java
```

### 1. 构造函数注入

增加字段：

```java
private final SafetyReferenceResolver referenceResolver;
```

构造函数增加参数。

### 2. 增量 FMEA 推送前补引用

当前 `generateFmea()` 中生成 batch 后会立即发送：

```java
state.getEmitter().send(SseEmitter.event().name("fmea_update").data(
    Map.of("mode", "append", "rows", batchRows)
));
```

如果希望前端在生成过程中就可以点击跳转，则应该在 append 前补引用：

```java
batchRows = referenceResolver.attachFmeaReferences(
    batchRows,
    state.getAadlContext(),
    state.getAttachmentIds()
);
```

然后再：

```java
state.addFmeaRows(batchRows);
send fmea_update
```

### 3. 最终持久化前再统一补一次

在 `finalizeAnalysis()` 中：

```java
FmeaReport fmeaReport = state.buildFmeaReport();
FtaDoc ftaDoc = state.getFtaDoc();
```

后面加：

```java
List<FmeaRow> rowsWithRefs = referenceResolver.attachFmeaReferences(
    fmeaReport.rows(),
    state.getAadlContext(),
    state.getAttachmentIds()
);
fmeaReport = new FmeaReport(fmeaReport.title(), rowsWithRefs);

ftaDoc = referenceResolver.attachFtaReferences(
    ftaDoc,
    rowsWithRefs,
    state.getAadlContext(),
    state.getAttachmentIds()
);
```

再执行：

```java
safetyDocService.persistSafetyDocs(conversationId, fmeaReport, ftaDoc);
```

这样以下数据都会带 `sourceRefs`：

1. SSE `fmea_update`
2. SSE `fta_ready`
3. SSE `report_ready`
4. SSE `done.moduleData`
5. GET `/api/modules/safety/fmea`
6. GET `/api/modules/safety/fta`

## 后端修改七：增加文件预览接口

当前 `FileController` 只有：

```text
POST /api/files/upload
GET /api/files/getPath
```

如果前端要跳转到上传文件，需要增加文件内容接口。

建议新增：

```text
GET /api/files/{fileId}
```

返回：

```json
{
  "id": "file_xxx",
  "name": "requirement.aadl",
  "mime": "text/plain",
  "content": "...",
  "lines": [
    {"line": 1, "text": "..."},
    {"line": 2, "text": "..."}
  ]
}
```

也可以先只返回：

```json
{
  "id": "file_xxx",
  "name": "requirement.aadl",
  "content": "..."
}
```

前端自己 split 成行。

## 后端修改八：AADL 模型跳转接口

当前已有：

```text
GET /api/modules/aadl/download?conversationId=xxx
```

它返回当前 AADL 文本。前端可以复用这个接口加载 AADL 内容，然后根据 `lineStart` 滚动。

如果前端已有 AADL 编辑器状态，则不需要新增接口。

如果没有，建议新增一个更语义化的接口：

```text
GET /api/modules/aadl?conversationId=xxx
```

返回：

```json
{
  "conversationId": "xxx",
  "content": "...",
  "updatedAt": "..."
}
```

这不是必须项，第一版可以先复用 download 接口。

## 前端修改一：更新类型定义

新增类型：

```ts
export type SourceReference = {
  targetType: 'aadl_model' | 'uploaded_file' | 'component'
  fileId?: string | null
  fileName?: string | null
  componentName?: string | null
  lineStart?: number | null
  lineEnd?: number | null
  snippet?: string | null
  routeHint?: 'aadl-editor' | 'file-preview' | 'component-tree' | string | null
}
```

扩展 FMEA：

```ts
export type FmeaRow = {
  component: string
  function: string
  failure: string
  effect: string
  cause: string
  severity: number
  occurrence: number
  detection: number
  rpn: number
  recommendedActions: string
  newRpn: number
  optimizationMethod: string
  optimizationEffect: string
  sourceRefs?: SourceReference[]
}
```

扩展 FTA：

```ts
export type FtaNode = {
  id: string
  label: string
  type: string
  parentGate?: string | null
  voteThreshold?: number | null
  inhibitCondition?: string | null
  children: FtaNode[]
  sourceRefs?: SourceReference[]
}
```

## 前端修改二：FMEA 表格增加“定位”列

建议在 FMEA 表格中增加一列：

```text
定位
```

渲染逻辑：

```tsx
function SourceRefButtons({ refs }: { refs?: SourceReference[] }) {
  if (!refs || refs.length === 0) {
    return <span className="muted">未定位</span>
  }

  return (
    <div className="source-ref-list">
      {refs.map((ref, index) => (
        <button key={index} onClick={() => jumpToSource(ref)}>
          {formatSourceRefLabel(ref)}
        </button>
      ))}
    </div>
  )
}
```

展示文案建议：

```ts
function formatSourceRefLabel(ref: SourceReference) {
  if (ref.targetType === 'aadl_model') {
    return ref.componentName
      ? `构件 ${ref.componentName}`
      : `AADL:${ref.lineStart ?? ''}`
  }

  if (ref.targetType === 'uploaded_file') {
    const line = ref.lineStart ? `:${ref.lineStart}` : ''
    return `${ref.fileName ?? '文件'}${line}`
  }

  if (ref.targetType === 'component') {
    return `构件 ${ref.componentName ?? ''}`
  }

  return '定位'
}
```

## 前端修改三：FTA 节点详情增加定位按钮

不要让整个 FTA 节点点击后直接跳转，否则会影响节点展开、折叠、选中等交互。

建议：

1. 点击 FTA 节点仍然打开节点详情。
2. 在节点详情里展示 `sourceRefs`。
3. 用户点击“定位”按钮后再跳转。

示例：

```tsx
function FtaNodeDetail({ node }: { node: FtaNode }) {
  return (
    <aside>
      <h3>{node.label}</h3>
      <SourceRefButtons refs={node.sourceRefs} />
    </aside>
  )
}
```

## 前端修改四：实现 jumpToSource

核心跳转逻辑：

```ts
async function jumpToSource(ref: SourceReference) {
  if (ref.targetType === 'aadl_model' || ref.routeHint === 'aadl-editor') {
    openAadlPanel()
    await ensureAadlLoaded()

    if (ref.lineStart) {
      scrollAadlEditorToLine(ref.lineStart)
    }

    if (ref.componentName) {
      highlightAadlComponent(ref.componentName)
    }
    return
  }

  if (ref.targetType === 'uploaded_file' || ref.routeHint === 'file-preview') {
    if (!ref.fileId) return

    openFilePreviewPanel()
    await loadFileContent(ref.fileId)

    if (ref.lineStart) {
      scrollFilePreviewToLine(ref.lineStart)
    }

    if (ref.snippet) {
      highlightSnippet(ref.snippet)
    }
    return
  }

  if (ref.targetType === 'component' || ref.routeHint === 'component-tree') {
    openComponentTreePanel()
    if (ref.componentName) {
      selectComponentNode(ref.componentName)
    }
  }
}
```

这些函数根据前端现有状态管理实现：

| 函数 | 作用 |
| --- | --- |
| `openAadlPanel()` | 切到 AADL 模型面板 |
| `ensureAadlLoaded()` | 确保当前会话 AADL 内容已加载 |
| `scrollAadlEditorToLine()` | 编辑器滚动到指定行 |
| `highlightAadlComponent()` | 高亮构件名 |
| `openFilePreviewPanel()` | 打开文件预览 |
| `loadFileContent()` | 请求 `GET /api/files/{fileId}` |
| `scrollFilePreviewToLine()` | 文件预览滚动到指定行 |
| `selectComponentNode()` | 选中构件树节点 |

## 前端修改五：SSE 事件处理

当前后端会推：

```text
fmea_update
fta_ready
report_ready
done
```

改造后这些事件的数据结构不需要换，只是 `rows` 和 `fta` 内部多了 `sourceRefs`。

前端处理方式：

```ts
if (eventName === 'fmea_update') {
  const { mode, rows } = data
  // rows: FmeaRow[]，每行可能带 sourceRefs
}

if (eventName === 'fta_ready') {
  const { fta } = data
  // fta.root.children[*].sourceRefs
}

if (eventName === 'report_ready') {
  const { fmeaReport, fta } = data
}

if (eventName === 'done') {
  const { moduleData } = data
  // moduleData.fmeaReport / moduleData.fta
}
```

注意：前端要兼容旧数据。

```ts
const refs = row.sourceRefs ?? []
```

## 第一版最小可行实现

建议第一版只做 AADL 构件跳转，不做上传文件跳转。

原因：

1. 当前会话已经有 `aadlContext`。
2. FMEA 行已经有 `component` 字段。
3. AADL 模型跳转最符合“失效对应构件”的诉求。
4. 不需要马上新增文件预览接口。

第一版范围：

后端：

1. 新增 `SourceReference`。
2. `FmeaRow` 增加 `sourceRefs`。
3. `FtaNode` 增加 `sourceRefs`。
4. 新增 `SafetyReferenceResolver`，只解析 `aadlContext`。
5. 在 `SafetyAnalysisChain.finalizeAnalysis()` 持久化前补引用。

前端：

1. FMEA 表格增加“定位”列。
2. 点击定位切到 AADL 面板。
3. 滚动到 `lineStart`。
4. 高亮 `componentName`。

第二版再做：

1. 传递 `attachmentIds` 到安全分析链。
2. `FileService` 增加文件读取方法。
3. `FileController` 增加文件预览接口。
4. 前端实现文件预览跳转。

## 推荐开发顺序

### 阶段一：后端字段扩展

1. 新增 `SourceReference.java`。
2. 扩展 `FmeaRow.java`。
3. 扩展 `FtaNode.java`。
4. 保留旧构造方法，避免大面积改 `new FmeaRow(...)` 和 `new FtaNode(...)`。
5. 跑 `mvn compile`。

### 阶段二：后端 AADL 引用解析

1. 新增 `SafetyReferenceResolver.java`。
2. 实现 AADL 构件索引。
3. 实现 FMEA 行引用补全。
4. 实现 FTA 节点递归引用补全。
5. 接入 `SafetyAnalysisChain.finalizeAnalysis()`。

### 阶段三：前端 FMEA 跳转

1. 更新 `FmeaRow` 类型。
2. FMEA 表格增加定位列。
3. 实现 `jumpToSource()` 的 AADL 跳转。

### 阶段四：前端 FTA 跳转

1. 更新 `FtaNode` 类型。
2. 节点详情显示定位按钮。
3. 复用 `jumpToSource()`。

### 阶段五：上传文件跳转

1. 后端传递 `attachmentIds`。
2. 后端增加文件读取接口。
3. resolver 增加上传文件匹配。
4. 前端增加文件预览面板或复用已有预览组件。

## 需要注意的兼容问题

1. 已保存的旧 FMEA/FTA JSON 没有 `sourceRefs` 字段。
   - DTO 构造中要把 `null` 归一为 `List.of()`。
   - 前端要用 `row.sourceRefs ?? []`。

2. 增加 record 字段会影响 Jackson 反序列化。
   - 如果旧 JSON 没有 `sourceRefs`，compact constructor 中处理 null 即可。

3. `FtaNode` 是递归结构。
   - 补引用时不要原地修改不可变 record。
   - 推荐递归创建新的 `FtaNode`。

4. 增量 FMEA 和最终 FMEA 都要考虑。
   - 第一版可以只保证最终 `report_ready` 和 GET 接口有 `sourceRefs`。
   - 如果前端生成过程中也允许点击，则 `fmea_update append` 也要补引用。

5. 大模型不要负责输出行号。
   - 行号由后端从 AADL/文件内容中解析。

## 最终接口数据示例

FMEA：

```json
{
  "component": "Cold_start_program",
  "function": "完成冷启动初始化",
  "failure": "冷启动初始化失败",
  "effect": "系统无法进入正常点火控制流程",
  "cause": "初始化状态、参数或通信条件未满足",
  "severity": 8,
  "occurrence": 4,
  "detection": 5,
  "rpn": 160,
  "recommendedActions": "补充冷启动状态检测和初始化失败处理逻辑",
  "newRpn": 80,
  "optimizationMethod": "增加初始化完成标志、错误传播和超时监测",
  "optimizationEffect": "降低冷启动失败不可探测风险",
  "sourceRefs": [
    {
      "targetType": "aadl_model",
      "fileId": null,
      "fileName": "current-aadl-model.aadl",
      "componentName": "Cold_start_program",
      "lineStart": 86,
      "lineEnd": 95,
      "snippet": "thread Cold_start_program ...",
      "routeHint": "aadl-editor"
    }
  ]
}
```

FTA：

```json
{
  "id": "n1",
  "label": "冷启动初始化失败",
  "type": "BASIC",
  "parentGate": "OR",
  "children": [],
  "sourceRefs": [
    {
      "targetType": "aadl_model",
      "componentName": "Cold_start_program",
      "lineStart": 86,
      "lineEnd": 95,
      "routeHint": "aadl-editor"
    }
  ]
}
```

## 建议结论

在当前实现中，最合适的接入点是：

```text
SafetyAnalysisChain.finalizeAnalysis()
```

最合适的数据承载位置是：

```text
FmeaRow.sourceRefs
FtaNode.sourceRefs
```

第一版建议只做：

```text
失效模式 -> AADL 构件 -> AADL 行号跳转
```

第二版再扩展：

```text
失效模式 -> 上传文件片段 -> 文件预览跳转
```

这样改动范围可控，前后端职责清晰，也不会破坏当前安全分析生成链路。

