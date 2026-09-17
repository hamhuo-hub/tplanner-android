# tPlanner

## 功能调整

手机、手表和 AI 排程已移除闹铃设置，应用不再安排或发送日程闹铃；升级后首次启动会清理
旧版已安排的闹铃和通知。提醒能力改为交给系统日历：TPlanner 把排期任务投影进一个应用
自有的本地日历，由系统的提醒、语音助手、手表与小组件去消费。

已移除 Custom Lists 的全部界面与操作，包括新建、选择、归属设置和删除。筛选仅保留
“收件箱”和“今天”。事项的清单标识字段 `x-tplanner-list-id` 继续原样保留，其他客户端
设置过的值不会被本机改写。类型选择器（`event` / `task` / `status`）同样已移除：Sync V5
只有一种可勾选的事项。

“今天”同时显示当天事项和过去未完成的任务，后者归入“已过 / Past”；跨日未完成任务
会继续保留在此视图中，完成后退出过去任务分组。时间轴仍按所选日期显示事项。

## Sync V5：单一数据包

完整契约见 [docs/sync-v5.md](docs/sync-v5.md)。要点：

- 每条记录只有一份事实：一个 RFC 7265 jCal 文档（RFC 5545 语义）。任务用 VTODO，
  日记用 VJOURNAL，重复用标准 RRULE 加 `recurrence-id` 例外与 `exdate`，不再生成
  一整个系列的多条记录。
- 传输元数据（`sequence` / `deviceId` / 回执 / revision）不重复任何任务事实。
- 服务器只有一个 SQLite 写入者，提供 `GET /tplanner/v5/snapshot`、
  `POST /tplanner/v5/batch`、`GET /tplanner/v5/health`，全部要求 Bearer 口令。口令写死在
  两端代码里（`SYNC_PASSWORD`），用户不需要配置任何东西；它只挡扫描流量，不是安全边界，
  所以这个部署的地址不要对外公开。
  没有增量编解码、没有长轮询、没有 V3/V4 兼容端点。
- 写入用 `baseRevision` 做逐记录比较交换：过期写入返回 conflict 而不是覆盖别人的版本，
  冲突草稿保留在本机供用户选择丢弃或重新应用。
- 每个设备有自己的 `deviceId` 与连续 sequence；重试发送的是同一条不可变命令，
  服务器用永久回执幂等应答。

### 手机与手表

手机与手表交换同一份 V5 batch/receipt/snapshot JSON，不再有日程投影专用格式。
手表有自己的设备身份与序列，GMS Data Layer 与无 GMS 时的 RFCOMM 承载同样的字节、
使用同样的幂等规则。手机只是中继：它不会改写手表身份，也不会在服务器提交前声称
中央已接受。

表盘刻度、今天窗口、标题省略与时间排序全部在手表本地从 jCal 计算，跨日或换时区
不需要手机重新生成任何东西。快照就是完整集合，缺席不等于删除；删除以墓碑到达。

### 系统日历

系统日历是**单向、可重试的消费者**，不参与同步，也不会被读回成为任务编辑。只有
“已排期且未完成”的 VTODO 会被投影成应用自有本地日历里的日程；未排期任务与日记
留在 TPlanner 内。权限被拒绝或 Provider 出错都不会影响保存、同步与手表回执。

### 本地存储

本地只有一份存储（`tplanner_v5`）：已安装的中央镜像、待上传的本地文档、唯一一条
在途命令与冲突列表。编辑草稿不是第二份记录，它就是尚未被服务器接受的同一个文档。
自动备份保留这份存储（里面有未同步的离线修改），排除服务器地址、诊断日志与中继回执。

## Hop / 跃时表盘（开发中）

Wear 新增 Hop：当前时间位于屏幕中央，放大的虚拟表盘随时间移动；日程只画与可见窗口
相交的细线和文字。字体按逻辑尺寸与系统字号计算，支持全天、跨日、重叠与极短事项。
实现基线、176–240dp 对照图、原始参考限制和验证方式见 [Hop 设计说明](docs/hop-watchface.md)。
Hop 预览保存在 `design-assets/hop`，表盘资源需随绘制器变更手动更新。

## Android / Wear 8.0.0

本仓库是 Android 手机与 Wear OS 客户端的**独立仓库**（`tplanner-android`），
不是任何仓库的分支。桌面端在 `tplanner-desktop`，中央服务在 `tplanner-server`。
正式标签为 `mobile_8.0.0`。手机、手表应用和两款表盘共享 `shared` 中的设计令牌与同步协议。

