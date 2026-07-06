# 安全分析失效项跳转方案

## 背景

安全分析结果中会产生 FMEA 失效模式、FTA 故障树节点等内容。当前这些内容主要是文本结果，例如组件名、失效模式、影响、原因、风险评分等。新的改进要求是：

> 失效部分要能对应到文件或者构件，点击后可以跳转。

这个要求是合理的，但不建议把跳转地址直接写进“失效模式”文本里。更合理的做法是：后端为每条失效结果补充结构化的来源引用，前端点击失效项时根据引用定位到对应文件、AADL 模型行号或构件节点。

## 需求理解

“失效部分点击跳转”实际包含三类跳转目标：

1. 跳转到上传文件
   - 例如需求文件、AADL 文件、历史失效数据文件。
   - 点击后打开文件预览，并滚动到相关行或相关片段。

2. 跳转到 AADL 构件
   - 例如 `Normal_program`、`Electric_detonator_device_1`、`Attitude_rocket_ignition_task`。
   - 点击后切到 AADL 模型视图，定位并高亮对应构件定义或子构件实例。

3. 跳转到前端构件树或架构图节点
   - 如果前端有系统结构树、AADL 构件树、架构图，则点击后选中对应节点。
   - 这种跳转依赖前端是否已有构件树/图谱能力。

## 是否应该这样做

应该做，但要注意边界：

1. 失效项本身不是文件，也不是构件，它是分析结论。
2. 一个失效项可能关联多个来源，例如：
   - 一个 AADL 构件定义。
   - 一个需求文件片段。
   - 一个历史失效案例。
3. 所以不要把跳转设计成单一 URL，而应该设计成 `sourceRefs` 引用数组。

推荐效果：

```text
失效模式：冷启动初始化失败
对应构件：Cold_start_program  [点击跳转]
来源文件：requirement.aadl:42  [点击跳转]
```

或者在表格中增加一列：

```text
定位
[构件 Cold_start_program] [文件 requirement.aadl:42]
```

## 总体方案

核心思路：

```text
安全分析生成 FMEA/FTA
        |
        v
后端根据 component/failure/label 做引用解析
        |
        v
给 FMEA 行和 FTA 节点补充 sourceRefs
        |
        v
SSE 推送和 GET 接口都返回带 sourceRefs 的结构化数据
        |
        v
前端点击 sourceRefs，跳转到文件、AADL 编辑器或构件树
```

## 推荐数据结构

新增一个通用引用对象，例如 `SourceReference`：

```java
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

字段说明：

| 字段 | 含义 |
| --- | --- |
| `targetType` | 跳转目标类型，例如 `aadl_model`、`uploaded_file`、`component` |
| `fileId` | 上传文件 id，如果跳转到上传文件则使用 |
| `fileName` | 展示用文件名 |
| `componentName` | 对应的 AADL 构件名 |
| `lineStart` | 起始行号 |
| `lineEnd` | 结束行号 |
| `snippet` | 命中的文本片段，用于 tooltip 或预览 |
| `routeHint` | 前端跳转提示，例如 `aadl-editor`、`file-preview`、`component-tree` |

示例 JSON：

```json
{
  "targetType": "aadl_model",
  "fileId": null,
  "fileName": "current-aadl-model.aadl",
  "componentName": "Cold_start_program",
  "lineStart": 86,
  "lineEnd": 95,
  "snippet": "thread Cold_start_program ... end Cold_start_program;",
  "routeHint": "aadl-editor"
}
```

## 后端应该放在哪里

### 1. DTO 层

建议扩展：

- `src/main/java/com/nuaa/aadl/module/safety/dto/FmeaRow.java`
- `src/main/java/com/nuaa/aadl/module/safety/dto/FtaNode.java`

FMEA 每一行加：

```java
List<SourceReference> sourceRefs
```

FTA 每个节点加：

```java
List<SourceReference> sourceRefs
```

原因：

1. FMEA 表格中的失效模式需要点击跳转。
2. FTA 故障树中的基本事件、中间事件也可能需要点击跳转。
3. 结果持久化时会保存整段 JSON，扩展字段后刷新页面也不会丢失跳转信息。

### 2. 引用解析服务

建议新增服务：

```text
src/main/java/com/nuaa/aadl/module/safety/service/SafetyReferenceResolver.java
```

职责：

1. 从当前 AADL 模型中解析构件索引。
2. 从上传附件中解析文件片段索引。
3. 根据 FMEA 的 `component`、`failure` 匹配引用。
4. 根据 FTA 的 `label` 匹配引用。
5. 给 FMEA/FTA 补充 `sourceRefs`。

### 3. 安全分析链路

推荐接入点：

```text
SafetyAnalysisChain.finalizeAnalysis()
```

具体位置应该在：

```text
生成 FMEA
生成 FTA
构造 FmeaReport / FtaDoc
补充 sourceRefs
持久化
SSE 推送给前端
```

也就是在持久化之前统一处理。

这样做的好处：

1. SSE 实时推送的数据里有跳转信息。
2. GET `/api/modules/safety/fmea` 返回的数据里也有跳转信息。
3. GET `/api/modules/safety/fta` 返回的数据里也有跳转信息。
4. 前端不用额外再请求一次“定位接口”。

## 后端怎么解析跳转目标

### AADL 构件索引

对 AADL 文本按行扫描，识别常见构件定义：

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
subcomponents
  instance_name: component_type;
```

