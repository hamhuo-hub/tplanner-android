# Mobile / Wear 浅色迁移协调与冲突记录

## 统一输入与所有权

- 起点：`111b90d`。读取 `design-assets/tokens/README.md`、`tplanner-light.tokens.json`、生成 Kotlin 和 `docs/token-migration.md` 后实施。
- 源 JSON SHA-256：`3C3F45D1A715D2B8FF146BCAAD37D5CF6A7B35787D5B12E67D1D3F86BBD231FB`。本批不修改颜色/尺寸令牌源，也不改 Web / Desktop 的生成副本。
- Web / Desktop 并行范围见 [对应协调记录](web-desktop-skin-coordination.md)。该任务使用同一基线；Android 只扩展生成器的显式 `--android` 接入选项，原资产导出保持兼容。
- 所有 agent 先盘点 Activity / 组件，复用现有入口或提取小型公共组件，再换肤。只有主 agent 运行全量构建并提交，避免共享构建目录竞争。

| 负责人 | 独占修改范围 |
| --- | --- |
| 主 agent | `shared/` 共享 View、生成令牌区块和资源；生成器、品牌检查、两个模块共享资源 source set；本记录和集成验证 |
| Phone agent | `app/.../ui/**`、手机主题资源及 Manifest；不碰 timeline 或 shared |
| Timeline agent | `app/.../timeline/**`；不修改时间、分列、拖动等算法 |
| Wear agent | `wear/.../ui/**`、`create/**`、应用主题资源及 Manifest；不修改 watchface、图片和表盘 metadata |

发现同文件修改或令牌缺口先报告主 agent，在此记录决策后再实施，不覆盖他人改动。

## Activity 与复用路径

| 应用入口 | 现有内容 | 复用后接入点 |
| --- | --- | --- |
| Phone `MainActivity` | `MainScreen` 组装日记、收件箱、时间轴；底栏、列表选择、创建/编辑、整理、日志及草稿冲突弹窗 | `Theme.kt` / MaterialTheme；`TPlannerComponents` 主/次按钮与字段；共享 TaskUnit / SyncFeedback |
| Phone `TimelineScreen` | Grid、DayHeader、ItemLayer、ItemCard、StatusStrip、ConflictBadge、AddButton | 复用 ItemCard / StatusStrip，提取 `TimelineItemVisuals` 统一类别与状态配对 |
| Wear `MainActivity` | `NextDashboardView`、列表、同步、权限、滑删 | WearUiStyle、共享 TaskUnit / SyncFeedback、浅色 dashboard 控件 |
| Wear `ListSelectionActivity` / `TaskDetailActivity` | 列表选择、详情 | 原 `WearPageActivity` 及公共 View 按钮/表面 |
| Wear `CreateTitle/Type/Time/Date/SettingsActivity` | 五步创建、表冠、字段与确认操作 | 复用 `CreationViews`；滚动容器、可增长字段和统一主/次操作 |

## 冲突与决策

