package com.pearlnode.model

/** A piece of the timeline nobody finer had anything to say about. */
data class PowerSegment(
    val startUtc: Long,
    val endUtc: Long,
    val energyMwh: Double,
    /** Which tier this came from, so a reader can tell detail from a day lump. */
    val tier: Int,
)

/** One bar of the chart. */
data class PowerBucket(
    val startUtc: Long,
    val endUtc: Long,
    val energyMwh: Double,
    /** The coarsest tier that contributed, or null if nothing is known here. */
    val coarsestTier: Int?,
)

/**
 * Lays the stored blocks over one another, finest first, and lets no coarse
 * block overwrite a stretch a finer one already described.
 *
 * The plug thins its own history out over time, so the same afternoon can be
 * present here three times over: as quarter hours copied last week, as hours
 * copied yesterday, and as a single day copied today. Taking the day would
 * throw away detail that is still on hand, and taking all three would count
 * the energy three times. So each block only contributes over the parts of its
 * span that no finer tier has covered, and its energy is split across those
 * parts in proportion to their length -- which is exactly what the block
 * asserts, since a block is a stretch of constant power.
 */
fun mergeFinest(blocks: List<PowerBlock>, fromUtc: Long, toUtc: Long): List<PowerSegment> {
    if (fromUtc >= toUtc) return emptyList()
    val covered = ArrayList<Covered>()   // disjoint, sorted by start
    val out = ArrayList<PowerSegment>()

    for (block in blocks.sortedWith(compareBy({ it.tier }, { it.startUtc }))) {
        val start = maxOf(block.startUtc, fromUtc)
        val end = minOf(block.endUtc, toUtc)
        if (start >= end || block.durationSec <= 0) continue

        val pieces = subtract(start, end, covered)
        if (pieces.isEmpty()) continue

        // What the block claims over the part of it that falls in the window,
        // less what finer blocks have already accounted for inside it.
        //
        // Subtracting the energy rather than the time is the whole point. If
        // the fine data stops at 11:07 and only the hour 11:00 to 12:00
        // survives, what happened between 11:07 and 12:00 is the hour's total
        // minus the seven minutes that are known exactly -- not fifty-three
        // sixtieths of it. The two differ by however unusual those minutes
        // were, which is exactly when it matters.
        val claim = block.energyMwh.toDouble() * (end - start) / block.durationSec
        var leftover = claim - coveredEnergy(covered, start, end)
        // The coarse tiers store whole units -- a whole watt hour in the hour
        // tier -- so the fine total can come out a shade above the coarse one it
        // sits inside. There is nothing left over then, and it must not become a
        // bar pointing the other way.
        if (claim >= 0) { if (leftover < 0) leftover = 0.0 } else if (leftover > 0) leftover = 0.0

        val free = pieces.sumOf { it[1] - it[0] }.toDouble()
        for (piece in pieces) {
            val share = if (free > 0) leftover * (piece[1] - piece[0]) / free else 0.0
            out.add(PowerSegment(piece[0], piece[1], share, block.tier))
            insert(covered, Covered(piece[0], piece[1], share))
        }
    }
    out.sortBy { it.startUtc }
    return out
}

/**
 * A stretch some tier has already spoken for, and what it said about it.
 *
 * These are never merged with one another, however neatly they abut. Merging
 * would pool the energy of a short, busy native run with a long, flat day
 * leftover, and the next coarse block asking what is already accounted for
 * across part of that would get the pooled average rather than the truth.
 */
private class Covered(val start: Long, val end: Long, val energyMwh: Double)

/** How much of what is already accounted for falls inside [start, end). */
private fun coveredEnergy(covered: List<Covered>, start: Long, end: Long): Double {
    var total = 0.0
    for (range in covered) {
        if (range.end <= start) continue
        if (range.start >= end) break
        val span = range.end - range.start
        if (span <= 0) continue
        val overlap = minOf(range.end, end) - maxOf(range.start, start)
        if (overlap > 0) total += range.energyMwh * overlap / span
    }
    return total
}

/** Buckets segments onto the boundaries the chart draws, splitting where they straddle one. */
enum class BucketAggregate {
    /** Add the segments up. Energy is additive: two half hours make an hour. */
    SUM,

    /**
     * Divide by the time actually covered. A temperature is not additive -- two
     * half hours at 20 degrees make an hour at 20, not at 40 -- so what a bucket
     * of a level series holds is the mean, weighted by how long each reading
     * stood. Both go through the same accumulation, because the quantity being
     * added up is the integral either way; only the last step differs.
     */
    MEAN,
}

fun bucketize(
    segments: List<PowerSegment>,
    edges: List<Long>,
    aggregate: BucketAggregate = BucketAggregate.SUM,
): List<PowerBucket> {
    if (edges.size < 2) return emptyList()
    val energy = DoubleArray(edges.size - 1)
    val covered = DoubleArray(edges.size - 1)
    val coarsest = arrayOfNulls<Int>(edges.size - 1)

    for (segment in segments) {
        val span = (segment.endUtc - segment.startUtc).toDouble()
        if (span <= 0) continue
        val rate = segment.energyMwh / span
        // The last edge at or before the segment starts, which is the first
        // bucket it can touch. Everything after that is walked forwards.
        var index = edges.binarySearch(segment.startUtc).let { if (it < 0) -it - 2 else it }
        if (index < 0) index = 0
        while (index < energy.size && edges[index] < segment.endUtc) {
            val from = maxOf(segment.startUtc, edges[index])
            val to = minOf(segment.endUtc, edges[index + 1])
            if (to > from) {
                energy[index] += rate * (to - from)
                covered[index] += (to - from).toDouble()
                val seen = coarsest[index]
                if (seen == null || segment.tier > seen) coarsest[index] = segment.tier
            }
            index++
        }
    }
    return (0 until energy.size).map {
        val value = when {
            aggregate == BucketAggregate.SUM -> energy[it]
            covered[it] > 0 -> energy[it] / covered[it]
            // Nothing covered means nothing known, which coarsestTier already
            // says; the figure beside it must not read as a measured zero.
            else -> 0.0
        }
        PowerBucket(edges[it], edges[it + 1], value, coarsest[it])
    }
}

/** [start, end) minus everything already covered, left to right. */
private fun subtract(start: Long, end: Long, covered: List<Covered>): List<LongArray> {
    val pieces = ArrayList<LongArray>()
    var at = start
    for (range in covered) {
        if (range.end <= at) continue
        if (range.start >= end) break
        if (range.start > at) pieces.add(longArrayOf(at, minOf(range.start, end)))
        at = maxOf(at, range.end)
        if (at >= end) return pieces
    }
    if (at < end) pieces.add(longArrayOf(at, end))
    return pieces
}

/** Adds a stretch to the covered list, keeping it sorted by start. */
private fun insert(covered: MutableList<Covered>, piece: Covered) {
    // Blocks arrive in order within a tier, so the new stretch almost always
    // belongs at the end. Checking that first keeps a month of native blocks
    // from turning the merge into a quadratic scan.
    if (covered.isEmpty() || covered.last().start <= piece.start) {
        covered.add(piece)
        return
    }
    var at = covered.indexOfFirst { it.start > piece.start }
    if (at < 0) at = covered.size
    covered.add(at, piece)
}
