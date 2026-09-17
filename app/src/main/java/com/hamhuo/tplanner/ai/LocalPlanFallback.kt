package com.hamhuo.tplanner.ai

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * 本地确定性兜底：不联网、不问模型，用规则把一段话拆成动作并解析时间。
 *
 * 它存在的意义有两个：
 * 1. 断网、没有 key、429、余额不足时，"写日程"仍然是可用的功能，而不是一句"AI 服务不可用"；
 * 2. 模型返回了但内容不可用时，它是第二道防线。
 *
 * 它与模型路径**产出同一个 [PlanProposal] 结构**——否则两套界面状态一定分叉。
 * 它只做保守的事：文中真的出现了时间才标 [TimeSource.STATED]，只给了日期或只给了时段时才按
 * 常设时刻补齐并标 [TimeSource.INFERRED]（依据必须写出来）。猜不出依据的一律 [TimeSource.NONE]。
 * 它的识别能力明显弱于模型，界面必须如实告诉用户这是本地规则的结果。
 */
internal object LocalPlanFallback {

    /** 一段文字最多当成几条动作，避免把一整篇日记铺成几十条任务。 */
    const val MAX_PARTS = 12

    /** 一句话里没有这些词、也不带时间时，多半不是待办（"你好"、"谢谢"）。 */
    private val ACTION_HINTS = listOf(
        "要", "得", "需要", "准备", "完成", "写", "做", "交", "发", "买", "去", "见", "开",
        "看", "读", "改", "学", "打", "跑", "整理", "预约", "开会", "上班", "上课", "考试",
    )

    /** 强边界：句号、分号、换行无条件切分。 */
    private val STRONG_BOUNDARY = Regex("""[\n\r；;。！!？?]+""")

    /** 弱边界：逗号。切出来的碎片必须自己也像一件事，否则并回上一段（"写论文，大概两小时"）。 */
    private val WEAK_BOUNDARY = Regex("""[，,]""")

    private val NEXT_WEEK_WEEKDAY = Regex("""下(?:个)?(?:周|星期|礼拜)([一二三四五六日天])""")
    private val THIS_WEEK_WEEKDAY = Regex("""(?:这|本)?(?:周|星期|礼拜)([一二三四五六日天])""")
    private val DAY_NUMBER = Regex("""(\d{1,2})\s*(?:号|日)""")

    /**
     * 真正带数字的日期写法（"下周三"）。用范围判断，不能用"前一个字是不是周"：
     * 「中午」里也有个「中」，单字判断会把"中午12点"排掉。
     */
    private val WEEKDAY_WITH_DIGIT = Regex("""(?:下(?:个)?)?(?:周|星期|礼拜)(?:[一二三四五六日天]|\d{1,2})""")

    /** 日期表达先找出来并屏蔽，避免其中的"晚/周"被当成时刻或时段。 */
    private val DATE_PATTERNS = listOf(
        NEXT_WEEK_WEEKDAY,
        THIS_WEEK_WEEKDAY,
        Regex("""今天|今日|明天|明日|后天|今晚"""),
        Regex("""下(?:个)?(?:周|星期|礼拜)"""),
        DAY_NUMBER,
    )

    /** 无条件成立的截止写法。 */
    private val DEADLINE_KEYWORD = Regex("""截止(?:到|日期)?|deadline""")

    /** "前/之前/以前"只是语素：它是不是期限，要看它前面有没有时间表达。 */
    private val DEADLINE_PARTICLE = Regex("""(?:之前|以前|前)""")

    /** 只说时段不说时刻（"晚上"）时用的常设时刻；不假装那是用户说的，会标成推断。 */
    private val BARE_PERIODS = listOf(
        "凌晨" to LocalTime.of(6, 0), "早上" to LocalTime.of(8, 0),
        "早晨" to LocalTime.of(8, 0), "上午" to LocalTime.of(10, 0),
        "中午" to LocalTime.of(12, 0), "下午" to LocalTime.of(15, 0),
        "傍晚" to LocalTime.of(18, 0), "晚上" to LocalTime.of(20, 0),
    )