| 编号 | 冲突 | 本批处理 | 状态 |
| --- | --- | --- | --- |
| A-01 | Kotlin 资产尚未编入应用；新增独立 raw 颜色文件会破坏唯一 ARGB 入口 | 生成器用 `--android` 管理 canonical `TPlannerDesignTokens.kt` 的独立区块；生成共享 XML 颜色供启动窗口和系统控件使用 | 已完成 |
| A-02 | 表盘与业务 UI 都使用旧 `TPlannerColors`，全局替换会改变 Tide / Next 艺术和 ambient | 保留旧表盘依赖；业务 adapter / 共享组件显式消费 `TPlannerLightTokens` | 已完成，表盘源码/metadata/图片无改动 |
| A-03 | GOLD 同时承担填充、文字、边框；白字和 BG-on-GOLD 在浅色下失效 | 拆为 Accent / AccentText / OnAccent / Focus；必要控件边界使用 BorderControl | 已完成 |
| A-04 | 类别背景混色、白字、整行 .25/.45/.75 透明度损害可读性 | 保留 colorId 0..7；类别 foreground/background 成对；完成状态显式前景与划线，不整体变透明 | 已完成 |
| A-05 | 手机时间轴 6.5–11sp、28dp 状态条及 22dp 冲突标记依赖现有几何 | 本批保留时间轴布局和密集刻度尺寸；常规页面采用 Phone profile。后续应成组迁移排布与命中区，不独立扩大文字破坏布局 | 已记录边界 |
| A-06 | Wear 创建页有固定坐标、22/24dp 操作与固定高度输入，不适合字体缩放 | 复用滚动安全容器与公共字段/按钮，使用 48dp 操作目标；时间/日期字段在 fontScale >1.3 或宽度 <176dp 时叠列，保持创建路由和表冠逻辑 | 已完成，圆屏实机视觉待验收 |
| A-07 | 手机 DayNight / Wear DeviceDefault 可能在启动及系统字段泄漏深色 | 显式浅色主题与生成资源；API 27 导航栏属性、API 29 forceDarkAllowed 分版本资源，保持 minSdk 26 | 已完成，构建与 lint 通过 |
| A-08 | 日记 WebView 资产自带黑金 CSS，且 CSP 原本拒绝外部样式 | 复用同源生成 CSS 的本地 Android asset；仅 style-src 增加 self，保留脚本和内容渲染协议 | APK 内 HTML/CSS 与源文件逐字节一致；实际 WebView 待设备验收 |
| A-09 | Phone TaskWidget 的 now 在 remember 中固定，状态分组可能过时 | 属于原有业务时间更新问题，本批不改变；另行修复状态刷新 | 已记录边界 |
| A-10 | Web 并行任务确认 completedForeground 对 disabledBackground 仅约 4.405:1 | 完成/已过条目使用普通 surface、完成前景和 opacity=1，disabled 背景仅供禁用控件 | 已协调 |
| A-11 | 移除旧透明度后，已完成但处于当前时间段的任务仍可能显示“现在”与当前背景 | Phone 调用方排除已完成状态；共享 TaskUnit 对 completed/past 优先使用完成前景和普通背景 | 已修复 |
| A-12 | 焦点态直接替换主按钮深色边框会丢失原有轮廓 | Phone / Wear 保留按钮正常边界，使用 Button.Focus 的颜色、2dp 宽度和 2dp 间距 | 已修复 |
| A-13 | 窄列和大字体可能挤掉共享任务行最后一个状态徽标 | 依据实际可用宽度和文字测量宽度切换徽标横排/纵排，单个过长徽标保留省略号 | 已修复，待设备视觉验收 |
| A-14 | Wear 圆屏顶部的可用宽度小于矩形屏幕宽，放大同步文字可能靠近裁切区 | 圆屏提示限制为屏宽 70%，顶部留出直径 16%；此处弦宽约 73%，保留几何余量；矩形屏保留原位置 | 已处理几何边界，未声称实机验证 |

## 验证记录

- 已完成 Phone MainActivity、时间轴及 Wear 8 个 Activity 的盘点和复用迁移；CreateTypeActivity 通过公共 CreationViews 与 Activity 主题接入，无需重复修改。
- 最终 `:app:assembleDebug :app:lintDebug :wear:assembleDebug :wear:lintDebug` 已通过（34 秒，95 tasks）。lint 无错误，Phone 73 / Wear 69 个 warning，包含生成色板未全部引用、KTX 建议及已有 RenderScript 兼容实现弃用提示；报告位于各模块 `build/reports/lint-results-debug.html`，未添加 lint baseline 或扩大 suppression。
- `scripts/check-android-brand-assets.ps1` 通过：8 份导出、393 个令牌、54 个必需对比度配对均通过；2 个 Hop 艺术诊断项保持已确认基线，不作为业务配色验收结果。原 JSON SHA-256、旧 Kotlin 令牌区块、表盘源码/metadata 和原 5 份生成资产均核对未变。
- 手机 APK 已检查包含 `assets/md_viewer.html` 与 `assets/tplanner_light.css`，两者均与本批源码逐字节一致。
- 初始 Wear 真机 SM-R870 在线，后续 ADB 已断开；本次未安装 APK、未执行真机视觉验收。当前没有已配置 AVD。待设备可用后验收小圆屏、fontScale 1.3/2、创建五步、表冠、滑删与同步提示；系统输入法界面仍由系统控制。
- 不恢复另一并行任务删除的测试代码或基础设施；使用当前构建、lint、令牌/品牌检查和可用设备进行验证。

## 本地交付

- Phone APK：`app/build/outputs/apk/debug/app-debug.apk`
- Wear APK：`wear/build/outputs/apk/debug/wear-debug.apk`
- 集成日志：`build/android-light-final-build.log`
- 生成/核对命令：`python scripts/generate-design-tokens.py --android` / `python scripts/generate-design-tokens.py --check --android`。以上 APK/日志属于本地产物，不纳入 Git。

