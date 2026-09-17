package com.hamhuo.tplanner.ai

/**
 * 契约资产的加载与自检。
 *
 * 资产由 `scripts/generate-ai-skill.py --android` 写入 `app/src/main/assets/ai-skill/`，
 * 唯一源在 `design-assets/ai-skill/`。**客户端只读自己 APK 内的副本**，不跨工作树读，
 * 也不在代码里内联提示词——内联就一定会有第二份真相。
 */
interface AiSkillAssets {
    val skillVersion: String
    val systemPrompt: String
    val toolSchema: AiJson.Obj

    companion object {
        const val ASSET_ROOT = "ai-skill"
        const val PROMPT_ASSET = "$ASSET_ROOT/system-prompt.md"
        const val TOOL_ASSET = "$ASSET_ROOT/tools/create-plan.json"
        const val MANIFEST_ASSET = "$ASSET_ROOT/manifest.json"

        /**
         * 从 APK 资产构造。**资产缺失或版本不匹配时抛异常**，不静默降级：
         * 提示词是版本化契约，读不到旧版提示词就应当退回本地兜底，而不是拿半个契约去问模型。
         */
        fun load(readAsset: (String) -> String): AiSkillAssets {
            val manifest = AiJson.parse(readAsset(MANIFEST_ASSET)) as? AiJson.Obj
                ?: throw AiJsonException("manifest.json 的根必须是对象", 0)
            val version = manifest.string("skill_version")
                ?: throw AiJsonException("manifest.json 缺少 skill_version", 0)
            if (version != AI_SKILL_VERSION) {
                throw AiJsonException(
                    "契约版本不匹配：APK 内是 $version，代码要求 $AI_SKILL_VERSION；" +
                        "请运行 python scripts/generate-ai-skill.py --android",
                    0,
                )
            }
            val prompt = readAsset(PROMPT_ASSET)
            if (prompt.isBlank()) throw AiJsonException("system-prompt.md 为空", 0)
            val tool = AiJson.parse(readAsset(TOOL_ASSET)) as? AiJson.Obj
                ?: throw AiJsonException("create-plan.json 的根必须是对象", 0)
            val name = tool.string("name")
            if (name != AI_SKILL_TOOL_NAME) {
                throw AiJsonException("工具名应为 $AI_SKILL_TOOL_NAME，实际是 $name", 0)
            }
            return Loaded(version, prompt, tool)
        }

        /** 离线测试与纯 JVM 场景用：直接给定三份资产内容。 */
        fun of(systemPrompt: String, toolSchema: AiJson.Obj): AiSkillAssets =
            Loaded(AI_SKILL_VERSION, systemPrompt, toolSchema)
    }

    private data class Loaded(
        override val skillVersion: String,
        override val systemPrompt: String,
        override val toolSchema: AiJson.Obj,
    ) : AiSkillAssets
}
