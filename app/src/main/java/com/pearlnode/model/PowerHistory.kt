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
    val covered = ArrayList<LongArray>()   // disjoint, sorted [start, end)
    val out = ArrayList<PowerSegment>()

    for (block in blocks.sortedWith(compareBy({ it.tier }, { it.startUtc }))) {
        val start = maxOf(block.startUtc, fromUtc)
        val end = minOf(block.endUtc, toUtc)
        if (start >= end || block.durationSec <= 0) continue

        val pieces = subtract(start, end, covered)
        if (pieces.isEmpty()) continue
        val rate = block.energyMwh.toDouble() / block.durationSec
        for (piece in pieces) {
            out.add(PowerSegment(piece[0], piece[1], rate * (piece[1] - piece[0]), block.tier))
            insert(covered, piece)
        }
    }
    out.sortBy { it.startUtc }
    return out
}

/** Buckets segments onto the boundaries the chart draws, splitting where they straddle one. */
fun bucketize(segments: List<PowerSegment>, edges: List<Long>): List<PowerBucket> {
    if (edges.size < 2) return emptyList()
    val energy = DoubleArray(edges.size - 1)
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
                val seen = coarsest[index]
                if (seen == null || segment.tier > seen) coarsest[index] = segment.tier
            }
            index++
        }
    }
    return (0 until energy.size).map {
        PowerBucket(edges[it], edges[it + 1], energy[it], coarsest[it])
    }
}

/** [start, end) minus everything already covered, left to right. */
private fun subtract(start: Long, end: Long, covered: List<LongArray>): List<LongArray> {
    val pieces = ArrayList<LongArray>()
    var at = start
    for (range in covered) {
        if (range[1] <= at) continue
        if (range[0] >= end) break
        if (range[0] > at) pieces.add(longArrayOf(at, minOf(range[0], end)))
        at = maxOf(at, range[1])
        if (at >= end) return pieces
    }
    if (at < end) pieces.add(longArrayOf(at, end))
    return pieces
}

/** Adds a range to the covered list, keeping it sorted and merging what touches. */
private fun insert(covered: MutableList<LongArray>, piece: LongArray) {
    var at = covered.indexOfFirst { it[0] > piece[0] }
    if (at < 0) at = covered.size
    covered.add(at, piece)
    var i = 0
    while (i < covered.size - 1) {
        if (covered[i][1] >= covered[i + 1][0]) {
            covered[i][1] = maxOf(covered[i][1], covered[i + 1][1])
            covered.removeAt(i + 1)
        } else {
            i++
        }
    }
}
