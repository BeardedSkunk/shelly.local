package com.pearlnode.data.api

import com.pearlnode.model.PowerBlock

/**
 * The alphabet a stored page is written in, mirrored from the script.
 *
 * Sixty-four printable characters that survive being pasted into a JavaScript
 * comment, which is where the oldest pages end up. The gap is deliberate:
 * backslash would escape whatever followed it, so the run jumps from '[' to ']'.
 */
private const val A64 =
    "#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[]^_`abc"

/** Grid seconds and energy unit per tier, mirrored from the script's CFG. */
private val TIER_GRID = longArrayOf(1, 900, 3600, 86400)
private val TIER_UNIT = longArrayOf(1, 100, 1000, 10000)

/** How wide a page is allowed to grow before a new one is started. */
const val PAGE_LIMIT = 1010

/**
 * A cursor into a page, because a number is several characters long and the
 * reader has to know where the last one stopped.
 */
private class Cursor(val text: String) {
    var at = 0
    var ok = true

    /**
     * One number: base-32 groups, least significant first, each carrying a
     * continuation bit. Small numbers cost one character, which is what makes a
     * page of mostly-one-step blocks so cheap.
     */
    fun number(): Long {
        var n = 0L
        var shift = 1L
        while (true) {
            if (at >= text.length) { ok = false; return 0 }
            val c = text[at].code
            at++
            val v = if (c < 92) c - 35 else c - 93 + 57
            if (v < 0 || v > 63) { ok = false; return 0 }
            n += (v % 32) * shift
            if (v < 32) return n
            shift *= 32
            // Past anything this format stores. A damaged page must not spin.
            if (shift > 34359738368L) { ok = false; return 0 }
        }
    }

    /** Zigzag, so a solar plant's negative energy stays as short as a positive one. */
    fun signed(): Long {
        val v = number()
        return if (v % 2 == 1L) -(v + 1) / 2 else v / 2
    }
}

/**
 * The blocks a stored page holds.
 *
 * A page is a tier digit, the time it starts at, and then pairs of how many
 * grid steps a block lasts and what it holds. Each block begins where the last
 * one ended, so no page carries a timestamp twice.
 *
 * Anything that does not decode cleanly yields the blocks read so far rather
 * than an exception. A page is twenty-year-old history from the writable tail
 * of a script's source: worth reading as far as it goes, never worth taking the
 * sync down for.
 */
fun decodeJournalPage(page: String, deviceId: String): List<PowerBlock> {
    if (page.length < 2) return emptyList()
    val tier = page[0] - '0'
    if (tier !in TIER_GRID.indices) return emptyList()
    val grid = TIER_GRID[tier]
    val unit = TIER_UNIT[tier]

    val cursor = Cursor(page)
    cursor.at = 1
    var at = cursor.number()
    if (!cursor.ok) return emptyList()

    val out = ArrayList<PowerBlock>()
    while (cursor.at < page.length) {
        val steps = cursor.number()
        if (!cursor.ok) break
        val units = cursor.signed()
        if (!cursor.ok) break
        val duration = steps * grid
        if (duration <= 0) break
        out.add(PowerBlock(deviceId, tier, at, duration, units * unit))
        at += duration
    }
    return out
}

/** One number, in the form a page stores it. */
internal fun encodeNumber(value: Long): String {
    var n = if (value < 0) 0 else value
    val out = StringBuilder()
    while (true) {
        var g = (n % 32).toInt()
        n /= 32
        if (n > 0) g += 32
        out.append(A64[g])
        if (n == 0L) return out.toString()
    }
}

/** The same, zigzagged, for a quantity that can run either way. */
internal fun encodeSigned(value: Long): String =
    encodeNumber(if (value < 0) -value * 2 - 1 else value * 2)

/**
 * Blocks packed back into pages, which is what writing history into the attic
 * needs.
 *
 * Every block must sit on the tier's grid and follow the one before it without
 * a gap: a page has no room to say otherwise, and the reader above assumes it.
 * A run that breaks either rule starts a new page, which costs one timestamp
 * and keeps the record honest.
 */
fun encodeJournalPages(tier: Int, blocks: List<PowerBlock>, limit: Int = PAGE_LIMIT): List<String> {
    if (blocks.isEmpty()) return emptyList()
    val grid = TIER_GRID[tier]
    val unit = TIER_UNIT[tier]
    val pages = ArrayList<String>()
    var page = StringBuilder()
    var expected = -1L

    for (block in blocks.sortedBy { it.startUtc }) {
        val steps = block.durationSec / grid
        if (steps < 1 || steps * grid != block.durationSec) continue
        val field = encodeNumber(steps) + encodeSigned(block.energyMwh / unit)
        val fresh = page.isEmpty() || block.startUtc != expected ||
            page.length + field.length > limit
        if (fresh) {
            if (page.isNotEmpty()) pages.add(page.toString())
            page = StringBuilder()
            page.append(('0' + tier)).append(encodeNumber(block.startUtc))
        }
        page.append(field)
        expected = block.startUtc + block.durationSec
    }
    if (page.isNotEmpty()) pages.add(page.toString())
    return pages
}

/**
 * The pages held in an attic script's source.
 *
 * The attic is a script that never runs, and its source is the only writable
 * space left on a plug once the storage slots are spoken for. Each page is one
 * comment line. The file also opens with a few lines of prose explaining what it
 * is, so a line counts as a page only when what follows the slashes really is
 * one: a tier digit and then nothing but the alphabet.
 */
fun atticPages(source: String): List<String> = source.lineSequence()
    .mapNotNull { line ->
        val body = line.trim().removePrefix("//").trim()
        when {
            body.length < 2 -> null
            body[0] !in '0'..'3' -> null
            body.drop(1).any { it !in A64 } -> null
            else -> body
        }
    }
    .toList()
