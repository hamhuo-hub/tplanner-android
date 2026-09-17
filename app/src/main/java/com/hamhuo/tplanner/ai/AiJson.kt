package com.hamhuo.tplanner.ai

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * 一个极小的 JSON 读写实现，只为契约层服务。
 *
 * 为什么不用 `org.json`：契约的解析与校验是本能力的**唯一共享逻辑**，它必须在纯 JVM 单元测试里
 * 无缝可测（不需要 Robolectric），也必须与桌面端的 JS 实现行为一致。`org.json` 在单元测试里是
 * 桩实现，会把这个能力最需要测试的部分变成不可测；而契约用到的 JSON 子集很小，
 * 自己实现反而更容易保证"两端一致"。
 *
 * 支持：object / array / string（含 `\uXXXX` 转义）/ number / boolean / null。
 * 不支持也不需要：注释、尾随逗号、`NaN`/`Infinity`。遇到非法输入抛 [AiJsonException]。
 */
class AiJsonException(message: String, val offset: Int) : Exception("$message (偏移 $offset)")

sealed interface AiJson {
    data class Obj(val entries: LinkedHashMap<String, AiJson>) : AiJson {
        operator fun get(key: String): AiJson? = entries[key]

        fun string(key: String): String? = (entries[key] as? Str)?.value

        fun double(key: String): Double? = (entries[key] as? Num)?.value

        fun int(key: String): Int? = double(key)?.let {
            if (it.isFinite() && it == Math.floor(it)) it.toInt() else null
        }

        fun bool(key: String): Boolean? = (entries[key] as? Bool)?.value

        fun array(key: String): Arr? = entries[key] as? Arr

        fun obj(key: String): Obj? = entries[key] as? Obj

        /** 只看这一层：缺键返回 null，显式 `null` 也返回 null。 */
        fun orNull(key: String): AiJson? = entries[key]?.takeIf { it !is Null }
    }

    data class Arr(val items: List<AiJson>) : AiJson

    data class Str(val value: String) : AiJson

    data class Num(val value: Double) : AiJson

    data class Bool(val value: Boolean) : AiJson

    data object Null : AiJson

    companion object {
        fun parse(text: String): AiJson = Reader(text).parseDocument()

        fun objOf(vararg pairs: Pair<String, AiJson>): Obj =
            Obj(LinkedHashMap<String, AiJson>().apply { pairs.forEach { put(it.first, it.second) } })

        fun objOf(pairs: Map<String, AiJson>): Obj = Obj(LinkedHashMap(pairs))

        fun arrayOf(items: List<AiJson>): Arr = Arr(items)

        fun of(value: String?): AiJson = value?.let { Str(it) } ?: Null

        fun of(value: Int): AiJson = Num(value.toDouble())

        fun of(value: Double): AiJson = Num(value)

        fun of(value: Boolean): AiJson = Bool(value)
    }
}

/** 序列化。键顺序保持插入顺序，便于日志与 fixture 对照。 */
fun AiJson.render(): String = StringBuilder().also { write(it) }.toString()

private fun AiJson.write(out: StringBuilder) {
    when (this) {
        is AiJson.Obj -> {
            out.append('{')
            entries.entries.forEachIndexed { index, (key, value) ->
                if (index > 0) out.append(',')
                writeString(out, key)
                out.append(':')
                value.write(out)
            }
            out.append('}')
        }

        is AiJson.Arr -> {
            out.append('[')
            items.forEachIndexed { index, item ->
                if (index > 0) out.append(',')
                item.write(out)
            }
            out.append(']')
        }

        is AiJson.Str -> writeString(out, value)
        is AiJson.Num -> out.append(renderNumber(value))
        is AiJson.Bool -> out.append(if (value) "true" else "false")
        AiJson.Null -> out.append("null")
    }
}

/** 整数不写成 `1.0`，保证同一条消息在任何端序列化后逐字一致（前缀缓存依赖这一点）。 */
private fun renderNumber(value: Double): String {
    if (!value.isFinite()) throw AiJsonException("不能序列化非有限数值", 0)
    if (value == Math.floor(value) && Math.abs(value) < 1e15) return value.toLong().toString()
    return value.toString()
}

private fun writeString(out: StringBuilder, value: String) {
    out.append('"')
    for (character in value) {
        when (character) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            '\b' -> out.append("\\b")
            '\u000C' -> out.append("\\f")
            else -> if (character < ' ') {
                out.append("\\u").append("%04x".format(character.code))
            } else {
                out.append(character)
            }
        }
    }
    out.append('"')
}

private class Reader(private val text: String) {
    private var index = 0

    fun parseDocument(): AiJson {
        skipWhitespace()
        val value = parseValue()
        skipWhitespace()
        if (index != text.length) throw AiJsonException("JSON 结束后还有多余内容", index)
        return value
    }

