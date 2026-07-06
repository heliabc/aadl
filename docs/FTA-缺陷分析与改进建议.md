# FTA 故障树分析模块 - 缺陷分析与改进建议

## 一、当前实现概述

当前项目中的故障树分析（FTA）生成主要由 `SafetyAnalysisChain` 类负责，核心流程如下：

1. **Step 1-5**：结构分解 → 功能定义 → 失效模式识别 → 影响原因分析 → 风险评估
2. **Step 6**：基于失效模式生成 FMEA 表格
3. **Step 7**：基于 FMEA 生成 FTA 故障树

FTA 生成的关键方法：
- `generateFta()`：FTA 生成入口
- `buildFtaPrompt()`：构建 LLM prompt
- `buildFtaDocFromPlan()`：从 LLM 返回的 FTAPlan 构建 FtaDoc
- `buildBranchNode()`：构建分支节点
- `buildPlanEventNode()`：构建事件节点

## 二、功能完整性分析

### 标准 FTA 分析应包含的功能模块

| 功能模块 | 当前状态 | 说明 |
|---------|---------|------|
| 故障树生成 | ⚠️ 部分实现 | 仅支持 2 层深度，无法多层分解 |
| 最小割集计算 | ❌ 缺失 | 未实现 |
| 最小路集计算 | ❌ 缺失 | 未实现 |
| 顶事件概率计算 | ❌ 缺失 | 未实现 |
| 结构重要度分析 | ❌ 缺失 | 未实现 |
| 概率重要度分析 | ❌ 缺失 | 未实现 |
| 关键重要度分析 | ❌ 缺失 | 未实现 |
| 灵敏度分析 | ❌ 缺失 | 未实现 |
| 定性分析报告 | ❌ 缺失 | 未实现 |
| 定量分析报告 | ❌ 缺失 | 未实现 |

## 三、主要缺陷分析

### 第一类：故障树生成缺陷

#### 缺陷 1：FTAPlan 格式限制导致树深度不足（严重）

**问题描述**：
当前 FTAPlan 格式只支持一级分支（`branches`），每个分支直接包含 `events`（叶子节点），导致生成的故障树深度只有两层：TOP → branches → events。

**代码位置**：
`SafetyAnalysisChain.java` 第 972 行：

```java
prompt.append("3. branches 是一级故障分支；每个 branch 必须有 label、relationType、gateReason、events。\n");
```

第 975 行：

```java
prompt.append("6. events[].type 只能为 BASIC 或 UNDEVELOPED；BASIC 必须填写 sourceRow，UNDEVELOPED 可不填 sourceRow。\n");
```

**影响**：
- 无法表达复杂的因果关系链
- 无法进行多层原因分解
- 与示例文档中的多层 FTA 结构不一致（参考 `safety-analysis-requirement-output.md` 中的三层结构）

#### 缺陷 2：buildPlanEventNode() 总是创建叶子节点（严重）

**问题描述**：
`buildPlanEventNode()` 方法创建的节点总是叶子节点（children 为空 `List.of()`），即使 LLM 返回的 event 对象中包含 children，也会被忽略。

**代码位置**：
`SafetyAnalysisChain.java` 第 1130-1138 行：

```java
return new FtaNode(
    "n" + branchIndex + "_" + eventIndex,
    label,
    type,
    null,
    null,
    null,
    List.of()  // 始终为空，忽略嵌套 children
);
```

**影响**：
- 即使 LLM 尝试生成嵌套结构，也会被后端丢弃
- 无法支持递归树分解

#### 缺陷 3：Prompt 示例过于简单（中等）

**问题描述**：
`buildFtaPrompt()` 中的输出格式示例只展示了扁平结构，没有展示嵌套 children 的用法。

**代码位置**：
`SafetyAnalysisChain.java` 第 978-987 行：

```java
prompt.append("---FTA_PLAN_JSON---\n");
prompt.append("{\"topEvent\":\"系统失效\",\"branches\":[");
prompt.append("{\"label\":\"任务执行链路异常\",...\"events\":[");
prompt.append("{\"label\":\"命令输入缺失\",\"type\":\"BASIC\",\"sourceRow\":1},");
prompt.append("{\"label\":\"模型缺少错误传播信息\",\"type\":\"UNDEVELOPED\"}]},...");
```