    /** 分隔符不含「点」——"周五" 的「周」不是时刻，把「点」放进字符类会误判。 */
    private val CLOCK = Regex("""(凌晨|早上|早晨|上午|中午|下午|傍晚|晚上|晚)?\s*(\d{1,2})\s*(?:[:：时]|点)\s*(半|\d{1,2})?\s*分?""")

    private val NUMERAL_DIGITS = mapOf(
        '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10,
    )

    /** 只替换紧挨着"点/时/:/："的中文数字，免得把"周三"改成"周3"。 */
    private val CN_HOUR = Regex("""([一二两三四五六七八九十]{1,2})\s*(?=[:：点时])""")

    /** "10点半"里分钟位的中文数字。 */
    private val CN_MINUTE = Regex("""(?<=点)\s*([一二三四五六七八九十])\s*分?""")

    /** 句首的连接词不是任务的一部分："还得剪个视频" 的标题应当是"剪个视频"。 */
    private val CONNECTOR_PREFIX = Regex(
        """^(?:然后|接着|之后|还得|还要|还有|另外|以及|顺便|再|要|得|必须|需要|记得|别忘(?:了)?|去)+""",
    )

    private val WEEKDAY_BY_CHAR = mapOf(
        '一' to DayOfWeek.MONDAY, '二' to DayOfWeek.TUESDAY, '三' to DayOfWeek.WEDNESDAY,
        '四' to DayOfWeek.THURSDAY, '五' to DayOfWeek.FRIDAY, '六' to DayOfWeek.SATURDAY,
        '日' to DayOfWeek.SUNDAY, '天' to DayOfWeek.SUNDAY,
    )

    fun extract(text: String, context: TimeContext): PlanProposal {
        val zone = runCatching { ZoneId.of(context.timeZoneId) }.getOrDefault(ZoneOffset.ofHours(8))
        val now = runCatching { Instant.parse(context.instantIso) }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(context.instantIso).toInstant() }.getOrNull()
        val today = runCatching { LocalDate.parse(context.today) }
            .getOrElse { now?.atZone(zone)?.toLocalDate() ?: LocalDate.now(zone) }
        val nowTime = now?.atZone(zone)?.toLocalTime() ?: LocalTime.NOON

        val parts = splitIntoParts(text)
        if (parts.isEmpty()) return PlanProposal(understanding = "没有读到内容。")

        val actions = parts.mapNotNull { part ->
            if (!looksLikeAction(part)) return@mapNotNull null
            val normalized = normalizeNumerals(part)
            val title = stripTimeExpressions(normalized).take(MAX_TITLE_CHARS)
            if (title.isBlank()) return@mapNotNull null
            PlannedAction(
                id = "",
                topicTitle = title,
                title = title,
                subtasks = emptyList(),
                time = parseTime(normalized, today, nowTime, zone),
                note = "",
            )
        }
        if (actions.isEmpty()) return PlanProposal(understanding = "这段文字里没有识别到待办。")