同步 UI 遵守单向数据流：用户手势、冷启动或定时刷新只向进程级 `SyncCoordinator`
提交请求；`operationId` 标识真实事务，页面只观察
`saved → uploading → updating → success/error(ERRORxxx)`。切换 今天 / Inbox、
Composable 重组或状态恢复都不得由动画反向触发网络请求。Phone 的 Pull-to-Sync 边界只有
一个，业务事务进行中切 Tab 只会继续展示同一个 `operationId`。

Wear 应用和表盘读取同一个已提交投影。投影安装通过 SharedPreferences 监听即时通知活动
Renderer 并 `invalidate()`；60 秒重读仅是进程异常恢复兜底。系统表盘选择器直接引用
`drawable-nodpi` 静态 PNG，避免三星选择器无法展开 vector/layer-list 而显示黑屏。

发布前仍需完成以下真机验收（JVM 无法覆盖系统 hit-test 与厂商 picker）：

- 全屏 Note 编辑器的标题、正文间距、四周 padding 和空白区域均吞掉触摸，背后详情页不得响应。
- 手表系统 picker 与 Galaxy Wearable 中 Tide / Next 均显示真实缩略图；快照安装后手表应用与活动表盘应在同一次提交后立即刷新。
- 关闭 GMS 或断开蓝牙后连续新建至少 3 项，pending 均保留；重新连接一次应批量上行，
  服务器回执返回后仍可见，收到覆盖该回执版本的新快照后才终结。
- 同一请求同时经 Data Layer 与 RFCOMM 到达时，服务器只出现一组命令；手机进程重启后
  重复发送仍返回同一份回执，不产生第二组命令。
- 打开系统日历开关并授予权限后，已排期任务出现在应用自有日历中；完成任务或删除后
  对应日程消失，未排期任务不产生任何日程。

## 手机主界面：一天一屏

界面只有这一套排版：不再把「随手记」和「时间线」分成两个 Tab，也不再按屏宽分叉出
"宽屏两栏"版本（原 `screenWidthDp < 840` 分支已删除）。折叠屏与外接大屏暂时复用同一排版，
更通用的自适应方案（分栏、铰链、后置屏）以后单独设计，`MainLayout` 里不预留分叉。

- 状态栏不占位（`MainActivity.applyImmersiveMode`）：内容从屏幕顶端开始，边缘下滑仍可临时唤出。
- 时间轴**只画今天**：没有顶部日期条（`TimelineDayHeader` 已删除），也没有右下角的加号；
  长按空白处仍然能在指定时刻落一个任务，新建入口只剩时间轴与 Inbox。
- 时间刻度写在左侧窄槽里、数字旋转 90° 自下而上读，横向只占一行字高，宽度来自
  `component.agenda.timeGutterWidth`。
- 底部 mini note 是唯一的写入口。点开后升起的面板**完全不透明**：顶部的缝留给 "Note"
  字样与关闭入口，面板本身只有上面两个圆角，背后不会透出时间轴。
- 保存（对勾）就是全部：提交这一天的 Note，并立刻用正文开一次识别预览。note 面板里没有
  "提取"按钮（识别一定会跑），但**预览界面保留**——识别结果先在预览里过一眼，勾选/去掉时间/
  确认之后才写入日程；不确认就什么都不会落盘。
- 未保存时按返回（或点关闭）：先收起输入法，再弹出确认弹窗；右下角是对勾保存与「丢弃」，
  「继续编辑」在左下角。丢弃只是把本机排队中的那份文档拿掉，不写回退命令、不动服务器版本。

这些尺寸与圆角全部来自 `design-assets/tokens/tplanner-light.tokens.json` 的
`component.agenda` / `component.note`，颜色仍取 `semantic.color.*`。

Android 业务 UI 的品牌色、重复语义字号和圆角只能来自 `shared/designsystem`；表盘可以拥有
独立艺术调色，但色值必须集中在 `TPlannerWatchFacePalette`，不得散落进 Renderer。padding、
触摸目标、viewport 与参数化表盘几何属于真实布局测量值，可保留在组件本地。完整规则与品牌
资源说明见 `design-assets/README.md`。修改 UI token、launcher 或表盘预览后运行：

```powershell
pwsh scripts/check-android-brand-assets.ps1
pwsh scripts/generate-watch-previews.ps1
```

## 领域词汇

### 核心模型

| 术语 | 说明 |
|------|------|
| `JcalDocument` | 唯一的事实载体：一个 RFC 7265 VCALENDAR |
| `ScheduleItem` | `JcalDocument` 的只读投影，供 UI 渲染与编辑 |
| `CheckItem` | 文档内 `x-tplanner-checklist` 的一行，不是独立任务 |
| VTODO / VJOURNAL | 任务 / 日记两种内容组件 |

### 应用层