**影响**：
- LLM 倾向于模仿示例结构，生成扁平树
- 缺乏多层分解的引导

#### 缺陷 4：FTAPlan 与 FtaDoc 格式不一致（中等）

**问题描述**：
FTAPlan 格式（branches/events）与最终的 FtaDoc 格式（root/children 递归）存在差异，增加了理解复杂度。

**FTAPlan 格式**：
```json
{
  "topEvent": "...",
  "branches": [
    {
      "label": "...",
      "relationType": "...",
      "events": [...]
    }
  ]
}
```

**FtaDoc 格式**：
```json
{
  "topEvent": "...",
  "root": {
    "id": "n0",
    "type": "TOP",
    "children": [
      {
        "id": "n1",
        "type": "INTERMEDIATE",
        "children": [...]
      }
    ]
  }
}
```

**影响**：
- 需要额外的格式转换逻辑
- 增加调试和维护难度

#### 缺陷 5：ID 生成策略不够健壮（轻微）

**问题描述**：
节点 ID 使用简单的计数器（`n{branchIndex}_{eventIndex}`），在嵌套场景下可能产生冲突。

**代码位置**：
`SafetyAnalysisChain.java` 第 1131 行：

```java
return new FtaNode(
    "n" + branchIndex + "_" + eventIndex,
    ...
);
```

**影响**：
- 嵌套层级增加时，ID 可能重复
- 违反 FtaValidator 的唯一性检查

#### 缺陷 6：sourceRow 覆盖验证不完整（轻微）

**问题描述**：
`buildUncoveredFmeaNodes()` 方法只检查一级 events 是否覆盖了 FMEA 行，嵌套的 children 中的 sourceRow 不会被统计。

**代码位置**：
`SafetyAnalysisChain.java` 第 1141-1158 行：

```java
private List<FtaNode> buildUncoveredFmeaNodes(List<FmeaRow> fmeaRows, boolean[] coveredRows) {
    // 只检查 coveredRows 数组，但该数组只在 buildPlanEventNode 中被设置
    // 嵌套的 children 中的 sourceRow 不会被设置到 coveredRows 中
}
```

**影响**：
- 可能遗漏未覆盖的 FMEA 行
- 生成的 FTA 不完整

### 第二类：FTA 分析功能缺失

#### 缺陷 7：缺少最小割集计算（严重）

**问题描述**：
最小割集（Minimal Cut Sets, MCS）是导致顶事件发生的最小基本事件组合，是 FTA 定性分析的核心。

**当前状态**：
- 完全未实现
- 无法识别系统的关键薄弱环节
- 无法进行定性分析

**影响**：
- 无法确定哪些基本事件组合会导致系统故障
- 无法识别单点故障
- 无法进行故障诊断和预防

#### 缺陷 8：缺少最小路集计算（严重）

**问题描述**：
最小路集（Minimal Path Sets, MPS）是保证顶事件不发生的最小基本事件组合，是系统可靠性设计的重要依据。

**当前状态**：
- 完全未实现
- 无法确定系统的冗余设计是否充分
- 无法进行可靠性优化

**影响**：
- 无法确定需要重点保护的组件
- 无法进行冗余设计优化
- 无法制定有效的维护策略

#### 缺陷 9：缺少顶事件概率计算（严重）

**问题描述**：
顶事件概率是 FTA 定量分析的基础，需要基于基本事件的失效概率和门逻辑进行计算。

**当前状态**：
- 完全未实现
- FmeaRow 中有 occurrence 字段，但未被用于概率计算
- 缺少基本事件的失效概率模型（指数分布、威布尔分布等）

**影响**：
- 无法评估系统整体风险水平
- 无法进行定量风险评估
- 无法满足安全完整性等级（SIL）验证要求

#### 缺陷 10：缺少重要度分析（严重）

**问题描述**：
重要度分析用于量化各基本事件对顶事件的影响程度，包括结构重要度、概率重要度和关键重要度。

**当前状态**：
- 完全未实现
- 无法识别对系统安全影响最大的组件
- 无法进行针对性的改进

**影响**：
- 无法确定优先改进的对象
- 无法进行成本效益分析
- 无法制定有效的维护计划

#### 缺陷 11：缺少灵敏度分析（中等）

