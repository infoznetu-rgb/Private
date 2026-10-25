package sk.preplatok.app

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class Energy(val title: String, val unit: String) {
    ELECTRICITY("Elektrina", "kWh"), WATER("Voda", "m³"), GAS("Plyn", "m³")
}
fun decimal(text: String): BigDecimal? = text.trim().replace(',', '.').takeIf { it.matches(Regex("[0-9]{1,12}(\\.[0-9]{1,6})?")) }?.toBigDecimalOrNull()
fun BigDecimal.display(scale: Int = 2): String = setScale(scale, RoundingMode.HALF_UP).toPlainString().replace('.', ',')
fun BigDecimal.measurement(): String = stripTrailingZeros().toPlainString().replace('.', ',')
fun String.date(): LocalDate? = runCatching { LocalDate.parse(this) }.getOrNull()
data class Reading(val id: String = UUID.randomUUID().toString(), val energy: Energy, val date: LocalDate, val value: BigDecimal, val note: String = "", val source: String = "Ručne")
data class Payment(val id: String = UUID.randomUUID().toString(), val energy: Energy, val date: LocalDate, val amount: BigDecimal)
data class Tariff(val energy: Energy, val price: BigDecimal = BigDecimal.ZERO, val fixed: BigDecimal = BigDecimal.ZERO, val advance: BigDecimal = BigDecimal.ZERO, val factor: BigDecimal = BigDecimal.ONE, val start: LocalDate = LocalDate.now().withDayOfYear(1), val end: LocalDate? = null, val priceUnit: String = if(energy==Energy.WATER)"m3" else "kwh")
data class Snapshot(val readings: List<Reading>, val payments: List<Payment>, val tariffs: List<Tariff>) {
    fun tariff(e: Energy) = tariffs.firstOrNull { it.energy == e } ?: Tariff(e)
}
data class Summary(val first: Reading, val last: Reading, val consumption: BigDecimal, val cost: BigDecimal, val paid: BigDecimal, val planned: BigDecimal, val baselineExact: Boolean) {
    val balance: BigDecimal get() = paid - cost
}
object Calculations {
    fun validate(readings: List<Reading>, reading: Reading, today: LocalDate = LocalDate.now()): String? {
        if (reading.date > today) return "Odpočet nemôže byť v budúcnosti."
        if (reading.value.signum() < 0) return "Stav merača musí byť nezáporný."
        val same = readings.filter { it.energy == reading.energy && it.id != reading.id }.sortedBy { it.date }
        if (same.any { it.date == reading.date }) return "Pre tento deň už odpočet existuje. Upravte ho v histórii."
        if (same.lastOrNull { it.date < reading.date }?.let { it.value > reading.value } == true) return "Stav je nižší než predchádzajúci odpočet. Pri výmene merača začnite novú evidenciu po zálohe."
        if (same.firstOrNull { it.date > reading.date }?.let { it.value < reading.value } == true) return "Stav je vyšší než nasledujúci odpočet."
        return null
    }
    // Costs cover [first date, last date). Fixed fees are prorated by actual days in each calendar month.
    fun fixedCost(start: LocalDate, end: LocalDate, monthly: BigDecimal): BigDecimal {
        var day = start
        var total = BigDecimal.ZERO
        while (day < end) {
            val next = minOf(day.withDayOfMonth(1).plusMonths(1), end)
            total += monthly.multiply(BigDecimal(ChronoUnit.DAYS.between(day, next))).divide(BigDecimal(day.lengthOfMonth()), 12, RoundingMode.HALF_UP)
            day = next
        }
        return total
    }
    fun summary(data: Snapshot, energy: Energy): Summary? {
        val t = data.tariff(energy)
        val rs = data.readings.filter { it.energy == energy && it.date >= t.start && (t.end == null || it.date <= t.end) }.sortedBy { it.date }
        if (rs.size < 2) return null
        val first = rs.first(); val last = rs.last()
        val used = last.value - first.value
        val cost = used.multiply(if(t.priceUnit=="m3")BigDecimal.ONE else t.factor).multiply(t.price) + fixedCost(first.date, last.date, t.fixed)
        val paid = data.payments.filter { it.energy == energy && it.date >= first.date && it.date < last.date }.fold(BigDecimal.ZERO) { acc, p -> acc + p.amount }
        var month = first.date.withDayOfMonth(1)
        var count = 0
        while (month < last.date) { if (month >= first.date) count++; month = month.plusMonths(1) }
        return Summary(first, last, used, cost.setScale(2, RoundingMode.HALF_UP), paid, t.advance.multiply(BigDecimal(count)), first.date == t.start)
    }
}

object OcrNumbers {
    // Never substitute O/0, S/5, etc. A numeric candidate is a suggestion, never an accepted reading.
    fun candidates(text: String): List<String> = Regex("(?<![\\p{L}\\d])[0-9]{1,10}(?:[.,][0-9]{1,4})?(?![\\p{L}\\d])")
        .findAll(text).map { it.value }.filter { decimal(it) != null }.distinct().toList()
}