建立索引：

```text
componentName -> SourceReference
```

示例：

```json
{
  "Cold_start_program": {
    "targetType": "aadl_model",
    "componentName": "Cold_start_program",
    "lineStart": 86,
    "lineEnd": 95,
    "routeHint": "aadl-editor"
  }
}
```

匹配优先级建议：

1. 完全匹配 `component` 字段。
2. 去掉 `.impl` 后匹配。
3. 忽略大小写匹配。
4. 对 `Electric_detonator_device_1~5` 这种范围表达，拆成多个构件匹配。
5. 如果没有命中构件，再用 `failure` 文本关键词在附件中搜索。

### 上传文件索引

文件服务现在已经有上传文件 id 和原始文件名。建议在引用解析时读取本轮附件：

```text
attachmentIds -> StoredFileRecord -> 文件内容
```

然后做轻量匹配：

1. 用 `component` 名称搜索。
2. 用 `failure` 中的核心关键词搜索。
3. 用 `cause` 或 `effect` 中的关键词搜索。

命中后返回：

```json
{
  "targetType": "uploaded_file",
  "fileId": "file_xxxxxx",
  "fileName": "requirement.aadl",
  "lineStart": 42,
  "lineEnd": 45,
  "snippet": "相关文本片段",
  "routeHint": "file-preview"
}
```

## 前端怎么跳转

前端不应该从失效文本里解析链接，而应该读取 `sourceRefs`。

点击逻辑：

```ts
function handleSourceRefClick(ref) {
  if (ref.targetType === "aadl_model") {
    openAadlPanel();
    scrollAadlEditorToLine(ref.lineStart);
    highlightAadlComponent(ref.componentName);
    return;
  }

  if (ref.targetType === "uploaded_file") {
    openFilePreview(ref.fileId);
    scrollFilePreviewToLine(ref.lineStart);
    highlightSnippet(ref.snippet);
    return;
  }

  if (ref.targetType === "component") {
    openComponentTree();
    selectComponent(ref.componentName);
  }
}
```

如果前端目前没有文件预览或 AADL 编辑器定位能力，可以分阶段做：

第一阶段：

- 点击后切到 AADL 模型面板。
- 显示构件名。
- 滚动到行号。

第二阶段：

- 支持上传文件预览。
- 支持行号高亮。

第三阶段：

- 支持构件树/架构图节点选中。

## 页面交互建议

### FMEA 表格

建议在 FMEA 表格增加一列：

```text
定位
```

每行展示：

```text
[构件 Cold_start_program] [文件 requirement.aadl:42]
```

如果引用为空，展示：

```text
未定位
```

### FTA 故障树

建议在节点上增加点击区域：

1. 点击节点本身：仍然展示节点详情。
2. 点击节点详情里的“定位”按钮：跳转到对应引用。

不要让整个 FTA 节点直接跳走，否则会影响故障树展开、折叠、选中等交互。

## 接口返回示例

FMEA 行示例：

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

FTA 节点示例：

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

## 为什么不要只靠大模型输出跳转

不建议让大模型直接输出文件行号，原因：

1. 附件内容可能被截断，大模型看到的不是完整文件。
2. 行号容易错。
3. 构件名可能被模型改写。
4. 前端跳转需要稳定字段，而不是自然语言。

更可靠的方式是：

```text
大模型负责生成分析结论
后端解析器负责把结论映射回文件和构件
前端负责根据结构化引用跳转
```

## 实施步骤

### 第一步：定义引用 DTO

新增：

```text
SourceReference.java
```

扩展：

```text
FmeaRow.java
FtaNode.java
```

### 第二步：实现 AADL 构件解析器

新增：

```text
SafetyReferenceResolver.java
```

先只做 AADL 文本行号匹配，解决最核心的“失效模式 -> 构件”跳转。

### 第三步：接入安全分析链路

在 `SafetyAnalysisChain.finalizeAnalysis()` 中：

```text
FmeaReport fmeaReport = state.buildFmeaReport();
FtaDoc ftaDoc = state.getFtaDoc();

fmeaReport = referenceResolver.attachFmeaReferences(...);
ftaDoc = referenceResolver.attachFtaReferences(...);

safetyDocService.persistSafetyDocs(...);
```

### 第四步：前端展示定位入口

FMEA 表格增加“定位”列。

FTA 节点详情增加“定位”按钮。

### 第五步：支持上传文件跳转

在 AADL 构件跳转稳定后，再扩展上传文件预览和行号定位。

## 推荐优先级

建议按这个顺序做：

1. FMEA 行对应 AADL 构件，点击跳到 AADL 模型行号。
2. FTA 节点对应 AADL 构件，点击跳到 AADL 模型行号。
3. FMEA/FTA 对应上传文件，点击跳到文件预览。
4. 对接构件树或架构图，实现选中构件节点。

第一步价值最大，复杂度最低，也最符合“失效部分对应到构件”的要求。

## 结论

这个需求合理，建议实现为“失效项到来源引用的可追溯跳转”。

后端不要直接生成前端路由，而是提供 `sourceRefs`。前端根据 `targetType`、`fileId`、`componentName`、`lineStart` 执行跳转。

最推荐的落点是：

```text
FmeaRow / FtaNode 增加 sourceRefs
SafetyAnalysisChain.finalizeAnalysis 持久化前补引用
前端 FMEA 表格和 FTA 节点详情读取 sourceRefs 跳转
```