**问题描述**：
灵敏度分析用于评估基本事件概率变化对顶事件概率的影响，是风险管理和决策支持的重要工具。

**当前状态**：
- 完全未实现
- 无法评估参数不确定性的影响
- 无法进行 what-if 分析

**影响**：
- 无法评估改进措施的效果
- 无法进行风险权衡分析
- 无法支持管理决策

#### 缺陷 12：缺少定性分析报告（中等）

**问题描述**：
定性分析报告应包含故障树结构摘要、最小割集列表、单点故障识别、关键路径分析等内容。

**当前状态**：
- 完全未实现
- 当前只输出 JSON 格式的故障树
- 缺少可读性强的分析报告

**影响**：
- 非技术人员难以理解分析结果
- 无法满足安全评审要求
- 不利于沟通和决策

#### 缺陷 13：缺少定量分析报告（中等）

**问题描述**：
定量分析报告应包含顶事件概率、不可靠度曲线、重要度排序、灵敏度曲线等内容。

**当前状态**：
- 完全未实现
- 缺少概率计算和统计分析
- 缺少可视化图表

**影响**：
- 无法进行定量风险评估
- 无法满足安全认证要求
- 无法支持数据驱动的决策

### 第三类：数据模型缺陷

#### 缺陷 14：FmeaRow 缺少失效概率字段（中等）

**问题描述**：
当前 FmeaRow 只有 occurrence（发生度）字段，缺少具体的失效概率值。

**代码位置**：
`FmeaRow.java`：

```java
public record FmeaRow(
    String component,
    String function,
    String failure,
    String effect,
    String cause,
    int severity,
    int occurrence,  // 只有发生度，没有具体概率
    int detection,
    Integer rpn,
    // ...
)
```

**影响**：
- 无法进行定量计算
- 无法进行概率重要度分析
- 无法进行灵敏度分析

#### 缺陷 15：FtaNode 缺少概率属性（中等）

**问题描述**：
当前 FtaNode 只有结构信息，缺少概率属性。

**代码位置**：
`FtaNode.java`：

```java
public record FtaNode(
    String id,
    String label,
    FtaNodeType type,
    GateType parentGate,
    Integer voteThreshold,
    String inhibitCondition,
    String gateReason,
    List<FtaNode> children,
    List<SourceReference> sourceRefs
    // 缺少：probability, unreliability, importance 等
)
```

**影响**：
- 无法存储计算结果
- 无法进行定量分析
- 无法进行重要度排序

#### 缺陷 16：缺少失效概率分布模型（中等）

**问题描述**：
系统缺少失效概率分布模型，无法将 occurrence 评分转换为具体的失效概率。

**当前状态**：
- 没有定义失效概率分布（指数分布、威布尔分布等）
- 没有定义 occurrence 评分与失效概率的映射关系
- 没有定义任务时间与不可靠度的关系

**影响**：
- 无法进行精确的定量计算
- 无法进行可靠性预测
- 无法进行寿命分析

## 四、改进建议

### 第一阶段：故障树生成改进（优先级：高）

#### 改进 1：支持多层递归分解

**目标**：支持 3-4 层故障树分解，形成：顶事件 → 一级中间事件 → 二级中间事件 → 基本事件

**修改点**：

1. **修改 `buildFtaPrompt()` 方法**：
   - 更新 prompt 说明，允许 events 包含嵌套 children
   - 添加多层分解的示例
   - 明确 INTERMEDIATE 类型事件可以有 children

2. **修改 `buildPlanEventNode()` 方法**：
   - 支持递归构建子节点
   - 处理嵌套的 children 字段
   - 正确设置 parentGate 和 gateReason

3. **修改 `buildBranchNode()` 方法**：
   - 确保分支节点正确处理嵌套事件

#### 改进 2：统一 FTAPlan 与 FtaDoc 格式

**目标**：减少格式转换复杂度，提高可维护性

**方案**：保持 FTAPlan 格式，但优化使其更接近 FtaDoc

### 第二阶段：数据模型扩展（优先级：高）

#### 改进 3：扩展 FmeaRow 数据模型

**目标**：支持失效概率计算

**新增字段**：

```java
public record FmeaRow(
    // ... 现有字段
    Double failureRate,        // 失效概率（每小时）
    Double missionTime,        // 任务时间（小时）
    Double unreliability,      // 不可靠度
    String distributionType   // 分布类型（exponential/weibull/normal）
)
```

