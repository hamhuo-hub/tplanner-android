# 全客户端通用 AI Skill：自然对话 → 主题 / 动作 / 子任务 / 时间

> 状态：**契约、P1 资产与手机端 L2 已落地**；桌面端仍待接入。
> P1 的资产（提示词 / 工具 schema / 语料 / 分发脚本）见 §7 与 `design-assets/ai-skill/`；
> P2 的手机端实现见 §9 与 `app/src/main/java/com/hamhuo/tplanner/ai/`。
> 目标：把现在只存在于 Android 的「写日程」提取器，改成**一份契约、四端消费**的 AI 能力。
> 相关：`docs/sync-v5.md`（数据与同步契约）、`design-assets/tokens/README.md`（既有的"单一源 + 各端副本"分发方式）。

## 1. 四端现状

| 端 | 分支 / 工作树 | 现在的 AI 能力 | 数据写入路径 |
| --- | --- | --- | --- |
| 手机 Android | `mobile_andorid`（当前） | `app/.../ai/DeepSeekAnalysisService.kt`（440 行，单轮，强制 tool call） | `ScheduleItemStore.saveAll()`，全选一次本地事务 |
| 手表 Wear | `mobile_andorid` | 无。只从 jCal 读数 | 自己的 deviceId + sequence，走手机中继 |
| 桌面 Electron/React | `.v5-worktrees/desktop`（`master`） | **完全没有** | `syncV5/store.js` → `sync.persist(document)` |
| 同步服务端 | `.v5-worktrees/server`（`sync_server`） | 无（服务端源码不在本仓） | 唯一 SQLite 写者 |

调用链（现状，仅手机）：

```
PlanSheet（在底部那张纸上写一段自然语言）
  → MainScreen.submitPlanForPreview()           ui/MainScreen.kt
  → PlanExtractor.extract()                     ai/PlanExtractor.kt
  → POST api.deepseek.com /v1/chat/completions  model=deepseek-flash
  → normalize()：没时间就保持没时间
  → PlanPreview（只确认"理解得对不对"）
  → MainScreen.confirmPlan()：stablePlanTaskId("task:$i", requestId) → eventStore.saveAll(items)
```

两端的 jCal 模型已经**各自重复实现了一份**：`shared/src/main/kotlin/.../JcalDocument.kt`（Kotlin）与
`.v5-worktrees/desktop/sync-v5/jcal.mjs`（JS，被 `document.js` 引用）。`JcalDocument.validate()`
只对已知属性查重、**不拒绝未知属性**，所以新增 `x-` 属性不会被旧客户端判为非法。这是本次设计
可以依赖的两个既有事实：**模型语义两端一致，但代码是两份，必须靠契约对齐。**

## 2. 现状与需求的四处硬冲突

先看代码实际做了什么，再看要求：

`SYSTEM_PROMPT`（`DeepSeekAnalysisService.kt:416`）原文要求：

- 「提取单位是「独立目标/主题」」——但输出结构里**没有主题这一层**，`ProposedTask` 就是任务本身；
- 「start_at：用户没有给出时间时必须传 null，**绝不推测**」；
- 「你不需要判断事件/状态/任务类型」；
- `"thinking": {"type": "disabled"}`（第 178 行）+ `"tool_choice": "required"`（第 181 行）。

对应用户的四条要求：

| 要求 | 现状 | 结论 |
| --- | --- | --- |
| ① 自然对话中识别不同主题为任务 | 只有 `title` + `checklist`；"主题"仅存在于提示词里，用户看不到"这句话里有 3 个目标" | **缺一层数据结构**，同时缺"多轮对话"这个前提 |
| ② 识别每个主题的动作，并拆成子任务 | "动作"这一层缺失。现在只有"目标 → 清单项"，没有"为达成该目标需要的几件事" | **缺第二层**（动作） |
| ③ 思考并补充猜测开始/结束时间 | 提示词四处在**禁止**推测时间 | **方向相反**，这不是加个字段，是改产品契约 |
| ④ 调用 flash 思考模型 | `MODEL = "deepseek-v4-flash"` 已是被下线模型的路由别名；思考被显式关闭；而一旦打开思考，`tool_choice: "required"` 会直接 **400** | **三处都要改，且互相耦合** |

### 2.1 模型接口（已核对官方文档）

