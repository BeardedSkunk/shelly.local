package shelly.local.data.api

import shelly.local.model.PowerBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page format is written on the plug and read here, so the two ends have to
 * agree exactly and neither can be changed alone.
 *
 * The fixture below was produced by the script's own encoder, running under
 * node against `shelly/power-journal/test/harness.js`. If this test fails after
 * a change to either side, the change broke the format rather than the test.
 */
class JournalPagesTest {

    private val device = "plug"

    // One day-tier page: seven days from 11.06.2024, the last of them the
    // highest yield in the archive and one of them negative, so the zigzag is
    // exercised in both directions.
    private val FIXTURE = "3C_aQIV\$\$W7\$MK\$\$W7\$OF\$\$:\$#\$GR\$"

    @Test
    fun `a page written by the plug reads back as the days it holds`() {
        val blocks = decodeJournalPage(FIXTURE, device)
        assertEquals(7, blocks.size)
        assertEquals(1718056800L, blocks[0].startUtc)
        assertTrue(blocks.all { it.durationSec == 86400L })
        assertTrue(blocks.all { it.tier == 3 })
        assertEquals(
            listOf(3_300_000L, 6_450_000L, 3_300_000L, 5_660_000L, -120_000L, 0L, 7_540_000L),
            blocks.map { it.energyMwh },
        )
        // Every block begins where the last one ended -- a page has no room to
        // say otherwise, and the reader relies on it.
        blocks.zipWithNext().forEach { (a, b) ->
            assertEquals(a.startUtc + a.durationSec, b.startUtc)
        }
    }

    @Test
    fun `what this app writes, this app reads`() {
        val days = (0 until 400).map { i ->
            PowerBlock(device, 3, 1718056800L + i * 86400L, 86400,
                ((i * 37) % 760 - 100) * 10_000L)
        }
        val pages = encodeJournalPages(3, days)
        assertTrue("400 Tage passen nicht auf eine Seite", pages.size > 1)
        assertTrue(pages.all { it.length <= PAGE_LIMIT })
        val back = pages.flatMap { decodeJournalPage(it, device) }
        assertEquals(days.size, back.size)
        days.zip(back).forEach { (want, got) ->
            assertEquals(want.startUtc, got.startUtc)
            assertEquals(want.durationSec, got.durationSec)
            assertEquals(want.energyMwh, got.energyMwh)
        }
    }

    @Test
    fun `a gap in the days starts a new page rather than shifting them`() {
        val blocks = listOf(
            PowerBlock(device, 3, 1718056800L, 86400, 30_000),
            PowerBlock(device, 3, 1718143200L, 86400, 40_000),
            // A fortnight missing, which no page can express inside itself.
            PowerBlock(device, 3, 1719352800L, 86400, 50_000),
        )
        val pages = encodeJournalPages(3, blocks)
        assertEquals(2, pages.size)
        val back = pages.flatMap { decodeJournalPage(it, device) }
        assertEquals(listOf(1718056800L, 1718143200L, 1719352800L), back.map { it.startUtc })
        assertEquals(listOf(30_000L, 40_000L, 50_000L), back.map { it.energyMwh })
    }

    @Test
    fun `the prose at the top of an attic is not mistaken for a page`() {
        val source = """
            // power-journal attic. This script never runs.
            // Each comment below is one day page pushed out of the device storage,
            // in the same encoding the HTTP endpoint serves.
            //$FIXTURE
        """.trimIndent()
        val pages = atticPages(source)
        assertEquals(1, pages.size)
        assertEquals(FIXTURE, pages[0])
        assertEquals(7, decodeJournalPage(pages[0], device).size)
    }

    @Test
    fun `a damaged page gives up what it has instead of throwing`() {
        val blocks = decodeJournalPage(FIXTURE.dropLast(1), device)
        assertTrue("was lesbar war, kommt zurueck", blocks.size >= 6)
    }
}
