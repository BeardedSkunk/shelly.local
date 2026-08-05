package com.pearlnode.ui.viewmodels

import com.pearlnode.model.PowerBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What is drawn and what is returned are not worth the same, and getting that
 * backwards turns a plant's earnings into costs without anything looking wrong.
 */
class PowerPricingTest {

    private fun state(vararg mwh: Double, price: Double = 30.0, feedIn: Double? = null) =
        PowerUiState(
            priceCentsPerKwh = price,
            feedInCentsPerKwh = feedIn,
            buckets = mwh.mapIndexed { i, e ->
                PowerBucket(i * 3600L, (i + 1) * 3600L, e, 1)
            },
        )

    @Test
    fun `a plain consumer pays the household price`() {
        val s = state(1_000_000.0, 500_000.0)   // 1.0 and 0.5 kWh
        assertEquals(1.5, s.totalKwh, 0.0001)
        assertEquals(0.45, s.totalEuro, 0.0001)
        assertFalse(s.hasExport)
    }

    @Test
    fun `a plant that only exports earns rather than costs`() {
        val s = state(-2_000_000.0, feedIn = 8.0)
        assertTrue(s.hasExport)
        assertEquals(-2.0, s.exportedKwh, 0.0001)
        assertEquals(-0.16, s.totalEuro, 0.0001)
    }

    @Test
    fun `without a feed-in price a returned kilowatt hour is worth a drawn one`() {
        val s = state(-2_000_000.0)
        assertEquals(-0.60, s.totalEuro, 0.0001)
    }

    @Test
    fun `both directions are priced apart, not netted first`() {
        // 3 kWh drawn at 30 ct and 2 kWh returned at 8 ct. Netting first would
        // price 1 kWh at 30 ct and come out at 0.30 -- which is not what
        // either meter reading is worth.
        val s = state(3_000_000.0, -2_000_000.0, feedIn = 8.0)
        assertEquals(1.0, s.totalKwh, 0.0001)
        assertEquals(3.0, s.drawnKwh, 0.0001)
        assertEquals(-2.0, s.exportedKwh, 0.0001)
        assertEquals(0.90 - 0.16, s.totalEuro, 0.0001)
    }

    @Test
    fun `a generous feed-in price turns a net consumer into an earner`() {
        val s = state(1_000_000.0, -3_000_000.0, feedIn = 20.0)
        assertTrue("net energy is negative", s.totalKwh < 0)
        assertEquals(0.30 - 0.60, s.totalEuro, 0.0001)
        assertTrue("and so is the money", s.totalEuro < 0)
    }

    @Test
    fun `bars with nothing behind them contribute nothing`() {
        val s = PowerUiState(
            buckets = listOf(
                PowerBucket(0, 3600, 0.0, null),
                PowerBucket(3600, 7200, 500_000.0, 1),
            )
        )
        assertEquals(0.5, s.totalKwh, 0.0001)
    }
}