| 术语 | 说明 |
|------|------|
| `ScheduleItemStore` | 事项存储门面：读写 `V5Store` 中的 jCal 文档 |
| `JcalProjection` | 文档 ↔ `ScheduleItem` 的投影与重复展开 |
| `ScheduleItemEditor` | 事项创建 + 编辑 UI |
| `ScheduleItemDetailScreen` | 事项详情编辑页 |
| `ScheduleItemActions` | 事项操作（创建 / 编辑 / 删除 / 冲突处理） |
| `V5Store` | 本地唯一存储：镜像 / 队列 / 在途命令 / 冲突 |
| `V5Sync` | 唯一的同步运行时（前台泵 + WorkManager 兜底） |
| `SyncCoordinator` | 进程级事务协调器，只负责 operationId 与阶段状态 |
| `TPlannerCalendarProjection` | 单向系统日历消费者入口 |

### 时间线

| 术语 | 说明 |
|------|------|
| `TimelineItemMetadata` | 事项在时间格中的布局元数据 |
| `TimelineItemCard` | 时间线事项卡片 |
| `TimelineItemLayer` | 时间线事项渲染层 |

### AI 辅助

设计稿与实施进度见 [docs/ai-skill.md](docs/ai-skill.md)：把「写日程」提取器改成
**一份契约、四端消费**的能力（手机 / 手表 / 桌面 / 服务端代理）。
手机端已落地（`app/src/main/java/com/hamhuo/tplanner/ai/`），桌面端待接入。

识别结果分三层：**主题 → 动作 → 子任务**。时间分三种来源：`stated`（用户明确说了）、
`inferred`（模型按依据推测，必须带依据与置信度，低置信度默认不勾选）、`none`（推不出来）。
模型不可用（断网 / 没 key / 429 / 结构跑偏）时自动退化为本地规则识别，界面会如实标注。

契约资产唯一源在 `design-assets/ai-skill/`（`system-prompt.md`、`tools/create-plan.json`、
`fixtures/`、`assertions.md`），由 `scripts/generate-ai-skill.py` 同步到各端自己的工作树：

```powershell
python scripts/generate-ai-skill.py --android           # 同步到 app/src/main/assets/ai-skill
python scripts/generate-ai-skill.py --check --android   # 校验副本没有漂移
```

客户端只读自己工作树内的副本，不跨工作树读资产；手改副本会被 `--check` 判为不一致。

| 术语 | 说明 |
|------|------|
| `topic` | 一段话里的一个独立主题/目标；彼此独立的事情必须分开 |
| `action` | 为达成某个主题要做的一个动作；落盘时就是一个任务记录 |
| `subtask` | 动作内部的勾选项；落盘为 `x-tplanner-checklist` |
| `time.source` | 时间来源：`stated` 用户明确说了 / `inferred` 有依据的推测 / `none` 推不出来 |
| `ProposedTask` | 旧手机端提取结果（单层目标 + 清单）；由 `topic` / `action` 取代 |

### 变量命名

| 旧 | 新 |
|----|----|
| `editingEvent` | `editingItem` |
| `beginNewEvent` | `beginNewItem` |
| `pendingNewEvent` | `pendingNewItem` |
| `eventConflict` | `itemConflict` |
| `openEvent` | `openItem` |

## 发版（版本号管理）

版本号以 git tag 为唯一来源，代码里不再手写。**只认已经推送到 origin 的 tag** ——
本地打了但没推的 tag 不会让 APK 自称正式版，版本名会退化成 `-dev`。

> **tag 命名约定**：master（桌面版）用 `v*`，mobile_andorid（Android）用 `mobile_*`，
> 两者互不重叠。历史前缀 `TUI_*`/`desktop_*`（桌面）、`PUKEKO_*`（Android）仅作
> `git describe` 兜底，不再用于新发版。

1. 发版 = 打 tag：`.\scripts\release.ps1 8.2.0`（可选 `-Push` 推送远程），生成 `mobile_8.2.0`
2. 构建时由根 `build.gradle.kts` 用 `git describe` 推导：
   - HEAD 恰好在**已推送**的 tag 上 → `versionName = 8.2.0`
   - HEAD 在 tag 之后 → `versionName = 8.2.0-dev`（可带 `-dirty`）
   - tag 只在本地、没推 → `8.2.0-dev+tag-not-pushed`
3. `versionCode = 主×1000 + 次×100 + 补丁`。它**仍然按本地 tag 推导**：Android 拒绝安装
   versionCode 更低的包（`INSTALL_FAILED_VERSION_DOWNGRADE`），所以安装序号绝不能因为
   "远端还没推 tag" 而倒退。
4. 查看将生成的版本：`.\gradlew.bat printVersion`