        return PlanProposal(
            understanding = "本地规则识别到 ${actions.size} 条待办（未使用模型）。",
            topics = actions.map { action ->
                PlanTopic(
                    title = action.topicTitle,
                    evidence = action.topicTitle.take(MAX_EVIDENCE_CHARS),
                    colorId = 0,
                    actions = listOf(action.copy(id = "local")),
                )
            },
            assumptions = listOf("本条结果由本地规则生成，没有使用模型；标题按输入分行切分。"),
        )
    }

    /**
     * 切句：先按强边界切，再按逗号切，但只在"切出来的碎片自己也像一件事"时才真的断开。
     * 这样 "今天下午三点开会，周四晚上九点前交周报" 会变成两条，而
     * "写论文，大概两小时" 仍然是一条。
     */
    private fun splitIntoParts(text: String): List<String> {
        val strong = STRONG_BOUNDARY.split(text).flatMap { segment -> WEAK_BOUNDARY.split(segment) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val parts = mutableListOf<String>()
        for (candidate in strong) {
            val previous = parts.lastOrNull()
            if (previous == null || looksLikeAction(candidate)) {
                parts += candidate
            } else {
                // 碎片本身不成立（多半是补充说明），并回上一段。
                parts[parts.lastIndex] = "$previous，$candidate"
            }
        }
        return parts.take(MAX_PARTS)
    }

    private fun looksLikeAction(part: String): Boolean {
        if (part.length > MAX_TITLE_CHARS + 20) return true
        // 短句必须带动作词或时间，否则当作寒暄/记录而不是待办。
        return ACTION_HINTS.any { part.contains(it) } ||
            CLOCK.containsMatchIn(normalizeNumerals(part)) ||
            BARE_PERIODS.any { part.contains(it.first) } ||
            DATE_PATTERNS.any { it.containsMatchIn(part) }
    }

    private fun parseTime(part: String, today: LocalDate, nowTime: LocalTime, zone: ZoneId): ActionTime {
        val deadline = deadlineMarker(part)
        // 期限写法里的"前/之前"不是相对日期的参照（"交周报前"），去掉它再解析日期。
        val dateReference = if (deadline == null) part else part.removeRange(deadline.range)
        val date = resolveDate(dateReference, today)
        val clock = findClock(part)
        val barePeriod = if (clock == null) BARE_PERIODS.firstOrNull { part.contains(it.first) } else null

        if (clock == null && barePeriod == null && date == null) return ActionTime()

        val time = when {
            clock != null -> resolveClock(clock, nowTime)
            barePeriod != null -> barePeriod.second
            else -> null
        }
        // 只说了时段（"晚上"）而没有说时刻：那是我们替它定的钟点，必须算推断。
        val timeIsExplicit = clock != null
        // 只有日期没有时刻时，用一个明确的常设时段并写进依据——不假装那是用户说的。
        val effectiveTime = time ?: LocalTime.of(20, 0)
        val effectiveDate = date ?: today
        val start = LocalDateTime.of(effectiveDate, effectiveTime).atZone(zone)

        val basis = buildString {
            append("本地规则：原文「")
            append(part.take(MAX_BASIS_CHARS - 30))
            append("」")
            if (time == null) append("，只给了日期，暂定 20:00")
        }

        if (deadline != null) {
            // "周五前交" 是一条截止期限：只给 end，不编造开始时间。
            return ActionTime(
                source = TimeSource.STATED,
                start = null,
                end = start.toInstant().toString(),
                confidence = 1.0,
                basis = basis,
            )
        }
        if (!timeIsExplicit) {
            // 只有日期或只有时段：开始时间是我们替它定的，必须标成推断。
            return ActionTime(
                source = TimeSource.INFERRED,
                start = start.toInstant().toString(),
                end = start.plusHours(1).toInstant().toString(),
                confidence = ActionTime.LOW_CONFIDENCE,
                basis = basis,
            )
        }
        return ActionTime(
            source = TimeSource.STATED,
            start = start.toInstant().toString(),
            end = start.plusHours(1).toInstant().toString(),
            confidence = 1.0,
            basis = "$basis，结束时刻按 1 小时估",
        )
    }

    /**
     * 期限标记。
     *
     * "截止" 这类词无条件成立；单独的「前」不行——"三点前到"里的「前」是地点，"交周报前"里的
     * 「前」是顺序。只有**它前面已经有时间表达**时（"9点前""周五前"），「前」才是截止期限。
     */
    private fun deadlineMarker(part: String): MatchResult? {
        DEADLINE_KEYWORD.find(part)?.let { return it }
        DEADLINE_PARTICLE.findAll(part).forEach { match ->
            val prefix = part.substring(0, match.range.first)
            if (clockMatches(prefix).isNotEmpty() || DATE_PATTERNS.any { it.containsMatchIn(prefix) }) {
                return match
            }
        }
        return null
    }

    /**
     * 找出真正的时刻。
     *
     * 只挡一种误判：`下周3` 里的数字是日期不是时刻。判断用**范围**而不是"前一个字"——
     * 「中午」里也有个「中」，单字判断会把"中午12点"整条排掉。
     * 不做整体日期屏蔽：屏蔽会连"今晚"的日期语义一起抹掉，把 22:30 读成 10:30，比没有时间更糟。
     */
    private fun clockMatches(part: String): List<MatchResult> {
        val dateRanges = WEEKDAY_WITH_DIGIT.findAll(part).map { it.range }.toList()
        return CLOCK.findAll(part).filter { match -> !insideDateRange(dateRanges, match) }.toList()
    }

    /** 时刻的起点落在"周X"这类日期写法里时，那个数字属于日期而不是时刻。 */
    private fun insideDateRange(ranges: List<IntRange>, match: MatchResult): Boolean =
        ranges.any { ranges -> match.range.first in ranges }

    private fun findClock(part: String): MatchResult? = clockMatches(part)
        // 多个候选时取时段最长的那个：`晚上10点` 比 `10点` 更完整。
        .maxByOrNull { match -> (match.groupValues[1].length * 100) - match.range.first }

    private fun resolveDate(part: String, today: LocalDate): LocalDate? {
        NEXT_WEEK_WEEKDAY.find(part)?.let { match ->
            val weekday = WEEKDAY_BY_CHAR[match.groupValues[1].first()] ?: return@let
            return nextWeekday(today.plusWeeks(1).with(DayOfWeek.MONDAY), weekday)
        }
        if (part.contains("下下周")) return null
        if (part.contains("下周") || part.contains("下个星期")) return today.plusWeeks(1)
        if (part.contains("后天")) return today.plusDays(2)
        if (part.contains("明天") || part.contains("明日")) return today.plusDays(1)
        if (part.contains("今天") || part.contains("今日") || part.contains("今晚")) return today
        THIS_WEEK_WEEKDAY.find(part)?.let { match ->
            val weekday = WEEKDAY_BY_CHAR[match.groupValues[1].first()] ?: return@let
            // 没说本周时取严格晚于今天的最近一次；只能落到未来。
            val days = ((weekday.value - today.dayOfWeek.value + 7) % 7).toLong()
            return today.plusDays(if (days == 0L) 7L else days)
        }
        DAY_NUMBER.find(part)?.let { match ->
            val day = match.groupValues[1].toIntOrNull() ?: return@let
            if (day !in 1..31) return@let
            val thisMonth = runCatching { today.withDayOfMonth(day) }.getOrNull() ?: return@let
            return if (thisMonth.isBefore(today)) thisMonth.plusMonths(1).withDayOfMonth(day) else thisMonth
        }
        return null
    }

    private fun nextWeekday(from: LocalDate, weekday: DayOfWeek): LocalDate {
        val days = (weekday.value - from.dayOfWeek.value + 7) % 7
        return from.plusDays(days.toLong())
    }

    private fun resolveClock(match: MatchResult, nowTime: LocalTime): LocalTime {
        val marker = match.groupValues[1].takeIf { it.isNotEmpty() }
        var hour = match.groupValues[2].toIntOrNull() ?: return nowTime
        val minute = when (val text = match.groupValues[3]) {
            "" -> 0
            "半" -> 30
            else -> text.toIntOrNull() ?: 0
        }
        when (marker) {
            // 中午 12 点就是正午；"中午1点"是 13 点。
            "中午" -> hour = if (hour == 12) 12 else if (hour in 1..10) hour + 12 else hour
            // 下午/傍晚/晚上 的 1..11 点换算成 13..23 点……
            "下午", "傍晚", "晚上" -> hour = if (hour == 12) 0 else if (hour in 1..11) hour + 12 else hour
            // ……而"凌晨/早上/上午12点"在中文里指的是 0 点。
            "凌晨", "早上", "早晨", "上午" -> hour = if (hour == 12) 0 else hour
            // 单独的「晚」按口语处理："晚8点"就是 20 点。前缀是"今/昨"的"今晚10点"也走这里，
            // 结论一致（晚上 10 点）——比"不换算"更符合用户的意思。
            else -> hour = if (hour in 1..11) hour + 12 else hour
        }
        if (hour !in 0..23) return nowTime
        return LocalTime.of(hour, minute.coerceIn(0, 59))
    }

    /**
     * 把小范围的中文数字换成阿拉伯数字，让"三点""十点半"也能被时刻规则命中。
     * 只看紧挨着"点/时"的数字：整句替换会把"周三"变成"周3"，反而破坏日期解析。
     */
    private fun normalizeNumerals(part: String): String {
        var result = CN_HOUR.replace(part) { match -> chineseNumeral(match.groupValues[1]).toString() }
        result = CN_MINUTE.replace(result) { match -> chineseNumeral(match.groupValues[1]).toString() }
        return result
    }

    /** 只支持 1..23：够覆盖"三点""十一点""十二点"这些说法，不做通用数字解析。 */
    private fun chineseNumeral(text: String): Int = when (text.length) {
        1 -> NUMERAL_DIGITS[text.first()] ?: return 0
        2 -> {
            val tens = NUMERAL_DIGITS[text.first()] ?: return 0
            val ones = NUMERAL_DIGITS[text.last()] ?: return 0
            if (text.first() != '十' && text.last() == '十') tens * 10 else tens * 10 + ones
        }

        3 -> {
            val tens = NUMERAL_DIGITS[text[1]] ?: return 0
            val ones = NUMERAL_DIGITS[text[2]] ?: return 0
            tens * 10 + ones
        }

        else -> 0
    }

    private fun stripTimeExpressions(part: String): String {
        // 掩码：把所有识别到的时间表达换成等长占位符。掩码只是内部占位，绝不能出现在标题里。
        val characters = part.toCharArray()
        clockMatches(part).forEach { match -> for (index in match.range) characters[index] = MASK }
        DATE_PATTERNS.forEach { pattern ->
            pattern.findAll(part).forEach { match -> for (index in match.range) characters[index] = MASK }
        }
        var result = String(characters)

        result = DEADLINE_KEYWORD.replace(result, " ")
        result = result
            .replace("今天", " ").replace("明天", " ").replace("后天", " ")
            .replace("今晚", " ").replace("早上", " ").replace("早晨", " ")
            .replace("上午", " ").replace("中午", " ").replace("下午", " ")
            .replace("晚上", " ").replace("凌晨", " ").replace("傍晚", " ")
        result = DAY_NUMBER.replace(result, " ")
        result = result.replace(MASK.toString(), " ").replace(Regex("""\s+"""), " ")
            .trim().trim('，', ',', '。', '.', '、', '：', ':')

        // 期限的「前」在标题里必须去掉："9点前把周报交了" -> "把周报交了"。
        // 动词前面往往还有宾语，所以只要求这一小段里出现结束类动词。
        if (result.startsWith("前")) {
            val isDeadlinePrefix = Regex("""^前[^，,。；;]{0,12}?(?:交|完成|弄完|搞定|给|发|上传|结束)""")
            if (isDeadlinePrefix.containsMatchIn(result)) result = result.substring(1)
        }
        result = result.removeSuffix("前").trim()
        return stripConnectorPrefix(result).trim('，', ',', '。', '.', '、', '：', ':', '的')
    }

    /** 去掉句首连接词；整句都是连接词时保持原样，不要清空标题。 */
    private fun stripConnectorPrefix(value: String): String {
        val stripped = CONNECTOR_PREFIX.replace(value, " ").trim()
        return stripped.ifBlank { value }
    }

    /** 日期屏蔽用的占位符，不会与中文输入冲突。 */
    private const val MASK = '\u0001'
}