#### 改进 4：扩展 FtaNode 数据模型

**目标**：支持概率计算和重要度分析

**新增字段**：

```java
public record FtaNode(
    // ... 现有字段
    Double probability,        // 节点概率
    Double unreliability,      // 不可靠度
    Double structureImportance,  // 结构重要度
    Double probabilityImportance, // 概率重要度
    Double criticalImportance   // 关键重要度
)
```

#### 改进 5：新增失效概率映射服务

**目标**：将 occurrence 评分转换为失效概率

**实现**：

```java
@Service
public class FailureProbabilityMapper {
    
    // occurrence 1-10 对应的失效概率范围
    private static final double[] OCCURRENCE_RATES = {
        1e-6, 2e-6, 5e-6, 1e-5, 2e-5,
        5e-5, 1e-4, 2e-4, 5e-4, 1e-3
    };
    
    public double mapOccurrenceToRate(int occurrence) {
        return OCCURRENCE_RATES[Math.min(occurrence - 1, 9)];
    }
    
    public double calculateUnreliability(double rate, double time) {
        return 1 - Math.exp(-rate * time);
    }
}
```

### 第三阶段：分析算法实现（优先级：高）

#### 改进 6：实现最小割集算法

**算法选择**：
- MOCUS（下行法）：适用于中小规模故障树
- Fussell-Vesely 算法：适用于大规模故障树
-二元决策图（BDD）算法：效率最高，但实现复杂

**推荐**：先实现 MOCUS 算法，后续根据性能需求优化

**实现示例**：

```java
@Service
public class MinimalCutSetCalculator {
    
    public List<Set<String>> calculate(FtaNode root) {
        List<Set<String>> cutSets = new ArrayList<>();
        // MOCUS 算法实现
        mocus(root, new HashSet<>(), cutSets);
        return removeSupersets(cutSets);
    }
    
    private void mocus(FtaNode node, Set<String> currentCut, 
                       List<Set<String>> cutSets) {
        if (node.isLeaf()) {
            Set<String> cut = new HashSet<>(currentCut);
            cut.add(node.id());
            cutSets.add(cut);
            return;
        }
        
        if (node.parentGate() == GateType.OR) {
            for (FtaNode child : node.children()) {
                mocus(child, currentCut, cutSets);
            }
        } else if (node.parentGate() == GateType.AND) {
            Set<String> newCut = new HashSet<>(currentCut);
            for (FtaNode child : node.children()) {
                mocus(child, newCut, cutSets);
            }
        }
    }
}
```

#### 改进 7：实现最小路集算法

**算法**：利用对偶树概念，将 AND 门替换为 OR 门，OR 门替换为 AND 门，然后计算最小割集

**实现**：

```java
@Service
public class MinimalPathSetCalculator {
    
    public List<Set<String>> calculate(FtaNode root) {
        FtaNode dualTree = buildDualTree(root);
        return minimalCutSetCalculator.calculate(dualTree);
    }
    
    private FtaNode buildDualTree(FtaNode node) {
        // 递归构建对偶树，交换 AND/OR 门
    }
}
```

#### 改进 8：实现顶事件概率计算

**算法**：基于最小割集和基本事件概率，使用 inclusion-exclusion 原理或近似算法

**实现**：

```java
@Service
public class TopEventProbabilityCalculator {
    
    public double calculate(FtaNode root, Map<String, Double> basicEventProbabilities) {
        List<Set<String>> mcs = minimalCutSetCalculator.calculate(root);
        return calculateFromCutSets(mcs, basicEventProbabilities);
    }
    
    private double calculateFromCutSets(List<Set<String>> mcs, 
                                        Map<String, Double> probabilities) {
        // 使用 inclusion-exclusion 或近似算法
        // 推荐使用 rare event 近似：P(top) ≈ 1 - ∏(1 - P(cutset_i))
        double product = 1.0;
        for (Set<String> cutset : mcs) {
            double cutsetProb = 1.0;
            for (String event : cutset) {
                cutsetProb *= probabilities.getOrDefault(event, 0.0);
            }
            product *= (1 - cutsetProb);
        }
        return 1 - product;
    }
}
```

#### 改进 9：实现重要度分析

**三种重要度**：

