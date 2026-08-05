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

    private fun state(
        vararg mwh: Double,
        price: Double = 30.0,
        feedIn: Double? = null,
        reversed: Boolean = false,
    ) = PowerUiState(
        priceCentsPerKwh = price,
        feedInCentsPerKwh = feedIn,
        reversed = reversed,
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

    /**
     * Reverse metering makes a plant report its generation as positive, which
     * is nicer to look at and makes it indistinguishable from a consumer to
     * everything downstream. Without the flag, the balcony plant's earnings
     * came out labelled as costs.
     */
    @Test
    fun `a reverse metered plant earns on exactly the readings that looked like costs`() {
        val plain = state(2_000_000.0, feedIn = 8.0)
        assertTrue("read straight it is consumption", plain.totalEuro > 0)

        val plant = state(2_000_000.0, feedIn = 8.0, reversed = true)
        assertEquals("the same readings are generation", -2.0, plant.totalKwh, 0.0001)
        assertTrue(plant.hasExport)
        assertEquals(-0.16, plant.totalEuro, 0.0001)
    }

    @Test
    fun `a reverse metered plug that draws at night is charged for it`() {
        // Negative under reverse metering means the plug actually drew -- an
        // inverter idling after dark. That is a cost even on a plant.
        val plant = state(3_000_000.0, -200_000.0, feedIn = 8.0, reversed = true)
        assertEquals(0.2, plant.drawnKwh, 0.0001)
        assertEquals(-3.0, plant.exportedKwh, 0.0001)
        assertEquals(0.06 - 0.24, plant.totalEuro, 0.0001)
    }

    @Test
    fun `the raw bars are left alone so the chart still draws what the plug said`() {
        val plant = state(1_000_000.0, reversed = true)
        assertEquals(1_000_000.0, plant.buckets.single().energyMwh, 0.0001)
        assertEquals(-1_000_000.0, plant.oriented.single().energyMwh, 0.0001)
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
