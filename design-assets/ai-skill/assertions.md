# 断言词表（fixtures 用）

`fixtures/*.json` 里每条 fixture 的 `expect[]` 只能使用下列 `assert` 值。
Kotlin 与 JS 两端的解析测试**读同一份 fixture、实现同一套断言**——这是"全客户端通用"唯一可证伪的证据。
断言对象是**解析后的契约结构**（`topics[] / actions[] / time`），不是模型原文，也不是自然语言措辞。

| `assert` | 参数 | 含义 |
| --- | --- | --- |
| `topic-count` | `min` / `max` / `exact` | 主题数量范围 |
| `action-count` | `min` / `max` / `exact` | 全部主题的动作总数 |
| `every-topic-has-actions` | `min`（默认 1） | 每个主题至少有 N 个动作 |
| `mentioned-topic` | `title_contains` | 存在一个主题，标题包含该子串 |
| `mentioned-action` | `title_contains`、可选 `topic_title_contains` | 存在匹配的动作 |
| `time-source-exists` | `source`、`count` | 该来源的动作数量 ≥ count |
| `time-source-at-least` | `source`、`count` | 同上（语义更明确的写法） |
| `no-time-invented-as-stated` | — | 用户原文里没出现的时点不得被标成 `stated`（用 fixture 的输入文本做朴素校验） |
| `inferred-has-basis` | — | 每个 `source=inferred` 的动作都有非空 `basis` 且 `confidence>0` |
| `inferred-times-ordered` | — | 同一主题内 `depends_on` 指向的动作必须更早结束；同一动作 `end > start` |
| `inferred-confidence-band` | `source`、`min`、`max` | 该来源的 `confidence` 落在区间内 |
| `time-within` | `source`、`after`、`before` | 存在一个该来源的动作，其 `start` 落在 `(after, before)` |
| `no-time-before-now` | — | 不存在早于 `now.instant` 的 `stated` 时间（用户明确说"昨天/刚才"的 fixture 例外，用 `allow_past: true`） |
| `first-action-has-evidence` | — | 第一个主题的第一个动作 `evidence` 非空 |
| `subtasks-verb-first` | `min`（默认 2） | 动作的子任务数量：要么 0，要么 ≥ min |
| `empty-topics-when-not-a-task` | — | 输入不是待办时 `topics` 必须为空，且 `understanding` 非空 |
| `degraded-matches-contract` | — | 本地兜底路径产出的结构同样满足上述全部结构约束 |

约定：

- 断言只看**结构**，不断言模型的具体措辞——否则每次提示词微调都会让语料失效。
- `stated` 的朴素校验：把 `start` 的 `HH:mm` 与 `M月D日`/`今天/明天/周X` 表达在输入原文里找一次；
  找不到就判失败。这一条专门用来防"模型把推测伪装成陈述"（见 `docs/ai-skill.md` §4.3）。
- 每条 fixture 至少要有一条与它考察重点相关的断言，不要所有 fixture 都只写 `topic-count`。