| 项 | 事实 | 来源 |
| --- | --- | --- |
| 模型 id | 只有 `deepseek-flash`（= V4.1-Flash）与 `deepseek-v4-pro`。`deepseek-v4-flash` 仍被接受但模型已下线，按 Flash 价计费 | [Models & Pricing](https://api-docs.deepseek.com/quick_start/pricing) |
| 思考 | `thinking.type` 默认就是 `enabled`；`reasoning_effort` = `none/low/high/max`（默认 `high`） | [Thinking Mode](https://api-docs.deepseek.com/guides/thinking_mode) |
| 思考 × 工具 | **思考模式下 `tool_choice: "required"` 与指定工具名都会 400**，只能用 `auto`/`none` | [Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion) |
| 思考 × 多轮 | 带 `tools` 时，**所有历史轮的 `reasoning_content` 必须原样回传**，否则 400 | [Thinking Mode](https://api-docs.deepseek.com/guides/thinking_mode) |
| 强制结构 | `strict: true`（Beta，需 `base_url=.../beta`），对象须 `additionalProperties:false` 且**所有字段都进 required**；不支持 `minItems/maxItems/minLength/maxLength` | [Tool Calls](https://api-docs.deepseek.com/guides/tool_calls) |
| JSON 模式 | 只有 `response_format:{type:"json_object"}`，**没有 json_schema**；须在提示里出现 "json" 并给样例，且官方承认偶发空内容 | [JSON Output](https://api-docs.deepseek.com/guides/json_mode) |
| 参数 | 思考模式**忽略** `temperature`/`presence_penalty`/`frequency_penalty`；`top_p` 被抬到 ≥0.95 | [Thinking Mode](https://api-docs.deepseek.com/guides/thinking_mode) |
| 上下文 | 1M 上下文 / 384K 输出；不设 `max_tokens` 时非思考默认 8K、思考默认 64K | [Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion) |
| 缓存 | 磁盘前缀缓存**默认开启**，命中按前缀"完整单元"匹配；命中价 $0.003–0.006/M（未命中 $0.15–0.30/M，输出 $0.60–1.20/M） | [Context Caching](https://api-docs.deepseek.com/guides/kv_cache)、[Pricing](https://api-docs.deepseek.com/quick_start/pricing) |
| 错误 | 400/401/402/422/429/500/503；`finish_reason` 多了 `aborted`。**文档不提供限流响应头与 Retry-After** | [Error Codes](https://api-docs.deepseek.com/quick_start/error_codes) |

`/v1/chat/completions` 这个路径：文档只写 `/chat/completions`（base 为 `https://api.deepseek.com`）。
现有代码走 `/v1/...` 目前可用（OpenAI 兼容层），但**应当在第 1 阶段实测确认**，不要当作契约。

### 2.2 其他既有缺口

- **时区**：技能里没有时区参数，代码写死 `Asia/Shanghai`（`AppTime.kt`），而桌面端有可选的旅行时区
  （`AddEventModal.jsx` 的 `tplanner_travel_timezone`）。两端基准不同，"明天下午3点"会解析成不同时刻。
- **失败不可见**：`extractTasks` 捕获所有异常后返回 `emptyList()`（第 102–110 行），UI 只能提示
  "AI 服务不可用"；模型返回了但内容不合法时也走同一条路。超时/HTTP 错误被吞掉。
- **密钥**：`BuildConfig.DEEPSEEK_API_KEY` 是构建期烧进 APK 的明文字符串；桌面端目前没有密钥来源。
  给桌面加同样的做法等于把一个共享 key 再发一份。见 §5。
- **无测试基础设施**：仓内没有任何 `test`/`androidTest` 目录，也没有 JS 测试。契约要能自证，必须
  自带 fixture 与校验命令（见 §7）。

## 3. 三层架构

```
┌─ L1 契约层（语言无关、有版本号、各端各存一份副本并对齐哈希）───────────────
│   design-assets/ai-skill/
│     contract.md              给人看的契约（本文的实现版）
│     system-prompt.md         稳定前缀，可缓存
│     tools/create-plan.json   JSON Schema（工具参数）
│     fixtures/*.json          CJK 语料 + 期望结果
│   产出：每个客户端只能消费「提示词字符串 + 工具 schema」，不得各自改写
├─ L2 适配层（每种语言一份，行为必须逐字一致）─────────────────────────────
│   Kotlin : shared/src/main/kotlin/com/hamhuo/tplanner/ai/          → 手机 + 手表
│   JS/TS  : .v5-worktrees/desktop/src/ai/                           → 桌面（Electron 主窗 + 小窗）
│   负责：拼请求 / 多轮 messages 管理 / thinking+reasoning_content 回传 /
│         HTTP 与重试 / 解析与本地兜底 / 遥测；不做任何业务判断
├─ L3 交互层（各端自己画，字段含义相同）──────────────────────────────────
│   提案复核：主题 → 动作 → 子任务 → 时间（明确 / 推测 / 无）
│   手机：提交 Plan → `PlanPreview` 确认后才落盘，输入只在纸上发生（见 §9.1）
│   确认：稳定的 UID + 一次本地事务 + 幂等重确认
│   映射：手机/手表 → ScheduleItem；桌面 → jCal VTODO → sync.persist()
└─────────────────────────────────────────────────────────────────────────
```

「全客户端通用」的落点就在 L1：**同一份提示词、同一份 schema、同一份语料**，四端都消费它。
L2 允许各写一次，但由 §7 的 `--check` 与 fixture 保证一致。

## 4. 契约 v1

### 4.1 请求

```jsonc
{
  "skill": "tplanner.plan-extract@1",     // 语义版本；客户端与服务端资产哈希不一致时拒绝
  "thread_id": "0f4c…",                   // 客户端生成、本机持久化；同一条对话不变
  "turn": 2,                              // 多轮：第几轮
  "locale": "zh-CN",
  "now": {
    "instant": "2026-09-16T11:20:00+08:00", // 时间基准（唯一基准，提示词里强调）
    "time_zone": "Asia/Shanghai",           // 由客户端显式传入，两端统一，不再写死
    "weekday": "周三",
    "relative": { "today": "2026-09-16", "tomorrow": "2026-09-17", "day_after": "2026-09-18" }
  },
  "location_hint": "北京市海淀区",          // 可空；不做定位就不传
  "known_schedule": [                       // 可空。客户端已有的已排期事项（仅时间与标题）
    { "title": "上课", "start": "2026-09-16T14:00:00+08:00", "end": "2026-09-16T15:40:00+08:00" }
  ],
  "thread": [                               // 完整历史：客户端持久化，服务端无状态
    { "role": "user", "content": "下周要交论文，还得剪个视频" },
    { "role": "assistant", "content": "", "reasoning_content": "…", "tool_calls": [/* 原样回传 */] },
    { "role": "tool", "tool_call_id": "call_…", "content": "{\"topics\":[…],\"actions\":[…]}" },
    { "role": "user", "content": "视频那件事放到晚上" }
  ],
  "previous_proposal": { /* 上一轮的 accepted 提案；用于"改一下"而不是重来 */ },
  "limits": { "max_topics": 8, "max_actions": 24, "max_steps_per_action": 8 }
}
```

要点：

- **`now` 由客户端传入，模型不许自己算日期。** 提示词只允许使用这里的基准（现有实现已有这条纪律，保留）。
- **`known_schedule` 是给冲突检测用的输入**，不是让模型去改用户已有的日程。
- `thread` 里带 `reasoning_content` 不是可选优化：**带 `tools` 时必须回传，否则 400**。
- `limits` 是客户端防线，不是提示词礼貌请求；超限由客户端截断并记录遥测。

### 4.2 响应

```jsonc
{
  "understanding": "用户提到三件事：论文、视频、还有周末的聚会。",  // 一句话回显，供用户确认"理解对不对"
  "topics": [
    {
      "id": "t1",                                     // 模型局部 id，仅用于同一响应内引用
      "title": "毕业论文",                             // 独立主题/目标
      "evidence": "下周要交论文",                      // 从用户原话摘的触发片段
      "color_id": 4,
      "actions": [
        {
          "id": "t1a1",
          "title": "改完第三章",
          "kind": "do",                               // do | prepare | communicate | travel | wait | review
          "depends_on": [],                            // 同 topic 内的动作 id；显式顺序
          "subtasks": [                                // 供勾选的清单项，2–5 条
            { "text": "补 3.2 节的实验数据" },
            { "text": "重画图 3-4" }
          ],
          "time": {
            "source": "inferred",                      // stated | inferred | none
            "start": "2026-09-17T19:30:00+08:00",      // source=none 时两字段为 null
            "end":   "2026-09-17T21:00:00+08:00",
            "confidence": 0.55,                        // 0–1；stated 恒为 1
            "basis": "论文无明确时间，但与已排期的『视频剪辑』同属晚间块，估 1.5 小时"
          },
          "note": "老师要求周五前给初稿"
        }
      ]
    }
  ],
  "assumptions": ["视频按 2 小时估算", "周末聚会默认在周六下午"],
  "needs_clarification": []                            // 默认空数组；保持不问反的既有产品决定
}
```

### 4.3 关键设计点：时间只有三种来源

用户要求③（补充猜测时间）与 `docs/sync-v5.md` 现有条款（"A missing time stays missing"）冲突。
处理方式不是放弃一边，而是**把"推测"变成一等公民、同时保持可审计**：

| `source` | 含义 | 写入记录的方式 | UI |
| --- | --- | --- | --- |
| `stated` | 用户明确说了 | 直接写 `DTSTART`/`DUE` | 普通时间 |
| `inferred` | 模型依据 `basis` 推断 | **也写**；有复核界面的客户端必须单独标注 | "预计"样式 + 依据可展开 |
| `none` | 无从推断 | 不写时间（`scheduled=false`） | "未排期" |

三条硬约束：

1. **`inferred` 必须给 `basis`，`confidence < 0.5` 的默认不勾选。** 没有依据的时间不落盘——
   预览界面里这一档默认不打勾，用户也不会在确认时顺手带上。
2. **`inferred` 永远不能变成 `stated`。** 用户在复核界面手动改过、或在时间轴上拖过的时间
   视为用户输入（`stated`），模型不能覆盖。
3. **复核界面（有该界面的客户端）允许"只去掉时间、保留任务"**。这是现有 `scheduled=false`
   路径已经支持的语义，不用新机制。

于是 `docs/sync-v5.md` 的 AI 段落需要同步改写为：*AI 可以提出时间猜测，但必须标注来源与依据，
并且只在用户确认后写入；AI 的猜测不得伪装成用户陈述。* 这条要作为跨端契约变更单独登记（见 §9）。

### 4.4 子任务 vs 动作

- **动作**（`actions[]`）：为达成主题所需的可独立调度步骤。落盘时是**任务本身**（桌面：一条 VTODO；
  手机：一条 `ScheduleItem`）。
- **子任务**（`subtasks[]`）：动作内的勾选项，落盘为 `x-tplanner-checklist`。

现有代码把两者混在 `checklist` 里（提示词明确要求"同一目标的子步骤放 checklist，独立目标另开任务"）。
新契约里 `actions` 是必填数组、`subtasks` 可空。一个"没有动作层的主题"在客户端表现为只勾主题
标题、不勾任何动作——比现在"目标=任务"更贴合用户说的"每个主题的动作"。

### 4.5 模型调用形态（每端一致）

```jsonc
{
  "model": "deepseek-flash",
  "messages": [ /* system = system-prompt.md + now 块；然后 thread 原样 */ ],
  "tools": [ /* create_plan：tools/create-plan.json */ ],
  "tool_choice": "auto",              // 思考模式下不能 required
  "thinking": { "type": "enabled" },  // 首轮提取与时间推理开启
  "reasoning_effort": "low",          // 生成/规划用 low；改时间的追问轮可 disabled
  "max_tokens": 4096,
  "user_id": "tplanner-<deviceId>"    // 用途：缓存隔离与限流分组，不放隐私
  // 不传 temperature：思考模式忽略它，传了只会误导后来的人
}
```

- **首轮**：`thinking=enabled, reasoning_effort=low, tool_choice=auto`，提示词要求"必须先调用
  `create_plan`"（不能靠 `required` 强制，见 §2.1）。
- **追问轮**（"视频那件事放到晚上"）：`thinking=disabled, tool_choice=required` 可用，最省 token；
  或者是 `enabled + auto`。这一条由客户端按"这轮是不是结构化改写"决定，端点写进 L2 适配层。
- **`strict` 模式**：`tools/create-plan.json` 已经按 strict 的形态写好（`strict: true`、每个 object
  `additionalProperties:false`、**所有字段都进 `required`**、`color_id` 用 `enum` 而不是 `minimum/maximum`、
  不用 `minItems`/`maxLength`），`scripts/generate-ai-skill.py` 会校验这些约束，写错了直接报错。
  运行时对应 `base_url=https://api.deepseek.com/beta`。**必须同时保留非 strict 路径**（普通 base +
  `tool_choice:auto` + 客户端校验）：Beta 不可用、或 strict 校验被服务端拒绝时自动降级，用一个开关切换。
- **解析三条路径**（缺一不可）：
  1. `choices[0].message.tool_calls[0].function.arguments` → 严格按 schema 校验；
  2. 无 tool_calls 但 `content` 是 JSON → 解析（思考模式下 `auto` 有可能选择说话）；
  3. 两者都不是或 HTTP 失败 → **本地确定性兜底**（见 §6），并标注 `degraded`。
- **strict 表达不了的约束必须在客户端补**：`start`/`end` 同时给或同时为 null、`end > start`、
  `source=none` 时三个时间字段为 null、`depends_on` 指向存在的动作且不形成环。

### 4.6 缓存前缀

前缀缓存按"完整匹配的缓存单元"命中。因此：

- **system 消息的最前面放 `system-prompt.md`（固定不变），`now` 块放在 system 末尾**，绝不把
  日期放最前面——否则每次请求前缀都变，缓存永远不命中。
- 多轮追加天然命中（`A+B` → `A+B+C`）。
- 遥测要记录 `usage.prompt_cache_hit_tokens` / `prompt_cache_miss_tokens`，
  这两项现在代码里完全没读。

## 5. 密钥边界（必须先决定）

现状：`local.properties`（本机未配置）或环境变量 `DEEPSEEK_API_KEY` 在**构建期**写进
`BuildConfig.DEEPSEEK_API_KEY`。也就是说每个装了这个 APK 的人都能 `strings` 出这把 key。

三个选项：

| 方案 | 做法 | 代价 | 评价 |
| --- | --- | --- | --- |
| A 端上自带 key（现状） | 继续烧进产物 | 任何拿到 APK 的人都能用你的额度；换 key 要重新发版 | 只适合单人自用 |
| B 每端 BYOK | 应用内输入，存本机（Android Keystore / Electron safeStorage），永不进同步、永不进备份 | 每个设备要配一次；用户要自己申请 key | 本期最现实，且能立刻让桌面端跑起来 |
| C 服务端代理 | 服务端加 `POST /tplanner/v5/ai`，key 只在服务器；客户端发同一份契约 | 要动服务端（**源码不在本仓**）、要限流与配额、离线不可用 | 终态；建议 B 落地后立刻排 C |

推荐：**B 先落地 + C 作为终态**。届时 L2 适配层只需把 base URL 从 `api.deepseek.com` 换成
同步服务端，其余（消息、schema、解析、兜底）完全不动——这正是把 L2 做成"可换端点"的原因。
方案 C 还要注意：服务端 runbook 现有条款是"不记录 jCal 正文"，AI 代理会经手用户原文，
必须同样不落正文日志（`docs/ops-runbook.md` §7 的纪律要扩展到 AI 端点）。

## 6. 降级：本地确定性兜底（新能力）

把"AI 不可用"从死路变成可用路径。纯本地、无网络，识别能力弱但确定：

- 按行/分号/「，然后」切分输入；
- 识别 `今天/明天/后天/周[一二三四五六日]/下周[一二三四五六日]` 与 `H点`、`H:MM`、`上午/下午/晚上`；
- 识别 `X前/之前/截止/截止到` → `end`；单独一个时刻 → `start` 且按 1 小时估 `end`（**标 `inferred`**，
  且 `basis="本地规则：只给了开始时刻"`）；
- 每行一条动作，`source` 严格按"文中是否真的出现时间"判定。

价值：断网、key 失效、429、余额不足时功能仍然可用；同时它是"模型乱说"时的第二道防线。
必须与模型路径**走同一个契约结构**，否则两套 UI 状态会分叉。

## 7. 资产分发与一致性

沿用仓库既有的"单一源 + 各端副本"方式（`design-assets/tokens/README.md`）：

```
design-assets/ai-skill/                     ← 唯一人工维护的源（在根仓）
  system-prompt.md            稳定前缀：三层结构、时间三态、字段纪律  ← 已落地
  tools/create-plan.json      工具 schema（strict 形态，全部字段必填） ← 已落地
  assertions.md               fixture 断言词表（两端实现同一套）      ← 已落地
  fixtures/*.json             CJK 语料 + 结构断言                     ← 已落地 3 条
  manifest.json               skill_version + 各文件 sha256（生成物）
scripts/generate-ai-skill.py                ← 复制 + 生成 manifest 哈希，不联网
  （不带开关）        校验源资产并重新生成 manifest.json
  --check             只校验，不写任何文件（清单过期即非零退出）
  --android           同步到 app/src/main/assets/ai-skill/
  --desktop           同步到 .v5-worktrees/desktop/public/ai-skill/
  --check --android   校验副本与源一致
```

规则（**必须写进 README，否则半年后一定会漂移**）：

- 客户端**只能**读自己工作树内的副本，**不得**跨工作树读资产（既有条款，token 包已经这么要求）；
- 手改副本 = 改错地方：`--check` 会失败；
- manifest 记录 `skill_version` 与每个文件的 sha256；客户端启动时记录自己加载的
  `skill_version`，便于排查"某台设备还在用旧提示词"。

当前已验证：源漂移会被 `--check` 拒绝、副本手改会被 `--check --android` 拒绝、
源里删文件后重新同步会清理副本残留。`.v5-worktrees/desktop/public/ai-skill/` 属于
`master` 分支，**需要在桌面工作树里执行 `--desktop` 并单独提交**，不要在本分支代劳。

## 8. UI（各端一致，外壳各异）

手机端已经定稿成"一张纸"的连续状态：`Editing → Submitting → Preview → Committing`
（输入只在纸上发生，`PlanPreview` 是纯确认页）。桌面沿用同一骨架，但确认态从
"平铺任务列表"升级为**可展开的两层结构**：

```
[ 我理解你在做三件事 ]                     ← understanding，一行，可纠错
▾ 毕业论文                        4 个动作   ← topic
    ▾ 改完第三章        预计 明天 19:30–21:00  依据 ▸
        ☑ 补 3.2 节的实验数据   ☑ 重画图 3-4      ← subtasks
    ▸ 读完参考文献        周四 20:00（你写的）
    ▸ 发给老师           时间待定
▸ 视频剪辑 …
[ 继续对话：________ ]                      ← 同一 thread 的下一轮
              [ 只选时间未定的 ] [ 确认 7 项 ]
```

- 时间芯片必须区分 `stated`/`inferred`/`none` 三种样式，`inferred` 可展开看 `basis`；
- `inferred` 且 `confidence < 0.5` 默认**不勾选**；
- 每项可"去掉时间保留任务"；
- 「继续对话」把本轮提案放进 `previous_proposal`，下一轮是**修改**而不是重新提取；
- 确认后：稳定 UID（`uuidv5(thread_id, topic/action/step 路径)`）+ 一次本地事务，重复确认写同一条记录
  （现有 `stablePlanTaskId` 已是这个语义，保留并推广到桌面）；
- **thread 持久化**：客户端本地保存 thread（输入 + 提案 + 对话），重开界面能继续，不丢上下文。
  thread 是设备本地数据，**不进同步**。

手表（Wear）：不做输入，只做**呈现与勾选**——复核列表可以放到手机，手表接收已确认的任务；
如果将来要做，只允许"从几条候选里选一条"，不引入键盘输入。

### 9.1 手机端已实现的部分与尚未实现的部分

| 能力 | 状态 |
| --- | --- |
| 主题 → 动作 → 子任务的三层契约与解析 | ✅ `SkillContract` + `PlanResponseParser` |
| 时间三态（`stated`/`inferred`/`none`）与"必须带依据" | ✅ 解析层强制，低置信度默认不勾选 |
| `deepseek-flash` + `thinking=enabled` + `tool_choice=auto` | ✅ `AiChatTransport.buildBody`（有测试固定） |
| 多轮消息与 `reasoning_content` 回传 | ✅ `AiSkillClient` 内部维护 thread；**界面暂时每轮新建 thread，尚未接"继续对话"** |
| 请求体/schema 从 assets 读取并校验 `skill_version` | ✅ `AiSkillAssets`（读不到就退化为本地兜底，不崩） |
| 本地确定性兜底 | ✅ `LocalPlanFallback`（断网/无 key/结构跑偏都能用） |
| 遥测（`finish_reason`、命中缓存、推理 token、耗时） | ✅ `PlanTelemetry.logLine()`，不含用户正文 |
| 复核界面区分三态并允许"只去掉时间" | ✅ `PlanPreview`（纯确认页，无输入框/提取按钮）+ `ReviewItem`；提交 Plan 后自动出现 |
| 失效提醒如实标注"本地规则识别" | ✅ `R.string.ai_local_fallback`（预览界面里如实提示） |
| 真机联调（真实 key + 真实模型） | ❌ 本环境没有 key，只能靠单元测试与契约测试 |
| 桌面端接入 | ❌ 见 §9 的 P3 |
| 服务端代理（key 不落端上） | ❌ 见 §5 与 P6 |

## 9. 分阶段实施

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| P1 契约定稿 | `design-assets/ai-skill/` 落地：提示词、strict 工具 schema、断言词表、fixtures；`generate-ai-skill.py` 能生成/校验/同步 | **已完成**：`python scripts/generate-ai-skill.py --check` 与 `--check --android` 均通过；语料覆盖 §4.3 三种 `source`。语料从 3 条扩到 12 条（补"改一下"追问、跨时区、非待办输入、模型跑偏）仍属 P1 收尾 |
| P2 手机重写 L2 | **已完成**：`DeepSeekAnalysisService` 删除，拆成 `ai/` 包 —— `AiJson`（契约层 JSON）、`SkillContract`、`PlanResponseParser`、`LocalPlanFallback`、`AiChatTransport`、`AiSkillClient`、`PlanExtractor`、`AiSkillAssets`；模型改 `deepseek-flash`；`thinking=enabled` + `reasoning_effort=low` + `tool_choice=auto`；`reasoning_content` 全程回传；时间基准与时区由 `PlanExtractor.timeContextOf(now)` 传入；提示词/schema 从 APK assets 读取并校验版本 | **45 个单元测试全绿**（`./gradlew :app:testDebugUnitTest`），`assembleDebug` 通过。三类断言已落地：请求体契约（§2.1 的 400 雷区）、解析降级规则、fixture 一致性。**尚未做真机联调**（需要 API key）：多轮追问不报 400、`prompt_cache_hit_tokens` 有值，只有真机能证明 |
| P3 桌面接入 L2 | `src/ai/` 用同一份提示词与 schema；`sync.persist(document)` 写入；未知 `x-` 属性往返不丢 | 桌面与手机对同一语料产出**相同结构**（fixture 断言）；桌面写入后手机快照能看到 |
| P4 本地兜底 | 两端的 `degraded` 路径 + UI 提示 | 断网/坏 key 下仍能提取并确认 |
| P5 UI 三层 | 手机 `PlanPreview` 升级为两层 + 时间芯片（三态已落地：明确 / 预计 / 未排期）；桌面新建同等面板 | 视觉得到确认（两侧截图），`inferred` 可视可去 |
| P6 服务端代理 | `/tplanner/v5/ai`（服务端源码不在本仓，需另一条工作流） | 客户端不再持有 key；runbook 增补"AI 端点不落正文" |

P1–P2 可以只动 `mobile_andorid`；P3 必须在 `.v5-worktrees/desktop`（`master`）里做，
并遵守既有的跨端协调方式：**在 `docs/` 里留一篇协调文档**（参照
`docs/web-desktop-skin-coordination.md`），写清所有权、资产哈希、验收证据。

## 10. 验收与测试（手机端已落地，桌面端待补）

- **契约一致性**：`fixtures/*.json` 里每条语料都给"期望结构断言"（不是期望文字），断言关键字表
  见 `design-assets/ai-skill/assertions.md`。Kotlin 与 JS 各有一个解析测试读**同一份** fixture、
  实现**同一套**断言，断言解析结果一致。这是"全客户端通用"唯一可证伪的证据。
  生成脚本已经校验：fixture 的 `skill` 版本、`now` 四要素、`thread` 非空、断言关键字必须来自词表。
- **纯函数**：动作顺序与时间单调性校验、UID 生成、时间基准注入——都做成纯函数，可无网络测试。
- **遥测字段**（两端同名，便于对照日志）：
  `skill_version`、`thread_id`、`turn`、`source_counts`、`finish_reason`、`prompt_cache_hit_tokens`、
  `prompt_cache_miss_tokens`、`reasoning_tokens`、`elapsed_ms`、`degraded`。
- **失败注入**：429 / 500 / 超时 / 空 `tool_calls` / `finish_reason=length` 截断 / 非法 JSON，
  逐项确认走兜底而不是走空列表。
> **约定**：这些单元测试**不进仓库**（`.gitignore` 排除 `/app/src/test`）。源文件备份在
> `.ai-skill-tests/`（同样被忽略）。恢复四步：
> 1. `mkdir -p app/src/test/java/com/hamhuo/tplanner/ai && cp .ai-skill-tests/*.kt $_`；
> 2. `.gitignore` 删掉 `/app/src/test`；
> 3. `gradle/libs.versions.toml` 加回 `junit = "4.13.2"` 与 `junit = { group = "junit", … }`；
> 4. `app/build.gradle.kts` 加回 `testImplementation(libs.junit)`。
>
> 之所以不把 JUnit 依赖留在提交里：没有测试源时它只是构建噪音。

- **已落地的手机端测试**（`app/src/test/java/com/hamhuo/tplanner/ai/`，45 个用例）：
  `AiJsonTest` 契约层 JSON 读写；`PlanResponseParserTest` 结构性约束与"降级不丢弃"规则；
  `LocalPlanFallbackTest` 本地兜底的时间解析与"不许把推测说成陈述"；
  `AiChatTransportTest` 请求体（`tool_choice=auto`、`reasoning_content` 回传、无 `temperature`、
  strict 走 beta 端点）与响应解析遥测；`SkillContractFixtureTest` 直接读
  `design-assets/ai-skill/` 的同一份语料、按 `assertions.md` 的语义断言结构。
  测试**不需要网络、不需要 API key、不需要 Robolectric**：契约层是纯 JVM 逻辑。

### 10.1 实测记录：`tool_arguments_unparsable` 是怎么来的

第一次真机联调时，界面报的是"AI 服务不可用"，但设备日志（`adb logcat | grep TplannerLLM`）显示
请求本身完全成功：

```
phase=init skill=tplanner.plan-extract@1 keyConfigured=true modelAvailable=true
phase=telemetry … topics=0 actions=0 finish=tool_calls toolCall=true cache_hit=2816 cache_miss=251
phase=route … result=empty reason=tool_arguments_unparsable
```

也就是说：密钥有效、模型活着、工具被调用了，**失败在客户端解析**。用真实提示词与 schema 回放
同一条输入后原因很清楚——用户输入是"测试一下"，模型正确地什么都没提取：

```json
{"understanding":"你只是打了句「测试一下」，没有描述任何待办事项，所以我没有生成主题。",
 "topics":[],"assumptions":[],"needs_clarification":[]}
```

而解析器当时把"空主题且没有附加说明"当成了解析失败（抛异常），于是：
模型答了 → 解析器报错 → 界面说"AI 服务不可用"。**一个正常的"没有待办"被显示成了服务故障。**

修法两条，都在这一节的前提上：

1. **空主题是合法结果**，不是解析失败。`PlanResponseParser` 不再抛异常，界面改为显示模型自己的
   说明（`R.string.ai_no_task`），于是"没有待办"和"服务不可用"终于是两句不同的话。
2. 解析失败时按**结构原因**记日志（工具名、参数长度、异常文本），不记参数正文——正文里是用户原文。

这条经验对 `assertions.md` 的词表直接有用：`empty-topics-when-not-a-task` 不是边角用例，
而是**第一个在真机上暴露出来的路径**。

## 11. 风险与开放问题

| 编号 | 风险 / 问题 | 现状处理 |
| --- | --- | --- |
| AI-01 | 思考模式 + 强制工具互斥；已有代码正好踩在 400 边界上 | §4.5 定 `auto` + 解析三路径；P2 实测 |
| AI-02 | `reasoning_content` 不回传即 400 | 写进契约；thread 必须持久化 |
| AI-03 | 时间"猜测"与 sync-v5 已有条款冲突 | §4.3 的 `source` 三态 + 必须登记契约变更 |
| AI-04 | 桌面/手机时区基准不同 | `now.time_zone` 显式传参；P3 用跨时区语料断言 |
| AI-05 | 密钥在产物里（现状） | §5：B 落地 + C 排期 |
| AI-06 | 本地兜底与模型路径分叉 | 共用同一契约结构；fixture 同时覆盖 `degraded` |
| AI-07 | 服务端源码不在本仓，P6 无法在本工作流完成 | 单独工作流；在此之前只能 B |
| AI-08 | 手表没有输入路径 | 本期不做输入，只做呈现；见 §8 |
| AI-09 | 主题/动作/子任务三层会让确认界面变长 | 默认只展开"有时间或高置信度"的主题；动作默认折叠 |
| AI-10 | 长对话 token 成本 | 前缀缓存 + `known_schedule` 只传时间标题 + thread 上限（`limits`）+ 追问轮关思考 |
| AI-11 | 现有 `DeepSeekAnalysisService.kt` 的包名是 `com.hamhuo.tplanner` 却在 `ai/` 目录下 | 重构 P2 时一并归位到 `com.hamhuo.tplanner.ai` |

## 12. 参考

- DeepSeek [Models & Pricing](https://api-docs.deepseek.com/quick_start/pricing)、
  [Thinking Mode](https://api-docs.deepseek.com/guides/thinking_mode)、
  [Tool Calls](https://api-docs.deepseek.com/guides/tool_calls)、
  [JSON Output](https://api-docs.deepseek.com/guides/json_mode)、
  [Context Caching](https://api-docs.deepseek.com/guides/kv_cache)、
  [Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion)、
  [Error Codes](https://api-docs.deepseek.com/quick_start/error_codes)
- 仓内：`docs/sync-v5.md`、`docs/web-desktop-skin-coordination.md`、
  `design-assets/tokens/README.md`、`app/src/main/java/com/hamhuo/tplanner/ai/DeepSeekAnalysisService.kt`
