# Android / Wear 依赖更新（2026-09-15）

本次按已安装 Android Studio 最高支持 AGP 9.2.1 的约束更新依赖，只采用稳定版本。

## 构建工具

| 项目 | 原版本 | 当前版本 |
| --- | --- | --- |
| Android Gradle Plugin | 9.3.2 | 9.2.1 |
| Gradle Wrapper | 9.7.0 | 9.4.1 |
| Kotlin / Compose compiler | 2.2.10 | 2.4.20 |
| KSP | 2.3.9 | 2.3.12 |
| compileSdk（手机 / 手表） | 35 | 37 |

Gradle 使用 [AGP 9.2 官方兼容表](https://developer.android.com/build/releases/agp-9-2-0-release-notes)
中的配套版本。Kotlin 2.4.20 的支持范围包含此组合，参见
[KGP 兼容表](https://kotlinlang.org/docs/gradle-configure-project.html)。由于 AGP 使用内置 Kotlin，
根构建脚本按[官方方式](https://developer.android.com/build/releases/agp-9-0-0-release-notes#runtime-dependency-on-kotlin-gradle-plugin)
显式引入同版本 KGP，确保实际 Kotlin 编译器与 Compose compiler 一致。
[KSP 2.3.12](https://github.com/google/ksp/releases/tag/2.3.12) 与版本目录统一管理。

CI 使用 `bash gradlew`，避免系统 Gradle 绕过 Wrapper；运行 JDK 21，与仓库 daemon 配置一致。
应用字节码仍面向 Java 17，`minSdk = 26`、`targetSdk = 35`。

## 应用依赖

| 依赖 | 原版本 | 当前版本 |
| --- | --- | --- |
| Core | 1.13.1 | 1.19.0 |
| Lifecycle | 2.8.3 | 2.11.0 |
| Activity / Activity Compose | 1.9.0 | 1.13.0 |
| Compose BOM | 2024.06.00 | 2026.08.00 |
| AppCompat | 1.7.0 | 1.8.0 |
| Wear Watchface | 1.2.1 | 1.3.0 |
| Play Services Wearable | 18.2.0 | 20.0.1 |
| Room | 2.8.4 | 2.8.5 |

版本来源：[AndroidX 稳定版本表](https://developer.android.com/jetpack/androidx/versions)、
[Compose BOM](https://developer.android.com/develop/ui/compose/bom)、
[Google Play services 依赖表](https://developers.google.com/android/guides/setup)。
Wear Compose 1.6.2、WorkManager 2.11.2、DataStore 1.2.1 已是对应稳定版本，继续使用。

Lifecycle 2.11 的 Compose 组件要求 compileSdk 37 和 AGP 至少 9.2.0，两端按此调整。
Activity 和 Activity Compose 共用一个版本键。Core、Lifecycle、WorkManager 已将 Kotlin 扩展
合入主 artifact，直接依赖主包；Room 已有 runtime，移除多余的 room-ktx。

`TPlannerPullToSync` 改用新版 `Modifier.pullToRefresh`，替换已移除的
`isRefreshing / endRefresh / nestedScrollConnection` 接口；同步请求仍来自用户下拉手势，
界面保留现有静态同步提示。

按用户要求，本次未运行构建、lint 或测试。版本依据为官方发布说明及静态代码阅读。
