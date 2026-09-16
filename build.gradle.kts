import java.io.ByteArrayOutputStream
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

buildscript {
    dependencies {
        // AGP 9 supplies Kotlin; raise its compiler together with the Compose compiler plugin.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

/** Both Android apps package the editable root artwork, without checked-in bitmap copies. */
abstract class GenerateLauncherResources : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceIcon: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val target = outputDirectory.file("mipmap-nodpi/tplanner_launcher_artwork.png").get().asFile
        target.parentFile.mkdirs()
        sourceIcon.get().asFile.copyTo(target, overwrite = true)
    }
}

tasks.register<GenerateLauncherResources>("generateLauncherResources") {
    sourceIcon.set(layout.projectDirectory.file("icon.png"))
    outputDirectory.set(layout.buildDirectory.dir("generated/launcher-res"))
}

// ── 版本管理：git tag 是唯一版本源 ─────────────────────────────────────────
// 发版 = 打 tag `mobile_8.0.0`（scripts/release.ps1 负责校验与打 tag）。
// versionName / versionCode 在构建时由 git 推导，代码里不再手写版本号：
//   - HEAD 恰好打在 mobile_* tag 上 → 正式版：versionName = 8.0.0
//   - HEAD 在 tag 之后       → 开发版：versionName = 8.0.0-dev[-dirty]
//   - 仓库无匹配 tag / 无 git → 用 fallbackVersion 兜底
// versionCode = 主×1000 + 次×100 + 补丁（三位以内无碰撞，单调递增）。
// 注意：旧版装机 versionCode 低于新公式的 8.0.0 = 8000，升级路径保持单调。
// 手机 / 手表两个 APK 用同一版本；若将来上传 Google Play，手表 versionCode 需小于手机。

val fallbackVersion = listOf(8, 0, 0)

// 顶层函数/匿名函数没有 Gradle 的 Project 接收器，这里用纯 JDK 的 ProcessBuilder 跑 git。
val runGit = fun(args: List<String>): String? = try {
    val stdout = ByteArrayOutputStream()
    val proc = ProcessBuilder(listOf("git") + args)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    proc.inputStream.copyTo(stdout)
    if (proc.waitFor() == 0) stdout.toString().trim() else null
} catch (_: Exception) {
    null
}

// 发版 tag 认 mobile_*（新约定）与 PUKEKO_*（历史遗留，仅作兜底）；桌面版用 v*，二者互不干扰。
// 前缀式解析，兼容 describe 输出的 "mobile_8.0.0-24-g7fa3f81" 这类后缀。
val semverRegex = Regex("""^(?:[A-Za-z]+[_-])?v?(\d+)\.(\d+)\.(\d+)""")

fun parseSemver(text: String?): Triple<Int, Int, Int>? {
    if (text.isNullOrBlank()) return null
    val m = semverRegex.find(text.trim()) ?: return null
    return Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
}

val exactTag = runGit(listOf("describe", "--tags", "--match", "mobile_*", "--match", "PUKEKO_*", "--exact-match"))
val nearestTag = runGit(listOf("describe", "--tags", "--match", "mobile_*", "--match", "PUKEKO_*", "--always"))
val dirtyTree = runGit(listOf("status", "--porcelain")).orEmpty().isNotBlank()

val exactVersion = parseSemver(exactTag)
val nearestVersion = parseSemver(nearestTag)
val (verMajor, verMinor, verPatch) = exactVersion ?: nearestVersion
    ?: Triple(fallbackVersion[0], fallbackVersion[1], fallbackVersion[2])

// 版本号只认**已经推送到 origin** 的 tag：本地打了但没推的 tag 不能让产物自称正式版。
// 远端取不到时按"未验证"处理，退化为 -dev。
val exactTagIsPushed = exactTag != null && runGit(
    listOf("ls-remote", "--tags", "--exit-code", "origin", "refs/tags/$exactTag"),
) != null

// versionCode 必须单调递增，否则新包装不上旧包（INSTALL_FAILED_VERSION_DOWNGRADE）。
// 因此它继续按 exact/nearest 的**本地** tag 推导：远端还没有 tag 时版本名会退化，
// 但安装序号不会倒退。
val appVersionCode = verMajor * 1000 + verMinor * 100 + verPatch
val appVersionName = when {
    exactVersion != null && exactTagIsPushed -> "$verMajor.$verMinor.$verPatch"
    exactVersion != null -> "$verMajor.$verMinor.$verPatch-dev" +
        (if (dirtyTree) "-dirty" else "") + "+tag-not-pushed"
    nearestVersion != null -> "$verMajor.$verMinor.$verPatch-dev" + (if (dirtyTree) "-dirty" else "")
    else -> "$verMajor.$verMinor.$verPatch-dev"
}

// 供 :app / :wear 的 defaultConfig 读取（根工程先于子工程求值，顺序有保证）
extra["appVersionName"] = appVersionName
extra["appVersionCode"] = appVersionCode

tasks.register("printVersion") {
    group = "help"
    description = "打印由 git tag 推导出的 versionName / versionCode"
    doLast {
        println("versionName = $appVersionName")
        println("versionCode = $appVersionCode")
    }
}
