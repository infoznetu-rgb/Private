package sk.preplatok.app

import java.math.BigDecimal
import java.text.Normalizer
import java.time.LocalDate

internal fun normalized(text: String) = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace('³','3')
data class ReadingContext(val unit: String?, val kind: String?, val blocked: Boolean, val warnings: List<String>)
object ReadingRules {
    fun inspect(text: String, energy: Energy): ReadingContext {
        val t = normalized(text)
        val unit = when { Regex("\\bkwh\\b").containsMatchIn(t) -> "kWh"; Regex("\\bm[3]\\b").containsMatchIn(t) -> "m³"; Regex("\\bmwh\\b").containsMatchIn(t) -> "MWh"; else -> null }
        val kind = Regex("(?<![0-9.])[12]\\.8\\.[012](?![0-9.])").find(t)?.value
        val warnings = mutableListOf<String>()
        val incompatible = unit != null && unit != energy.unit
        if(incompatible) warnings += "Na fotografii je jednotka $unit, vybraná energia používa ${energy.unit}."
        val wrongRegister = kind != null && kind != "1.8.0"
        if(wrongRegister) warnings += "Register $kind nie je celkový odber 1.8.0. Zobrazte na merači celkový stav."
        if(unit == null) warnings += "Jednotku sa nepodarilo overiť. Skontrolujte ${energy.unit}."
        val serial=Regex("vyrobne|seriove|serial|meter no|cislo mer").containsMatchIn(t)
        if(serial) warnings += "Text môže obsahovať výrobné číslo merača."
        return ReadingContext(unit,kind,incompatible || wrongRegister || serial,warnings)
    }
    fun numericValues(text: String): List<String> {
        val cleaned = text.replace(Regex("(?<![0-9.])[12]\\.8\\.[012](?![0-9.])"), " ")
        if(Regex("vyrobne|seriove|serial|meter no|cislo mer").containsMatchIn(normalized(text))) return emptyList()
        return OcrNumbers.candidates(cleaned).filter { it.count(Char::isDigit) >= 2 }
    }
}
data class InvoiceField(val value: String, val evidence: String, val warning: String? = null, val unit: String? = null)
data class InvoiceDraft(val prices: List<InvoiceField>, val advances: List<InvoiceField>, val periods: List<Pair<LocalDate,LocalDate>>, val warnings: List<String>)
object InvoiceParser {
    private val amount = "([0-9]{1,6}(?:[ .,][0-9]{3})*[,.][0-9]{1,6}|[0-9]{1,6})"
    fun parse(text: String, energy: Energy): InvoiceDraft {
        val prices = mutableListOf<InvoiceField>(); val advances = mutableListOf<InvoiceField>(); val periods = mutableListOf<Pair<LocalDate,LocalDate>>()
        val warnings = mutableListOf<String>()
        val lines = text.lines().filter { it.isNotBlank() }
        for ((index,line) in lines.withIndex()) {
            val t = normalized(line)
            val evidence = line.take(220)
            val unitMatch = Regex("$amount\\s*(?:€|eur)\\s*/\\s*(kwh|mwh|m3)",RegexOption.IGNORE_CASE).find(t)
            if(unitMatch != null && Regex("cen|sadzb|tarif").containsMatchIn(t)) {
                val unit = unitMatch.groupValues[2].lowercase()
                val compatible = (energy == Energy.WATER && unit == "m3") || (energy != Energy.WATER && unit in listOf("kwh","mwh")) || (energy == Energy.GAS && unit == "m3")
                val n = decimal(unitMatch.groupValues[1].replace(" ",""))
                if(compatible && n != null) {
                    val value = if(unit == "mwh") n.movePointLeft(3) else n
                    val warn = when { "bez dph" in t -> "Cena je bez DPH. Zadajte konečnú cenu vrátane DPH a distribúcie."; energy == Energy.GAS && unit == "m3" -> "Cena je za m³. Prevodný faktor bude potrebné nastaviť na 1."; else -> "Overte, či cena zahŕňa DPH aj distribúciu." }
                    prices += InvoiceField(value.toPlainString(),evidence,warn,if(unit=="mwh")"kwh" else unit)
                } else warnings += "Na faktúre je aj cena s inou jednotkou. Skontrolujte vybranú energiu."
            }
            if(Regex("zaloh|preddav|mesacna platba").containsMatchIn(t) && !Regex("celkom|sucet|spolu|nedoplatok|preplatok").containsMatchIn(t)) {
                val amounts = Regex("$amount\\s*(?:€|eur)(?!/)",RegexOption.IGNORE_CASE).findAll(t).toList()
                amounts.forEach { m -> decimal(m.groupValues[1].replace(" ",""))?.let { advances += InvoiceField(it.toPlainString(),evidence,"Overte, že ide o jednu mesačnú zálohu, nie súčet platieb.") } }
            }
            if(Regex("obdob|odber od|dodavk.*od|faktur.*od").containsMatchIn(t)) {
                val context = line + " " + lines.getOrNull(index+1).orEmpty()
                val dates = Regex("(?<![0-9])([0-3]?[0-9])\\.\\s*([01]?[0-9])\\.\\s*(20[0-9]{2})(?![0-9])").findAll(context).mapNotNull { m -> runCatching { LocalDate.of(m.groupValues[3].toInt(),m.groupValues[2].toInt(),m.groupValues[1].toInt()) }.getOrNull() }.toList()
                if(dates.size >= 2 && dates[0] <= dates[1]) periods += dates[0] to dates[1]
            }
        }
        if(prices.isEmpty()) warnings += "Konečnú jednotkovú cenu sa nepodarilo jednoznačne nájsť. Sumu celej faktúry nepoužívame ako cenu energie."
        return InvoiceDraft(prices.distinctBy { Triple(it.value,it.warning,it.unit) },advances.distinctBy { it.value },periods.distinct(),warnings.distinct())
    }
}

/** Strict seven-segment codebook. Unknown or uncertain segments must return null, never nearest digit. */
object SevenSegment {
    private val digits = mapOf(0b1111110 to '0', 0b0110000 to '1', 0b1101101 to '2', 0b1111001 to '3', 0b0110011 to '4', 0b1011011 to '5', 0b1011111 to '6', 0b1110000 to '7', 0b1111111 to '8', 0b1111011 to '9')
    fun decode(levels: List<Float>): Char? {
        if(levels.size != 7 || levels.any { it in 0.24f..0.55f || !it.isFinite() }) return null
        val bits = levels.fold(0) { n,v -> (n shl 1) or if(v > 0.55f) 1 else 0 }
        return digits[bits]
    }
}