1. **结构重要度**：仅基于故障树结构，不考虑概率

```java
@Service
public class StructureImportanceCalculator {
    
    public double calculate(FtaNode node, FtaNode root) {
        // 计算节点在所有最小割集中的出现频率
        List<Set<String>> mcs = minimalCutSetCalculator.calculate(root);
        int totalMcs = mcs.size();
        int containingMcs = (int) mcs.stream()
            .filter(cutset -> cutset.contains(node.id()))
            .count();
        return (double) containingMcs / totalMcs;
    }
}
```

2. **概率重要度**：考虑概率变化对顶事件的影响

```java
@Service
public class ProbabilityImportanceCalculator {
    
    public double calculate(FtaNode node, Map<String, Double> probabilities, 
                           double missionTime) {
        double p0 = topEventProbabilityCalculator.calculate(root, probabilities);
        Map<String, Double> modified = new HashMap<>(probabilities);
        modified.put(node.id(), 1.0);  // 假设该事件必然发生
        double p1 = topEventProbabilityCalculator.calculate(root, modified);
        return p1 - p0;
    }
}
```

3. **关键重要度**：相对变化率

```java
@Service
public class CriticalImportanceCalculator {
    
    public double calculate(FtaNode node, Map<String, Double> probabilities, 
                           double missionTime) {
        double pi = probabilityImportanceCalculator.calculate(node, probabilities, missionTime);
        double pTop = topEventProbabilityCalculator.calculate(root, probabilities);
        double pNode = probabilities.get(node.id());
        return (pi * pNode) / pTop;
    }
}
```

#### 改进 10：实现灵敏度分析

**算法**：评估基本事件概率变化对顶事件概率的影响

**实现**：

```java
@Service
public class SensitivityAnalyzer {
    
    public Map<String, List<double[]>> analyze(FtaNode root, 
                                               Map<String, Double> baseProbabilities,
                                               double variationRange) {
        Map<String, List<double[]>> results = new HashMap<>();
        
        for (String event : baseProbabilities.keySet()) {
            List<double[]> curve = new ArrayList<>();
            for (double factor = 1 - variationRange; 
                 factor <= 1 + variationRange; 
                 factor += 0.1) {
                Map<String, Double> modified = new HashMap<>(baseProbabilities);
                modified.put(event, baseProbabilities.get(event) * factor);
                double pTop = topEventProbabilityCalculator.calculate(root, modified);
                curve.add(new double[]{factor, pTop});
            }
            results.put(event, curve);
        }
        
        return results;
    }
}
```

### 第四阶段：报告生成（优先级：中）

#### 改进 11：生成定性分析报告

**报告内容**：

```java
@Service
public class QualitativeAnalysisReportGenerator {
    
    public String generate(FtaDoc ftaDoc) {
        StringBuilder report = new StringBuilder();
        
        report.append("# FTA 定性分析报告\n\n");
        report.append("## 1. 故障树结构摘要\n");
        report.append("- 顶事件：").append(ftaDoc.topEvent()).append("\n");
        report.append("- 总节点数：").append(ftaDoc.root().totalNodes()).append("\n");
        report.append("- 最大深度：").append(ftaDoc.root().maxDepth()).append("\n");
        report.append("- 门类型统计：").append(countGates(ftaDoc.root())).append("\n\n");
        
        report.append("## 2. 最小割集\n");
        List<Set<String>> mcs = minimalCutSetCalculator.calculate(ftaDoc.root());
        report.append("- 割集数量：").append(mcs.size()).append("\n");
        report.append("- 一阶割集（单点故障）：").append(filterSingleEvents(mcs)).append("\n\n");
        
        report.append("## 3. 单点故障识别\n");
        List<FtaNode> singlePoints = identifySinglePointFailures(ftaDoc.root());
        for (FtaNode node : singlePoints) {
            report.append("- ").append(node.label()).append("\n");
        }
        
        return report.toString();
    }
}
```

#### 改进 12：生成定量分析报告

**报告内容**：

