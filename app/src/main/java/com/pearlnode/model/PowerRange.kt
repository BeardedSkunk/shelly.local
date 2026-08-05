package com.pearlnode.model

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * The spans the chart can show, and the bars each one is drawn in.
 *
 * Every edge is a real local boundary -- a full hour, a local midnight, the
 * first of a month -- rather than a multiple of the span counted back from now,
 * so a bar labelled Tuesday is Tuesday. Daylight saving is why this goes
 * through ZonedDateTime rather than arithmetic on seconds: a day is not always
 * 86400 seconds long, and two days a year it is not.
 */
enum class PowerRange(val bars: Int) {
    DAY(24),
    WEEK(7),
    MONTH(30),
    YEAR(12);

    /** Bar boundaries, oldest first, ending at the edge just after now. */
    fun edges(nowUtc: Long, zone: ZoneId = ZoneId.systemDefault()): List<Long> {
        val now = ZonedDateTime.ofInstant(Instant.ofEpochSecond(nowUtc), zone)
        val end = when (this) {
            DAY -> now.truncatedTo(ChronoUnit.HOURS).plusHours(1)
            WEEK, MONTH -> now.truncatedTo(ChronoUnit.DAYS).plusDays(1)
            YEAR -> now.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS).plusMonths(1)
        }
        val edges = ArrayList<Long>(bars + 1)
        for (step in bars downTo 0) {
            val at = when (this) {
                DAY -> end.minusHours(step.toLong())
                WEEK, MONTH -> end.minusDays(step.toLong())
                YEAR -> end.minusMonths(step.toLong())
            }
            edges.add(at.toEpochSecond())
        }
        return edges
    }
}
