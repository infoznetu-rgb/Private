package sk.preplatok.app

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class RecognitionRulesTest {
    @Test fun rejectsWrongUnitAndRegisterAndSerialNumber() {
        assertTrue(ReadingRules.inspect("001234 m³",Energy.ELECTRICITY).blocked)
        assertFalse(ReadingRules.inspect("1.8.0 001234 kWh",Energy.ELECTRICITY).blocked)
        assertTrue(ReadingRules.inspect("1.8.1 001234 kWh",Energy.ELECTRICITY).blocked)
        assertTrue(ReadingRules.inspect("2.8.0 001234 kWh",Energy.ELECTRICITY).blocked)
        assertTrue(ReadingRules.inspect("Výrobné číslo 12345",Energy.WATER).blocked)
        assertEquals(listOf("001234"),ReadingRules.numericValues("1.8.0 001234 kWh"))
        assertTrue(ReadingRules.numericValues("Sériové číslo 12345").isEmpty())
        assertTrue(ReadingRules.numericValues("12O45").isEmpty())
    }
    @Test fun unknownUnitDoesNotClaimVerifiedUnit() {
        val result=ReadingRules.inspect("001234",Energy.GAS)
        assertNull(result.unit)
        assertTrue(result.warnings.isNotEmpty())
    }
    @Test fun invoiceExtractsLabelledPriceAdvanceAndPeriod() {
        val draft=InvoiceParser.parse("Cena za dodávku 0,245 EUR/kWh vrátane DPH\nMesačná záloha: 45,00 EUR\nFakturačné obdobie: 01.01.2026 - 31.12.2026",Energy.ELECTRICITY)
        assertEquals("0.245",draft.prices.single().value)
        assertEquals("45.00",draft.advances.single().value)
        assertEquals(LocalDate.of(2026,1,1) to LocalDate.of(2026,12,31),draft.periods.single())
    }
    @Test fun totalsAndDatesAreNotGuessedAsUnitPrice() {
        val draft=InvoiceParser.parse("Celkom na úhradu 150,00 EUR\nSplatnosť 12.02.2026\nZálohy spolu 300,00 EUR",Energy.WATER)
        assertTrue(draft.prices.isEmpty());assertTrue(draft.advances.isEmpty());assertTrue(draft.periods.isEmpty())
    }
    @Test fun multiplePricesStayAmbiguousAndVatIsNotInvented() {
        val draft=InvoiceParser.parse("Cena dodávky 0,10 EUR/kWh bez DPH\nCena distribúcie 0,08 EUR/kWh bez DPH",Energy.ELECTRICITY)
        assertEquals(2,draft.prices.size)
        assertTrue(draft.prices.all { "bez DPH" in it.warning.orEmpty() })
        assertNull(draft.prices.singleOrNull())
    }
    @Test fun mwhConversionIsExplicitAndWaterRejectsElectricityPrice() {
        val gas=InvoiceParser.parse("Jednotková cena 80,00 EUR/MWh",Energy.GAS)
        assertEquals(0,gas.prices.single().value.toBigDecimal().compareTo("0.08".toBigDecimal()))
        assertTrue(InvoiceParser.parse("Cena 0,25 EUR/kWh",Energy.WATER).prices.isEmpty())
        assertEquals("m3",InvoiceParser.parse("Cena 1,25 EUR/m³",Energy.GAS).prices.single().unit)
    }
    @Test fun strictSegmentsNeverChooseNearestDigit() {
        assertEquals('8',SevenSegment.decode(List(7){1f}))
        assertEquals('0',SevenSegment.decode(listOf(1f,1f,1f,1f,1f,1f,0f)))
        assertEquals('1',SevenSegment.decode(listOf(0f,1f,1f,0f,0f,0f,0f)))
        assertNull(SevenSegment.decode(listOf(1f,.4f,1f,1f,1f,1f,0f)))
        assertNull(SevenSegment.decode(List(7){0f}))
    }
    @Test fun billingEndCapsConsumptionWithoutChangingOldOpenPeriod() {
        val e=Energy.WATER
        val rs=listOf(Reading(energy=e,date=LocalDate.of(2026,1,1),value="100".toBigDecimal()),Reading(energy=e,date=LocalDate.of(2026,2,1),value="120".toBigDecimal()),Reading(energy=e,date=LocalDate.of(2026,3,1),value="150".toBigDecimal()))
        val t=Tariff(e,price="2".toBigDecimal(),start=rs.first().date)
        assertEquals("100.00",Calculations.summary(Snapshot(rs,emptyList(),listOf(t)),e)!!.cost.toPlainString())
        assertEquals("40.00",Calculations.summary(Snapshot(rs,emptyList(),listOf(t.copy(end=LocalDate.of(2026,2,1)))),e)!!.cost.toPlainString())
    }
    @Test fun gasPricePerCubicMeterDoesNotApplyKwhFactor() {
        val e=Energy.GAS
        val rs=listOf(Reading(energy=e,date=LocalDate.of(2026,1,1),value="100".toBigDecimal()),Reading(energy=e,date=LocalDate.of(2026,2,1),value="110".toBigDecimal()))
        val t=Tariff(e,price="1.25".toBigDecimal(),factor="10.55".toBigDecimal(),start=rs.first().date,priceUnit="m3")
        assertEquals("12.50",Calculations.summary(Snapshot(rs,emptyList(),listOf(t)),e)!!.cost.toPlainString())
    }
}