```java
@Service
public class QuantitativeAnalysisReportGenerator {
    
    public String generate(FtaDoc ftaDoc, Map<String, Double> probabilities, 
                          double missionTime) {
        StringBuilder report = new StringBuilder();
        
        report.append("# FTA 定量分析报告\n\n");
        report.append("## 1. 基本参数\n");
        report.append("- 任务时间：").append(missionTime).append(" 小时\n");
        report.append("- 基本事件数量：").append(countBasicEvents(ftaDoc.root())).append("\n\n");
        
        report.append("## 2. 顶事件概率\n");
        double pTop = topEventProbabilityCalculator.calculate(ftaDoc.root(), probabilities);
        report.append("- 顶事件概率：").append(String.format("%.6e", pTop)).append("\n");
        report.append("- 系统可靠度：").append(String.format("%.6f", 1 - pTop)).append("\n\n");
        
        report.append("## 3. 重要度分析\n");
        report.append("### 3.1 结构重要度 TOP 10\n");
        List<FtaNode> structureTop10 = getStructureImportanceTopN(ftaDoc.root(), 10);
        for (int i = 0; i < structureTop10.size(); i++) {
            FtaNode node = structureTop10.get(i);
            double importance = structureImportanceCalculator.calculate(node, ftaDoc.root());
            report.append(String.format("%d. %s: %.4f\n", i + 1, node.label(), importance));
        }
        
        report.append("### 3.2 关键重要度 TOP 10\n");
        List<FtaNode> criticalTop10 = getCriticalImportanceTopN(ftaDoc.root(), probabilities, 10);
        for (int i = 0; i < criticalTop10.size(); i++) {
            FtaNode node = criticalTop10.get(i);
            double importance = criticalImportanceCalculator.calculate(node, probabilities, missionTime);
            report.append(String.format("%d. %s: %.4f\n", i + 1, node.label(), importance));
        }
        
        return report.toString();
    }
}
```

### 第五阶段：API 与 UI 集成（优先级：中）

#### 改进 13：新增 FTA 分析 API

**新增接口**：

```java
@RestController
@RequestMapping("/api/safety/fta")
public class FtaAnalysisController {
    
    @PostMapping("/minimal-cut-sets")
    public ResponseEntity<List<Set<String>>> calculateMinimalCutSets(
        @RequestBody FtaDoc ftaDoc) {
        return ResponseEntity.ok(minimalCutSetCalculator.calculate(ftaDoc.root()));
    }
    
    @PostMapping("/minimal-path-sets")
    public ResponseEntity<List<Set<String>>> calculateMinimalPathSets(
        @RequestBody FtaDoc ftaDoc) {
        return ResponseEntity.ok(minimalPathSetCalculator.calculate(ftaDoc.root()));
    }
    
    @PostMapping("/top-event-probability")
    public ResponseEntity<Double> calculateTopEventProbability(
        @RequestBody FtaAnalysisRequest request) {
        return ResponseEntity.ok(
            topEventProbabilityCalculator.calculate(
                request.ftaDoc().root(), 
                request.basicEventProbabilities()
            )
        );
    }
    
    @PostMapping("/importance-analysis")
    public ResponseEntity<ImportanceAnalysisResult> analyzeImportance(
        @RequestBody FtaAnalysisRequest request) {
        return ResponseEntity.ok(importanceAnalyzer.analyze(request));
    }
    
    @PostMapping("/sensitivity-analysis")
    public ResponseEntity<Map<String, List<double[]>>> analyzeSensitivity(
        @RequestBody FtaAnalysisRequest request) {
        return ResponseEntity.ok(sensitivityAnalyzer.analyze(request));
    }
    
    @PostMapping("/qualitative-report")
    public ResponseEntity<String> generateQualitativeReport(
        @RequestBody FtaDoc ftaDoc) {
        return ResponseEntity.ok(qualitativeReportGenerator.generate(ftaDoc));
    }
    
    @PostMapping("/quantitative-report")
    public ResponseEntity<String> generateQuantitativeReport(
        @RequestBody FtaAnalysisRequest request) {
        return ResponseEntity.ok(quantitativeReportGenerator.generate(request));
    }
}
```

#### 改进 14：新增前端可视化组件

**需要新增的组件**：

1. **故障树可视化**：使用 D3.js 或 vis.js 渲染故障树
2. **最小割集表格**：展示最小割集列表和单点故障
3. **重要度图表**：柱状图展示重要度排序
4. **灵敏度曲线**：折线图展示参数变化影响
5. **分析报告导出**：支持 PDF/Excel 导出

## 五、实施计划

