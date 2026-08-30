package shelly.local.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which script on a plug the app treats as its own.
 *
 * Written after finding the real plug being read wrongly: it carries both the
 * publisher this app maintains and the hand-written one that came before it, and
 * the older one has the lower id. Since both name an openSenseMap box, "the
 * first script that mentions a box" found the wrong one -- which meant the app
 * saw a script unlike its template, concluded the plug was out of date, and
 * would have written over a recorder that was already current.
 */
class SearchOrderTest {

    private val NAME = "blu-osm"

    @Test
    fun `the app's own script is looked at first, whatever its id`() {
        val listed = listOf(1 to "blu-ht-kvs", 2 to "pj-attic", 3 to "power-journal", 4 to NAME)
        assertEquals(4 to NAME, searchOrder(listed, NAME).first())
    }

    @Test
    fun `everything else keeps the order the plug gave`() {
        // The fallback matters: a plug with no script of this name is searched
        // from the top for one that publishes anywhere, and that is how a
        // hand-written first script gets adopted rather than duplicated.
        val listed = listOf(1 to "blu-ht-kvs", 2 to "pj-attic", 3 to "power-journal")
        assertEquals(listed, searchOrder(listed, NAME))
    }

    @Test
    fun `a plug with only the app's script is unchanged`() {
        val listed = listOf(4 to NAME)
        assertEquals(listed, searchOrder(listed, NAME))
    }

    @Test
    fun `nothing at all is nothing at all`() {
        assertEquals(emptyList<Pair<Int, String>>(), searchOrder(emptyList(), NAME))
    }
}