## 后续批次：手机主界面合并（一天一屏 + Plan）

上面记录的 JSON SHA 属于浅色迁移那一批。之后的批次把手机主界面收成一套排版，并逐步删掉
与"一天一屏"冲突的入口。当前状态：

- 手机底栏**已删除**（`PhoneTabBar.kt` 移除，`ChromeMode` / `PRIMARY_NAVIGATION_VISIBLE_MILLIS`
  一并消失）。主界面只剩 Timeline + 底部 Plan 输入条 + 右上角控制器（`ui/DayControlDock.kt`）：
  一个 `Transition(expanded)` 让同一枚控制点长成控制组，内含 Inbox/今天、连体 `‹ ›` 日期
  胶囊、设置。`MainLayout` 因此只剩 `showInbox` 一个开关。
- 控制器收敛成"一列独立圆形按钮"：44dp 圆 + 20dp 图标是右上角唯一的形状语言，父布局完全
  透明、每枚圆自带 `component.dock.surfaceOpacity` 这一层 material，没有父底板、没有 border
  （避免圆角处的白色 halo）。收起与展开是两套内容、同一时刻只存在一套——此前两者同时绘制，
  控制点和展开后的某一枚会叠在同一位置。Inbox 也不再是长方形文字按钮，改用图标。
  morph 动画暂缓：先把形状与层级做对。
- Plan 输入条改成"一行可以写的地方"：整条可点，右侧只是一个很小的 `▲`（次级色，按住/聚焦
  才变强调色），没有圆形底色与橙色大块；`component.plan.barClearance` 随导航岛一起删除。
- 换日在控制器里，替换掉原来的日期条；换完短暂浮出「M月d日 · 周X」（`DayFlashLabel`），
  不在今天时控制器显示当天日期数字。
- 按屏宽分叉的"宽屏两栏"排版已删除（`isPhone` / `screenWidthDp < 840` / `notesCard` /
  `NotesHeader`），折叠屏与大屏暂时复用同一套排版；更通用的自适应方案以后单独设计。
- 已删除的旧入口：顶部日期条（`TimelineDayHeader`、`TimelineDateWindow`、日期选择器）、
  时间轴右下角加号（`TimelineAddButton`）、Plan 面板里的 AI 提取按钮。状态栏改为沉浸隐藏
  （`MainActivity.applyImmersiveMode`）。
- 时间轴只画今天，时间刻度是旋转 90° 的纵向数字，刻度宽度取 `component.agenda.timeGutterWidth`。
- 底部那张纸（Plan）是唯一写入口：`ui/DayPlanPanel.kt` 的收起 / 展开 / 未保存三态。
  展开的面板完全不透明（因此没有遮罩透明度令牌），顶部留缝放 "Plan" 字样。
- AI 提取的交互定稿：**保留"结果预览"，删掉"输入页面"**。`UntangleSheet` 收缩为
  `ui/PlanPreview.kt`（纯确认页：没有输入框、没有"提取"按钮、没有面向用户的诊断信息），
  输入只发生在纸上；`MainScreen` 用 `PlanStage`
  （Collapsed / Editing / Submitting / Preview / Committing）驱动同一张纸的连续状态，
  ✓ 的语义是"交给 TPlanner 整理"而不是保存（`submitPlanForPreview`）。
- 时间在预览里只呈现三个层级：明确时间 / 「预计」（点开才展开依据）/ 「未排期」；本地兜底与
  assumptions 只进 Logcat。确认后先让新卡片亮起（`revealedEventIds` → 0.96→1.0）再收纸。
  位置提示暂不采集。
- 内边距只在各自使用方让位一次：主界面卡片用 `systemBars`，Plan 面板与预览界面用
  `systemBars ∪ ime`（由调用方传入），根节点不再统一加，避免键盘顶起时叠加两次。
- 令牌：新增 `component.plan.*`（`barHeight` / `barRadius` / `sheetRadius` / `sheetTopGap` /
  `sheetBottomBarHeight`，原 `component.note.*` 随代码一起更名；`barClearance` 随底栏删除）。
  颜色仍全部来自既有语义令牌，没有新增色值，对比度配对数量不变（54 必需 + 2 诊断）。
- 令牌源现状：`design-assets/tokens/tplanner-light.tokens.json`
  SHA-256 `533C6EF5F06BD743CB103722659BE268B0BEECD83B92B8C7B6B86985ED5F2FE0`，
  导出 401 个令牌。改动后必须重跑生成器与品牌检查。