### 阶段一：故障树生成改进（1-2 天）

1. 修改 `buildFtaPrompt()` 方法，更新 prompt 支持多层分解
2. 修改 `buildPlanEventNode()` 方法，支持递归构建子节点
3. 添加多层 FTA 生成的单元测试

### 阶段二：数据模型扩展（1 天）

1. 扩展 FmeaRow 数据模型
2. 扩展 FtaNode 数据模型
3. 实现 FailureProbabilityMapper 服务

### 阶段三：分析算法实现（3-4 天）

1. 实现最小割集算法（MOCUS）
2. 实现最小路集算法
3. 实现顶事件概率计算
4. 实现重要度分析（结构/概率/关键）
5. 实现灵敏度分析

### 阶段四：报告生成（1-2 天）

1. 实现定性分析报告生成器
2. 实现定量分析报告生成器
3. 添加报告导出功能

### 阶段五：API 与 UI 集成（2-3 天）

1. 新增 FTA 分析 API 接口
2. 前端故障树可视化组件
3. 前端分析结果展示组件
4. 集成测试

### 预计总工作量：8-12 天

## 六、参考示例

### 当前生成的 FTA（2 层）

```json
{
  "topEvent": "系统失效",
  "root": {
    "id": "n0",
    "label": "系统失效",
    "type": "TOP",
    "parentGate": "OR",
    "children": [
      {
        "id": "n1",
        "label": "任务执行链路异常",
        "type": "INTERMEDIATE",
        "parentGate": "OR",
        "children": [
          {"id": "n1_1", "label": "命令输入缺失", "type": "BASIC", "children": []},
          {"id": "n1_2", "label": "任务调度异常", "type": "BASIC", "children": []}
        ]
      }
    ]
  }
}
```

### 改进后期望的 FTA（3-4 层 + 分析结果）

```json
{
  "topEvent": "系统失效",
  "root": {
    "id": "n0",
    "label": "系统失效",
    "type": "TOP",
    "parentGate": "OR",
    "children": [
      {
        "id": "n1",
        "label": "任务执行链路异常",
        "type": "INTERMEDIATE",
        "parentGate": "OR",
        "gateReason": "命令输入缺失、任务调度异常或设备通道失效任一项都可能独立导致任务执行链路异常。",
        "probability": 0.00234,
        "criticalImportance": 0.45,
        "children": [
          {
            "id": "n1_1",
            "label": "命令输入缺失",
            "type": "INTERMEDIATE",
            "parentGate": "OR",
            "gateReason": "命令来源有多个独立路径，任一路径失效都可导致命令缺失。",
            "probability": 0.00123,
            "criticalImportance": 0.25,
            "children": [
              {"id": "n1_1_1", "label": "主命令通道失效", "type": "BASIC", "sourceRow": 1, "probability": 0.0005},
              {"id": "n1_1_2", "label": "备用命令通道失效", "type": "BASIC", "sourceRow": 2, "probability": 0.0007}
            ]
          }
        ]
      }
    ]
  },
  "analysis": {
    "minimalCutSets": [
      ["n1_1_1"],
      ["n1_1_2"],
      ["n1_2_1", "n1_2_2"]
    ],
    "topEventProbability": 0.00234,
    "systemReliability": 0.99766,
    "structureImportance": {
      "n1_1_1": 0.33,
      "n1_1_2": 0.33,
      "n1_2_1": 0.17,
      "n1_2_2": 0.17
    },
    "criticalImportance": {
      "n1_1_1": 0.21,
      "n1_1_2": 0.30,
      "n1_2_1": 0.12,
      "n1_2_2": 0.12
    }
  }
}
```

## 七、总结

当前 FTA 模块存在两大类缺陷：

1. **故障树生成缺陷**：树深度不足，只能生成 2 层结构
2. **分析功能缺失**：缺少最小割集、最小路集、概率计算、重要度分析等核心功能

改进优先级：
1. **高**：支持多层递归分解、扩展数据模型、实现核心分析算法
2. **中**：生成分析报告、API 集成、前端可视化
3. **低**：优化算法性能、添加高级分析功能

预计改进工作量：8-12 天

通过本次改进，FTA 模块将从简单的故障树生成工具升级为完整的故障树分析系统，能够支持定性分析和定量分析，满足安全评审和认证要求。
