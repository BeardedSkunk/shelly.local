package shelly.local.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The journal's responses are written by hand in mJS, one string concatenation
 * at a time, rather than by a serialiser. So these are not made-up fixtures:
 * every one below was captured verbatim from a Plug M Gen3 running the script,
 * on 2026-08-05. If the script's output ever drifts from what this app reads,
 * this is where it shows.
 *
 * PLANT is 192.168.178.23, a balcony solar plant with reverse power on, so it
 * reads positive while generating. CHARGER is 192.168.178.21, idle, which is
 * the case where almost every field is empty and the running block is a null
 * block that reports nothing but when it began.
 */
class PowerJournalParsingTest {

    private val plant = """
        {"version":2,"generation":76,"utc_offset":7200,"attic_bytes":0,"tiers":[
        {"grid_sec":1,"unit_mwh":1,"pages":["c"],"pending":null,"open_bucket":null,"open_mwh":0,"carry_mwh":0},
        {"grid_sec":900,"unit_mwh":100,"pages":["b"],"pending":[1785923100,1800,429100],"open_bucket":1785924900,"open_mwh":12681,"carry_mwh":-36},
        {"grid_sec":3600,"unit_mwh":1000,"pages":["a"],"pending":[1785920400,3600,761000],"open_bucket":1785924000,"open_mwh":221522,"carry_mwh":-377},
        {"grid_sec":86400,"unit_mwh":10000,"pages":[],"pending":null,"open_bucket":1785880800,"open_mwh":2032145,"carry_mwh":0}],
        "archive_end":1785925000,
        "current":{"start_time":1785925000,"duration_sec":600,"energy_mwh":83818,"meter_net_mwh":2266666,"meter_gross_mwh":2572666,"watt":502.908,"reference_watt":494.2}}
    """.trimIndent().replace("\n", "")

    private val charger = """
        {"version":2,"generation":0,"utc_offset":7200,"attic_bytes":0,"tiers":[
        {"grid_sec":1,"unit_mwh":1,"pages":[],"pending":null,"open_bucket":null,"open_mwh":0,"carry_mwh":0},
        {"grid_sec":900,"unit_mwh":100,"pages":[],"pending":null,"open_bucket":null,"open_mwh":0,"carry_mwh":0},
        {"grid_sec":3600,"unit_mwh":1000,"pages":[],"pending":null,"open_bucket":null,"open_mwh":0,"carry_mwh":0},
        {"grid_sec":86400,"unit_mwh":10000,"pages":[],"pending":null,"open_bucket":null,"open_mwh":0,"carry_mwh":0}],
        "archive_end":null,"current":{"start_time":1785915797,"watt":0}}
    """.trimIndent().replace("\n", "")

    // Captured from PLANT on 2026-08-06, from the read-by-time endpoint, cut to
    // three blocks with max so that "more" and "next" are the live ones rather
    // than the ends of a whole tier. The middle block is a merged run: eight
    // hours the plug held as one entry because they were all the same level.
    private val quarterHourRead = """
        {"api":2,"tier":1,"generation":86,"grid_sec":900,
        "fields":["start_time","duration_sec","energy_mwh"],
        "blocks":[[1785957300,900,100],[1785958200,28800,4800],[1785987000,900,300]],
        "returned":3,"next":1785987900,"more":true,"tier_start":1785957300}
    """.trimIndent().replace("\n", "")

    @Test
    fun `a working plant's index reads back whole`() {
        val index = parseJournalIndex(plant)
        assertEquals(2, index.version)
        assertEquals(7200, index.utcOffsetSec)
        assertEquals(1785925000L, index.archiveEnd)
        assertEquals(4, index.tiers.size)
        assertEquals(listOf(1L, 900L, 3600L, 86400L), index.tiers.map { it.gridSec })
        assertEquals(listOf("c"), index.tiers[0].pages)
        assertEquals(Triple(1785923100L, 1800L, 429100L), index.tiers[1].pending)
        assertNull("the native tier has no pending run", index.tiers[0].pending)
        assertEquals(Triple(1785925000L, 600L, 83818L), index.current)
    }

    @Test
    fun `the plug's own clock comes through, and its absence is visible`() {
        // Version 3 of the script publishes unixtime. The two fixtures above
        // were captured from version 2, which did not -- and a reader has to be
        // able to tell that apart from a clock reading zero, because that is
        // when it has to fall back to its own.
        val withClock = plant.replace(""""generation":76,""", """"generation":76,"unixtime":1785925600,""")
        assertEquals(1785925600L, parseJournalIndex(withClock).unixtime)
        assertEquals(0L, parseJournalIndex(plant).unixtime)
    }

    @Test
    fun `a merged pending run keeps its full span`() {
        // Two quarter hours that turned out to be the same level, so the plug
        // holds them as one 1800 second run. Copying it as 900 would lose half.
        val pending = parseJournalIndex(plant).tiers[1].pending!!
        assertEquals(1800L, pending.second)
    }

    @Test
    fun `an idle plug parses without anything to parse`() {
        val index = parseJournalIndex(charger)
        assertNull(index.archiveEnd)
        assertTrue(index.tiers.all { it.pages.isEmpty() && it.pending == null })
        // A null block reports only when it began. The duration comes out zero
        // and the repository fills it in from the clock, because "nothing has
        // flowed since 09:43" is a fact worth drawing rather than a gap.
        val current = index.current
        assertNotNull(current)
        assertEquals(1785915797L, current!!.first)
        assertEquals(0L, current.second)
        assertEquals(0L, current.third)
    }

    @Test
    fun `a tier read decodes to real seconds and real milliwatt hours`() {
        val read = parseJournalRead(quarterHourRead)
        assertEquals(1, read.tier)
        assertEquals(3, read.blocks.size)
        assertEquals(1785957300L, read.blocks[0][0])
        // Durations arrive in seconds, not in grid steps -- a quarter hour is
        // 900 here even though the page stores it as the number 1, and the
        // merged run is 28800 rather than 32.
        assertEquals(900L, read.blocks[0][1])
        assertEquals(28800L, read.blocks[1][1])
        assertEquals(100L, read.blocks[0][2])
        // Blocks are gapless: each begins where the last one ended.
        for (i in 1 until read.blocks.size) {
            assertEquals(read.blocks[i - 1][0] + read.blocks[i - 1][1], read.blocks[i][0])
        }
    }

    @Test
    fun `a cut off read says where to carry on`() {
        val read = parseJournalRead(quarterHourRead)
        assertTrue(read.more)
        // The end of the last block handed over, so the next request fetches
        // nothing twice and skips nothing.
        val last = read.blocks.last()
        assertEquals(last[0] + last[1], read.next)
        assertEquals(1785957300L, read.tierStart)
        assertEquals(86, read.generation)
    }

    @Test
    fun `a plug still serving reads by slot is recognised as such`() {
        // Neither fixture above carries api, because neither script had it. The
        // app upgrades such a plug rather than trying to read it: the endpoint
        // it offers names storage slots, and the plug recycles those under its
        // own writes.
        assertEquals(0, parseJournalIndex(plant).api)
        assertEquals(0, parseJournalIndex(charger).api)
        assertEquals(2, parseJournalIndex(plant.replace(""""version":2""", """"api":2,"version":3""")).api)
    }

    @Test(expected = IllegalStateException::class)
    fun `a read the plug refuses is an error rather than an empty result`() {
        parseJournalRead("""{"error":"no such tier"}""")
    }
}
