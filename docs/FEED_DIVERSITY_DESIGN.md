# AI PREF 多样性校正设计

> 状态：**已实现（2026-10-26）**
> 关联：`docs/FEED_BUILD_QUOTA_DESIGN.md`、`docs/FUTURE_DESIGN.md` 推荐 Feed

---

## 1. 问题

AI 重排的 PREF 变体在偏好命中上很强，但会过度集中：

- 12 个用户离线对比，AI PREF Top10 NDCG 0.995、Precision 0.992
- 但 Top10 分类多样性只有 **1.08**，几乎整个首页都是同一分类
- prompt 第 5 条要求“同一分类不要连续超过 3 个”，模型没有可靠遵守
- `merge` 只做去重和补齐，没有多样性后置约束

结果：偏好命中高，但“逛”的体验变差，容易审美疲劳。

---

## 2. 方案

不改模型、不改 prompt，在 AI 排序之后做一次**保序纠偏**：

1. 同一分类最多连续 `maxConsecutive` 个（默认 3）
2. TopK 至少出现 `minCategoriesTop` 个不同分类（默认 3）

只对 `PREF` 生效；`EXPLORE` 已经有 `FeedExploreService` 强制探索，不再二次调整。

### 2.1 第一遍：连续约束

维护 `remaining` 和 `output`：

- 从 `remaining` 里找**第一个**放进去不会超过连续上限的商品
- 如果下一个就满足，直接放，顺序不变
- 如果连续超限，向后找第一个不同分类，把它提前；被跳过的商品保留原相对顺序
- 如果剩余全是同一分类，强制放行并记一次 `relaxed`

相比“先全部扫描、违规的丢到最后”，这种 first-fit 方式移动更少，NDCG 损失更小。

### 2.2 第二遍：TopK 分类数

第一遍后如果 TopK 仍然不足 3 个分类：

- 从 TopK 之后找排名最高的、TopK 里没有的分类
- 和 TopK 中“数量最多分类里排名最低”的一条交换
- 重复直到满足或没有可用分类
- 没有可用分类时记 `relaxed`，不死循环

---

## 3. 代码位置

```
AiFeedBuildProcessor.buildVariants
  → buildVariant
  → merge
  → dedupHeads
  → applyDiversity        // 新增，仅 PREF
  → applyExploration      // 仅 EXPLORE
```

- `FeedDiversityRanker`：纯函数，负责保序纠偏
- `AiFeedBuildProcessor.applyDiversity`：构造分类映射、替换变体顺序、记录指标
- 分类映射来自 `bundle.candidates()`，不额外查库
- 规则降级变体（`rule-fallback`）不处理，避免改变规则顺序

---

## 4. 配置

```yaml
app.ai:
  feed-diversity-enabled: ${AI_FEED_DIVERSITY_ENABLED:true}
  feed-diversity-max-consecutive: ${AI_FEED_DIVERSITY_MAX_CONSECUTIVE:3}
  feed-diversity-min-categories-top: ${AI_FEED_DIVERSITY_MIN_CATEGORIES_TOP:3}
  feed-diversity-top-k: ${AI_FEED_DIVERSITY_TOP_K:10}
```

指标：

```
sundry.feed.ai.diversity.adjusted
sundry.feed.ai.diversity.moved
sundry.feed.ai.diversity.relaxed
```

---

## 5. 效果

同一批 12~13 个新用户、目标分类偏好、同一候选集：

| 排序 | NDCG@10 | Precision@10 | Top10 分类多样性 |
|---|---:|---:|---:|
| 规则 | 0.612 | 0.500 | 4.15 |
| AI PREF（校正前） | 0.995 | 0.992 | **1.08** |
| AI PREF（校正后） | **0.815** | **0.769** | **3.08** |
| AI EXPLORE | 0.185 | 0.254 | 3.46 |

结论：

- 偏好命中从 0.995 降到 0.815，但仍明显高于规则 0.612
- 多样性从 1.08 提升到 3.08
- 13/13 个用户的 AI PREF NDCG 仍高于规则
- 这是“用一点偏好命中换多样性”的明确取舍；参数可调

---

## 6. 测试

- `FeedDiversityRankerTest`（5 个）
  - 长连续分类被打散且不丢商品
  - 已经多样的顺序不变
  - 分类不足时安全放行
  - TopK 分类补位
  - 未知分类不计入
- `AiFeedIntegrationTest` 26 个、`FeedIntegrationTest` 8 个
- 全量 Java：**380 个用例，0 失败 0 错误**

---

## 7. 调参

| 现象 | 调整 |
|---|---|
| NDCG 掉太多 | `min-categories-top` 3 → 2，或 `max-consecutive` 3 → 4 |
| 首页还是太单一 | `min-categories-top` 提到 4，或 `max-consecutive` 降到 2 |
| `relaxed` 很高 | 候选集分类本身不足，先查召回 |

---

## 8. 改动文件

- `src/main/java/com/sundry/ai/FeedDiversityRanker.java`（新增）
- `src/main/java/com/sundry/ai/AiFeedBuildProcessor.java`
- `src/main/java/com/sundry/ai/AiProperties.java`
- `src/main/resources/application.yml`
- `src/test/java/com/sundry/ai/FeedDiversityRankerTest.java`（新增）
- `docs/FEED_DIVERSITY_DESIGN.md`（本文）
- `DEVLOG.md`