    private fun parseValue(): AiJson {
        if (index >= text.length) throw AiJsonException("JSON 意外结束", index)
        return when (val character = text[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> AiJson.Str(parseString())
            't' -> parseLiteral("true", AiJson.Bool(true))
            'f' -> parseLiteral("false", AiJson.Bool(false))
            'n' -> parseLiteral("null", AiJson.Null)
            else -> if (character == '-' || character in '0'..'9') parseNumber()
            else throw AiJsonException("非法字符 '$character'", index)
        }
    }

    private fun parseObject(): AiJson.Obj {
        expect('{')
        val entries = LinkedHashMap<String, AiJson>()
        skipWhitespace()
        if (peek() == '}') {
            index++
            return AiJson.Obj(entries)
        }
        while (true) {
            skipWhitespace()
            if (peek() != '"') throw AiJsonException("对象的键必须是字符串", index)
            val key = parseString()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            // 重复键以后出现的为准：模型偶发重复键时不要整条丢弃。
            entries[key] = parseValue()
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                '}' -> {
                    index++
                    return AiJson.Obj(entries)
                }

                else -> throw AiJsonException("对象里缺少 ',' 或 '}'", index)
            }
        }
    }

    private fun parseArray(): AiJson.Arr {
        expect('[')
        val items = mutableListOf<AiJson>()
        skipWhitespace()
        if (peek() == ']') {
            index++
            return AiJson.Arr(items)
        }
        while (true) {
            skipWhitespace()
            items += parseValue()
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                ']' -> {
                    index++
                    return AiJson.Arr(items)
                }

                else -> throw AiJsonException("数组里缺少 ',' 或 ']'", index)
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            if (index >= text.length) throw AiJsonException("字符串没有闭合", index)
            when (val character = text[index++]) {
                '"' -> return out.toString()
                '\\' -> out.append(parseEscape())
                else -> if (character < ' ') {
                    throw AiJsonException("字符串里出现未转义的控制字符", index - 1)
                } else {
                    out.append(character)
                }
            }
        }
    }

    private fun parseEscape(): String {
        if (index >= text.length) throw AiJsonException("转义序列不完整", index)
        return when (val escape = text[index++]) {
            '"' -> "\""
            '\\' -> "\\"
            '/' -> "/"
            'b' -> "\b"
            'f' -> "\u000C"
            'n' -> "\n"
            'r' -> "\r"
            't' -> "\t"
            'u' -> {
                if (index + 4 > text.length) throw AiJsonException("\\u 转义不完整", index)
                val hex = text.substring(index, index + 4)
                val code = hex.toIntOrNull(16) ?: throw AiJsonException("\\u 转义不是十六进制", index)
                index += 4
                code.toChar().toString()
            }

            else -> throw AiJsonException("未知转义 '\\$escape'", index - 1)
        }
    }

    private fun parseNumber(): AiJson.Num {
        val start = index
        if (peek() == '-') index++
        while (index < text.length && text[index] in '0'..'9') index++
        if (index < text.length && text[index] == '.') {
            index++
            while (index < text.length && text[index] in '0'..'9') index++
        }
        if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
            index++
            if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
            while (index < text.length && text[index] in '0'..'9') index++
        }
        val literal = text.substring(start, index)
        val value = literal.toDoubleOrNull() ?: throw AiJsonException("非法数字 '$literal'", start)
        return AiJson.Num(value)
    }

    private fun parseLiteral(literal: String, value: AiJson): AiJson {
        if (!text.startsWith(literal, index)) throw AiJsonException("非法字面量", index)
        index += literal.length
        return value
    }

    private fun peek(): Char =
        if (index < text.length) text[index] else throw AiJsonException("JSON 意外结束", index)

    private fun expect(character: Char) {
        if (peek() != character) throw AiJsonException("期望 '$character'", index)
        index++
    }

    private fun skipWhitespace() {
        while (index < text.length && text[index].isJsonWhitespace()) index++
    }

    private fun Char.isJsonWhitespace(): Boolean =
        this == ' ' || this == '\t' || this == '\n' || this == '\r'
}

/** 供 HTTP 层用：UTF-8 输出，避免各端对中文编码做出不同选择。 */
internal fun String.utf8Bytes(): ByteArray = toByteArray(StandardCharsets.UTF_8)

internal fun ByteArray.asUtf8String(): String = String(this, StandardCharsets.UTF_8)

internal fun readFully(stream: InputStream?, limit: Int = 8 * 1024 * 1024): String {
    if (stream == null) return ""
    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(8 * 1024)
    stream.use { input ->
        while (true) {
            val read = input.read(chunk)
            if (read <= 0) break
            buffer.write(chunk, 0, read)
            if (buffer.size() > limit) throw AiJsonException("响应体超过 $limit 字节", buffer.size())
        }
    }
    return buffer.toByteArray().asUtf8String()
}
