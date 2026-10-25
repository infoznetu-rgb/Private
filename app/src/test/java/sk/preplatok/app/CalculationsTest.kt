package sk.preplatok.app

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class CalculationsTest {
    private val energy = Energy.ELECTRICITY
    private fun r(day: String, value: String) = Reading(energy = energy, date = LocalDate.parse(day), value = value.toBigDecimal())
    @Test fun slovakDecimalIsStrict() {
        assertEquals(BigDecimal("123.045"), decimal("123,045"))
        listOf("1O5", "1,2,3", "1.2.3", "-2", "NaN", "1e6", "").forEach { assertNull(decimal(it)) }
    }
    @Test fun validationRejectsLowerAndDuplicateAndFutureReadings() {
        val existing = listOf(r("2026-01-01","100"),r("2026-03-01","300"))
        assertNotNull(Calculations.validate(existing, r("2026-02-01","99")))
        assertNotNull(Calculations.validate(existing, r("2026-02-01","301")))
        assertNotNull(Calculations.validate(existing, r("2026-01-01","100")))
        assertNotNull(Calculations.validate(existing, r("2026-02-01","200"), LocalDate.parse("2026-01-20")))
        assertNull(Calculations.validate(existing, r("2026-02-01","200"), LocalDate.parse("2026-02-01")))
        assertNull(Calculations.validate(existing, existing[0]))
    }
    @Test fun exactMoneyAndActualPaymentsAreUsed() {
        val rs = listOf(r("2026-01-01","100"), r("2026-02-01","200"))
        val t = Tariff(energy, BigDecimal("0.20"),BigDecimal("5"),BigDecimal("40"), start = LocalDate.parse("2026-01-01"))
        val ps = listOf(Payment(energy = energy, date = LocalDate.parse("2026-01-10"),amount = BigDecimal("30")), Payment(energy = energy, date = LocalDate.parse("2026-02-01"),amount = BigDecimal("40")))
        val s = Calculations.summary(Snapshot(rs,ps,listOf(t)),energy)!!
        assertEquals("25.00", s.cost.toPlainString())
        assertEquals(0, s.balance.compareTo(BigDecimal("5")))
        assertEquals(0, s.planned.compareTo(BigDecimal("40")))
        assertTrue(s.baselineExact)
    }
    @Test fun partialCalendarMonthAndLeapYear() {
        assertEquals(0, Calculations.fixedCost(LocalDate.parse("2024-02-01"),LocalDate.parse("2024-03-01"),BigDecimal("29")).compareTo(BigDecimal("29")))
        assertEquals(0, Calculations.fixedCost(LocalDate.parse("2026-01-16"),LocalDate.parse("2026-02-01"),BigDecimal("31")).compareTo(BigDecimal("16")))
    }
    @Test fun noConsumptionUntilTwoReadingsAndMissingBaselineFlagged() {
        assertNull(Calculations.summary(Snapshot(listOf(r("2026-01-01","0")),emptyList(),emptyList()),energy))
        val s = Calculations.summary(Snapshot(listOf(r("2026-01-02","0"),r("2026-02-01","100")),emptyList(),listOf(Tariff(energy,start = LocalDate.parse("2026-01-01")))),energy)!!
        assertFalse(s.baselineExact)
    }
    @Test fun gasUsesUserFactor() {
        val rs = listOf(Reading(energy = Energy.GAS,date = LocalDate.parse("2026-01-01"),value = BigDecimal("50")), Reading(energy = Energy.GAS,date = LocalDate.parse("2026-02-01"),value = BigDecimal("60")))
        val t = Tariff(Energy.GAS,price = BigDecimal("0.08"),factor = BigDecimal("10.55"),start = LocalDate.parse("2026-01-01"))
        assertEquals("8.44",Calculations.summary(Snapshot(rs,emptyList(),listOf(t)),Energy.GAS)!!.cost.toPlainString())
    }
    @Test fun ocrDoesNotSilentlyRepairLetters() {
        assertTrue(OcrNumbers.candidates("12O45").isEmpty())
        assertEquals(listOf("00123,45"),OcrNumbers.candidates("00123,45 kWh"))
        assertEquals(listOf("123", "456"),OcrNumbers.candidates("123 456"))
    }
}
