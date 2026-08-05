package com.pearlnode.data

import java.time.DayOfWeek
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings turn into text here, so this is where a wrong one becomes
 * visible. The system defaults are stubbed rather than read: a unit test runs on
 * a machine with no Android settings at all, and the point is what the app does
 * with an answer, not where the answer came from.
 */
class FormatsTest {

    /** A stand-in for the phone. Reading the real one needs a device. */
    private val phone = SystemPrefs(
        firstDayOfWeek = DayOfWeek.MONDAY,
        clock24h = true,
        datePattern = "dd.MM.yyyy",
        temperature = TemperatureUnit.CELSIUS,
    )

    private fun formats(prefs: AppPrefs, system: SystemPrefs = phone) = Formats(prefs, system)

    @Before
    fun germanNumbers() {
        // Decimal comma, so an assertion says what a German phone would show.
        Locale.setDefault(Locale.GERMANY)
    }

    /** An American phone, so every regional answer differs from the default. */
    private val american = SystemPrefs(
        firstDayOfWeek = DayOfWeek.SUNDAY,
        clock24h = false,
        datePattern = "MM/dd/yyyy",
        temperature = TemperatureUnit.FAHRENHEIT,
    )

    @Test
    fun `an unset regional setting takes the phone's answer`() {
        val f = formats(AppPrefs(), american)
        assertEquals(DayOfWeek.SUNDAY, f.firstDayOfWeek)
        assertEquals(false, f.clock24h)
        assertEquals("MM/dd/yyyy", f.datePattern)
        assertEquals(TemperatureUnit.FAHRENHEIT, f.temperature)
    }

    @Test
    fun `a set one overrides it, all four of them`() {
        val f = formats(
            AppPrefs(
                firstDayOfWeek = DayOfWeek.MONDAY,
                clock24h = true,
                datePattern = "yyyy-MM-dd",
                temperature = TemperatureUnit.CELSIUS,
            ),
            american,
        )
        assertEquals(DayOfWeek.MONDAY, f.firstDayOfWeek)
        assertEquals(true, f.clock24h)
        assertEquals("yyyy-MM-dd", f.datePattern)
        assertEquals(TemperatureUnit.CELSIUS, f.temperature)
    }

    @Test
    fun `money goes to the whole unit at a hundred of the small one`() {
        val f = formats(AppPrefs(currencyMajor = "€", currencyMinor = "ct"))
        assertEquals("45,0 ct", f.money(45.0))
        assertEquals("1,00 €", f.money(100.0))
        assertEquals("4,50 €", f.money(450.0))
        // Below half a cent there is nothing to say, and "0,00 €" would read as
        // a real figure rather than as nothing.
        assertEquals("0", f.money(0.02))
    }

    @Test
    fun `another currency is just other characters`() {
        val f = formats(AppPrefs(currencyMajor = "$", currencyMinor = "¢"))
        assertEquals("45,0 ¢", f.money(45.0))
        assertEquals("4,50 $", f.money(450.0))
        assertEquals("¢/kWh", f.priceUnit)
    }

    @Test
    fun `a currency with no small unit only ever shows whole ones`() {
        val f = formats(AppPrefs(currencyMajor = "¥", currencyMinor = ""))
        assertEquals("0,45 ¥", f.money(45.0))
        assertEquals("0,00 ¥", f.money(0.02))
        assertEquals("¥/kWh", f.priceUnit)
    }

    @Test
    fun `a reading is converted, not relabelled`() {
        val celsius = formats(AppPrefs(temperature = TemperatureUnit.CELSIUS))
        val fahrenheit = formats(AppPrefs(temperature = TemperatureUnit.FAHRENHEIT))
        assertEquals("22,7 °C", celsius.temperature(22.7))
        assertEquals("72,9 °F", fahrenheit.temperature(22.7))
        // Freezing and boiling, the two anyone would check by hand.
        assertEquals("32,0 °F", fahrenheit.temperature(0.0))
        assertEquals("212,0 °F", fahrenheit.temperature(100.0))
        assertEquals("100,0 °C", celsius.temperatureFromFahrenheit(212.0))
    }

    @Test
    fun `the clock setting decides the hour, not the phone`() {
        val afternoon = 1785931200000L
        // Built on an American phone, so only the override can produce 24 hour
        // time -- which is the whole point of the setting existing.
        val h24 = formats(AppPrefs(clock24h = true), american)
        val h12 = formats(AppPrefs(clock24h = false), american)
        val hhmm = Regex("""^\d{2}:\d{2}$""")
        assertTrue("24 hour time is HH:mm", hhmm.matches(h24.time(afternoon)))
        assertTrue("12 hour time is not", !hhmm.matches(h12.time(afternoon)))
        assertTrue("and the two disagree", h24.time(afternoon) != h12.time(afternoon))
    }

    @Test
    fun `the date pattern is used as written`() {
        val millis = 1785931200000L
        assertEquals(
            formats(AppPrefs(datePattern = "yyyy-MM-dd")).date(millis).take(4),
            "2026",
        )
        assertTrue(formats(AppPrefs(datePattern = "dd.MM.yyyy")).date(millis).endsWith("2026"))
        // A pattern that cannot be parsed falls back rather than throwing.
        assertTrue(formats(AppPrefs(datePattern = "not a pattern")).date(millis).isNotEmpty())
    }
}
