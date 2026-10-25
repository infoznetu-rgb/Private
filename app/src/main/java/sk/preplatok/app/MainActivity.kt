package sk.preplatok.app

import android.app.DatePickerDialog
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val Green = Color(0xFF146C60)
private val Ink = Color(0xFF172E2B)
private val Muted = Color(0xFF5D716D)
private val DateFormat = DateTimeFormatter.ofPattern("d. M. yyyy")
private fun LocalDate.pretty() = format(DateFormat)
private fun energyIcon(e: Energy): ImageVector = when(e) { Energy.ELECTRICITY -> Icons.Rounded.Bolt; Energy.WATER -> Icons.Rounded.WaterDrop; Energy.GAS -> Icons.Rounded.LocalFireDepartment }

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocale(java.util.Locale.forLanguageTag("sk"))
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme(colorScheme = lightColorScheme(primary = Green, background = Color(0xFFF5F8F5), surface = Color.White, onSurface = Ink, secondaryContainer = Color(0xFFDCEEE6))) { SimpleEnergyApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnergyApp(model: AppModel = viewModel()) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var energyName by rememberSaveable { mutableStateOf(Energy.ELECTRICITY.name) }
    val energy = Energy.valueOf(energyName)
    var add by rememberSaveable { mutableStateOf(false) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var suggested by rememberSaveable { mutableStateOf<String?>(null) }
    var ocrGeneration by rememberSaveable { mutableIntStateOf(0) }
    var photoUri by rememberSaveable { mutableStateOf<String?>(null) }
    val draftHolder = rememberSaveableStateHolder()
    var draftKey by rememberSaveable { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    val context = LocalContext.current
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) model.recognize(uri) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) photoUri?.let { model.recognize(it.toUri()) } }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) model.export(uri) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) model.inspectBackup(uri) }
    fun takePhoto() {
        runCatching {
            val folder = File(context.cacheDir, "camera").apply { mkdirs() }
            folder.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86_400_000 }?.forEach { it.delete() }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File.createTempFile("meter", ".jpg", folder))
            photoUri = uri.toString(); camera.launch(uri)
        }.onFailure { model.message = "Fotoaparát nie je dostupný. Vyberte fotografiu z galérie." }
    }
    BackHandler(enabled = add || model.scan != null) { if (!model.busy) { if (model.scan != null) model.scan = null else { add = false; suggested = null; editId = null; model.proof = null } } }
    Scaffold(
        topBar = { TopAppBar(title = { Column { Text(if (model.scan != null) "Skontrolujte displej" else if (add) "${if(editId == null) "Nový" else "Upraviť"} odpočet" else "Preplatok alebo nedoplatok", fontSize = 20.sp, fontWeight = FontWeight.Bold); if (!add && model.scan == null) Text("Energie pod kontrolou", fontSize = 12.sp, color = Muted) } }, navigationIcon = { if (add || model.scan != null) IconButton(enabled = !model.busy, onClick = { if (model.scan != null) model.scan = null else { add = false; suggested = null; editId = null; model.proof = null } }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Späť") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) },
        bottomBar = { if (!add && model.scan == null) NavigationBar(containerColor = Color.White) { listOf("Prehľad" to Icons.Rounded.Dashboard, "História" to Icons.AutoMirrored.Rounded.List, "Nastavenia" to Icons.Rounded.Settings).forEachIndexed { i, item -> NavigationBarItem(selected = page == i, onClick = { page = i }, icon = { Icon(item.second, item.first) }, label = { Text(item.first) }) } } }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            if (model.busy || !model.ready) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (model.scan != null) {
                Scanner(model.scan!!, model.busy, onCrop = model::recognizeCrop, onPick = { model.proof = model.scan!!.bitmap; suggested = it; ocrGeneration++; model.scan = null }, onManual = { model.scan = null; suggested = null; model.proof = null })
            } else if (model.ready) {
                if (add) draftHolder.SaveableStateProvider(draftKey) { ReadingForm(model, energy, editId?.let { id -> model.data.readings.firstOrNull { it.id == id } }, suggested, ocrGeneration, onCamera = { takePhoto() }, onGallery = { gallery.launch("image/*") }, done = { add = false; suggested = null; editId = null; model.proof = null }) }
                else Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Energy.entries.forEach { e -> FilterChip(selected = energy == e, onClick = { energyName = e.name }, label = { Text(e.title) }, leadingIcon = { Icon(energyIcon(e), null, Modifier.size(18.dp)) }) } }
                    when(page) {
                        0 -> Overview(model.data, energy, onAdd = { draftHolder.removeState(draftKey); draftKey = java.util.UUID.randomUUID().toString(); suggested = null; editId = null; add = true })
                        1 -> History(model, energy, onAdd = { draftHolder.removeState(draftKey); draftKey = java.util.UUID.randomUUID().toString(); suggested = null; editId = null; add = true }, onEdit = { draftHolder.removeState(draftKey); draftKey = java.util.UUID.randomUUID().toString(); editId = it.id; suggested = null; add = true })
                        else -> Settings(model, energy, onExport = { export.launch("energie-${LocalDate.now()}.json") }, onRestore = { restore.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
                    }
                }
            }
        }
    }
    if (model.message != null) AlertDialog(onDismissRequest = { model.message = null }, title = { Text("Informácia") }, text = { Text(model.message!!) }, confirmButton = { TextButton(onClick = { model.message = null }) { Text("Rozumiem") } })
    if (model.restoreCount != null) AlertDialog(onDismissRequest = model::cancelRestore, title = { Text("Nahradiť údaje zálohou?") }, text = { Text("Záloha obsahuje ${model.restoreCount}. Obnova nahradí všetky súčasné odpočty, platby a ceny. Pred obnovou odporúčame exportovať aktuálne údaje.") }, confirmButton = { TextButton(enabled = !model.busy, onClick = model::confirmRestore) { Text("Nahradiť a obnoviť") } }, dismissButton = { TextButton(onClick = model::cancelRestore) { Text("Zrušiť") } })
}

@Composable
internal fun Section(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
            content()
        }
    }
}
@Composable
private fun Stat(label: String, value: String) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = Muted, modifier = Modifier.weight(1f)); Text(value, fontWeight = FontWeight.SemiBold) } }
@Composable
internal fun PageColumn(content: @Composable ColumnScope.() -> Unit) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content) }

@Composable
private fun Overview(data: Snapshot, e: Energy, onAdd: () -> Unit) {
    val t = data.tariff(e)
    val summary = Calculations.summary(data, e)
    val rs = data.readings.filter { it.energy == e }.sortedBy { it.date }
    PageColumn {
        Card(colors = CardDefaults.cardColors(containerColor = Green), shape = RoundedCornerShape(28.dp)) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(energyIcon(e), null, tint = Color.White); Spacer(Modifier.width(8.dp)); Text(e.title.uppercase(), color = Color.White, letterSpacing = 2.sp, fontSize = 13.sp) }
                Text(if(summary == null) "Začnime prvým\nodpočtom" else if(t.price.signum() == 0) "Nastavte cenu energie" else if(summary.balance.signum() >= 0) "Priebežný preplatok" else "Priebežný nedoplatok", color = Color.White, fontSize = 19.sp)
                if (summary != null && t.price.signum() > 0) Text("${summary.balance.abs().display()} €", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(if(summary == null) "Na výpočet spotreby sú potrebné aspoň dva odpočty v období." else "${summary.first.date.pretty()} – ${summary.last.date.pretty()} • orientačný výpočet", color = Color(0xFFD4EAE3), fontSize = 12.sp)
                Button(onClick = onAdd, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Green), modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Add, null); Text("Pridať odpočet") }
            }
        }
        if (summary != null) {
            Section("Spotreba a náklady", "Interval medzi prvým a posledným odpočtom v období.") {
                Stat("Spotreba", "${summary.consumption.measurement()} ${e.unit}")
                if(e == Energy.GAS) Stat("Prepočet", "${summary.consumption.multiply(t.factor).display(3)} kWh")
                Stat("Odhad nákladov", "${summary.cost.display()} €")
                Stat("Zaplatené zálohy", "${summary.paid.display()} €")
                Stat("Plán záloh", "${summary.planned.display()} €")
                if (!summary.baselineExact) Text("Chýba odpočet k začiatku obdobia ${t.start.pretty()}. Výpočet začína až ${summary.first.date.pretty()}.", color = Color(0xFF975300), fontSize = 12.sp)
                if (t.price.signum() == 0) Text("Cena ešte nie je nastavená. Náklady ani zostatok zatiaľ nie sú úplné.", color = Color(0xFF975300), fontSize = 12.sp)
                Text("Platby sa započítavajú od prvého odpočtu vrátane po posledný odpočet bez tohto dňa. Plán záloh nie je zaplatená suma.", fontSize = 12.sp, color = Muted)
            }
        }
        Section("Vývoj spotreby", "Priemerná denná spotreba medzi odpočtami (${e.unit}/deň)") { ConsumptionChart(rs); if(rs.size < 2) Text("Pridajte aspoň dva odpočty.", color = Muted) }
        Section("Posledný odpočet") {
            val last = rs.lastOrNull()
            if(last == null) Text("Zatiaľ nemáte žiadne odpočty.", color = Muted)
            else { Stat(last.date.pretty(), "${last.value.measurement()} ${e.unit}"); Text(last.source, fontSize = 12.sp, color = Muted) }
        }
        Text("Údaje zostávajú vo vašom telefóne. Výpočet používa ceny z nastavení pre celé zobrazené obdobie; nejde o vyúčtovanie dodávateľa.", color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun ConsumptionChart(readings: List<Reading>) {
    val intervals = readings.zipWithNext().takeLast(12).map { (a,b) -> b.date to (b.value - a.value).toFloat() / ChronoUnit.DAYS.between(a.date,b.date).coerceAtLeast(1) }
    if(intervals.isEmpty()) return
    val max = intervals.maxOf { it.second }
    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
        val step = size.width / intervals.size
        drawLine(Color(0xFFE5ECE6), Offset(0f, size.height), Offset(size.width, size.height), 2f)
        intervals.forEachIndexed { i, pair -> val h = (pair.second / max.coerceAtLeast(0.001f)) * (size.height - 10); drawRoundRect(Green, Offset(step * i + step * 0.15f, size.height - h), Size(step * 0.7f, h.coerceAtLeast(2f)), androidx.compose.ui.geometry.CornerRadius(6f,6f)) }
    }
    Stat(intervals.first().first.pretty(), intervals.last().first.pretty())
    Text("Maximum ${BigDecimal(max.toString()).display(3)} za deň • ${intervals.size} intervalov", color = Muted, fontSize = 12.sp)
    intervals.forEach { (date, value) -> Stat(date.pretty(), BigDecimal(value.toString()).display(3)) }
}

@Composable
private fun ReadingForm(model: AppModel, energy: Energy, editing: Reading?, suggested: String?, generation: Int, onCamera: () -> Unit, onGallery: () -> Unit, done: () -> Unit) {
    var value by rememberSaveable(editing?.id) { mutableStateOf(editing?.value?.toPlainString() ?: "") }
    var day by rememberSaveable(editing?.id) { mutableStateOf(editing?.date?.toString() ?: LocalDate.now().toString()) }
    var note by rememberSaveable(editing?.id) { mutableStateOf(editing?.note ?: "") }
    var ocr by rememberSaveable(editing?.id) { mutableStateOf(false) }
    var confirmed by rememberSaveable(editing?.id, generation) { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(generation, suggested) { if(suggested != null) { value = suggested; ocr = true; confirmed = false } }
    PageColumn {
        Section(energy.title, "Kumulatívny stav merača v ${energy.unit}") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !model.busy, onClick = onCamera, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.PhotoCamera, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Odfotiť") }
                OutlinedButton(enabled = !model.busy, onClick = onGallery, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.PhotoLibrary, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Galéria") }
            }
            NumberField("Stav merača (${energy.unit})", value) { value = it; confirmed = false }
            DateField("Dátum odpočtu", day) { day = it }
            OutlinedTextField(value = note, onValueChange = { note = it.take(500) }, label = { Text("Poznámka (nepovinné)") }, modifier = Modifier.fillMaxWidth())
            if (ocr) {
                model.proof?.let { Image(it.asImageBitmap(), "Fotografia na kontrolu odpočtu", Modifier.fillMaxWidth().heightIn(max = 260.dp)) }
                Text("OCR je iba návrh. Porovnajte celé číslo s displejom vrátane desatinnej čiarky. Sériové číslo ani číslo tarify nie je odpočet.", color = Color(0xFF975300))
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = confirmed, onCheckedChange = { confirmed = it }); Text("Skontroloval/a som číslo podľa merača.", modifier = Modifier.weight(1f)) }
            }
            if(error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
            Button(enabled = !model.busy && (!ocr || confirmed), modifier = Modifier.fillMaxWidth(), onClick = {
                val number = decimal(value); val date = day.date()
                if(number == null || date == null || date.year < 1900) error = "Zadajte platný stav a dátum. Použite číslice a desatinnú čiarku."
                else {
                    val r = Reading(editing?.id ?: java.util.UUID.randomUUID().toString(), energy, date, number, note, if(ocr) "OCR · potvrdené používateľom" else if(editing != null) "Ručne upravené" else "Ručne")
                    error = Calculations.validate(model.data.readings,r)
                    if(error == null) model.change({ save(r) },done)
                }
            }) { Text(if(editing == null) "Potvrdiť a uložiť odpočet" else "Uložiť zmeny") }
        }
        Text("Tip: fotografujte kolmo, bez odlesku a s ostrými číslicami. Desatinné miesta na vodomere alebo plynomere môžu byť červené. Po výmene merača nezadávajte nižší stav do rovnakej evidencie.", color = Muted, fontSize = 13.sp)
    }
}

@Composable
internal fun NumberField(label: String, value: String, onChange: (String) -> Unit) { OutlinedTextField(value = value, onValueChange = { if(it.length <= 20) onChange(it) }, label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth()) }
@Composable
internal fun DateField(label: String, value: String, onChange: (String) -> Unit) {
    val context = LocalContext.current
    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { val d = value.date() ?: LocalDate.now(); DatePickerDialog(context, { _, y, m, day -> onChange(LocalDate.of(y,m+1,day).toString()) }, d.year,d.monthValue-1,d.dayOfMonth).show() }) { Icon(Icons.Rounded.CalendarMonth, null); Spacer(Modifier.width(8.dp)); Text("$label: ${value.date()?.pretty() ?: value}") }
}

@Composable
internal fun History(model: AppModel, energy: Energy, onAdd: () -> Unit, onEdit: (Reading) -> Unit) {
    var deleting by remember { mutableStateOf<Reading?>(null) }
    val readings = model.data.readings.filter { it.energy == energy }.sortedByDescending { it.date }
    PageColumn {
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Add, null); Text("Pridať odpočet") }
        Section("Vývoj spotreby", "Priemer za deň (${energy.unit}/deň)") { ConsumptionChart(readings.sortedBy { it.date }) }
        if(readings.isEmpty()) Section("História je zatiaľ prázdna") { Text("Prvý odpočet nastaví východiskový stav merača.", color = Muted) }
        readings.forEach { r ->
            Section(r.date.pretty()) {
                Text("${r.value.measurement()} ${energy.unit}", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                val previous = readings.filter { it.date < r.date }.maxByOrNull { it.date }
                if(previous != null) Text("+ ${(r.value - previous.value).measurement()} ${energy.unit} za ${ChronoUnit.DAYS.between(previous.date,r.date)} dní", color = Green)
                if(r.note.isNotBlank()) Text(r.note)
                Text(r.source, fontSize = 12.sp, color = Muted)
                Row { TextButton(enabled = !model.busy, onClick = { onEdit(r) }) { Text("Upraviť") }; TextButton(enabled = !model.busy, onClick = { deleting = r }) { Text("Odstrániť", color = MaterialTheme.colorScheme.error) } }
            }
        }
    }
    if(deleting != null) AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Odstrániť odpočet?") }, text = { Text("Odpočet z ${deleting!!.date.pretty()} sa odstráni a spotreba sa prepočíta.") }, confirmButton = { TextButton(onClick = { val id = deleting!!.id; deleting = null; model.change({ deleteReading(id) }) }) { Text("Odstrániť") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Zrušiť") } })
}

@Composable
internal fun Settings(model: AppModel, energy: Energy, onExport: () -> Unit, onRestore: () -> Unit) {
    val tariff = model.data.tariff(energy)
    var price by rememberSaveable(energy, tariff) { mutableStateOf(tariff.price.toPlainString()) }
    var fixed by rememberSaveable(energy, tariff) { mutableStateOf(tariff.fixed.toPlainString()) }
    var advance by rememberSaveable(energy, tariff) { mutableStateOf(tariff.advance.toPlainString()) }
    var factor by rememberSaveable(energy, tariff) { mutableStateOf(tariff.factor.toPlainString()) }
    var start by rememberSaveable(energy, tariff) { mutableStateOf(tariff.start.toString()) }
    var paymentAmount by rememberSaveable(energy) { mutableStateOf("") }
    var paymentDate by rememberSaveable(energy) { mutableStateOf(LocalDate.now().toString()) }
    var error by rememberSaveable(energy) { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Payment?>(null) }
    PageColumn {
        Section("Ceny · ${energy.title}", "Konečné ceny vrátane DPH a distribúcie. Nulová cena znamená neúplný odhad.") {
            NumberField("Cena za ${if(tariff.priceUnit=="m3")"m³" else "kWh"} (€)", price) { price = it }
            NumberField("Stály mesačný poplatok (€)", fixed) { fixed = it }
            NumberField("Plánovaná mesačná záloha (€)", advance) { advance = it }
            if(energy == Energy.GAS && tariff.priceUnit!="m3") { NumberField("Prevodný faktor kWh / m³", factor) { factor = it }; Text("Zadajte faktor podľa vyúčtovania dodávateľa.", fontSize = 12.sp, color = Muted) }
            DateField("Začiatok sledovaného obdobia", start) { start = it }
            Text("Zmena cien prepočíta celé zobrazené obdobie. Pri zmene tarify urobte zálohu a začnite nové obdobie s odpočtom k jeho začiatku.", fontSize = 12.sp, color = Muted)
            if(error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
            Button(enabled = !model.busy, modifier = Modifier.fillMaxWidth(), onClick = {
                val p = decimal(price); val f = decimal(fixed); val a = decimal(advance); val k = if(energy == Energy.GAS) decimal(factor) else BigDecimal.ONE; val d = start.date()
                if(p == null || f == null || a == null || k == null || k.signum() <= 0 || d == null || d.year !in 1900..2200) error = "Zadajte platné nezáporné ceny a kladný prevodný faktor."
                else { error = null; model.change({ save(Tariff(energy,p,f,a,k,d,tariff.end?.takeIf { it>=d },tariff.priceUnit)) }) { model.message = "Nastavenia boli uložené." } }
            }) { Text("Uložiť nastavenia") }
        }
        Section("Zaplatené zálohy", "Zapisujte skutočne uhradené platby. Mesačný plán sa automaticky nepovažuje za zaplatený.") {
            NumberField("Zaplatená suma (€)", paymentAmount) { paymentAmount = it }
            DateField("Dátum platby", paymentDate) { paymentDate = it }
            OutlinedButton(enabled = !model.busy, modifier = Modifier.fillMaxWidth(), onClick = {
                val a = decimal(paymentAmount); val d = paymentDate.date()
                if(a == null || a.signum() <= 0 || d == null || d > LocalDate.now() || d.year < 1900) model.message = "Zadajte kladnú sumu a dátum platby, ktorý nie je v budúcnosti."
                else model.change({ save(Payment(energy = energy, date = d, amount = a)) }) { paymentAmount = ""; model.message = "Platba bola uložená." }
            }) { Text("Pridať zaplatenú zálohu") }
            model.data.payments.filter { it.energy == energy }.sortedByDescending { it.date }.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) { Text(p.date.pretty(), Modifier.weight(1f)); Text("${p.amount.display()} €"); IconButton(enabled = !model.busy, onClick = { deleting = p }) { Icon(Icons.Rounded.DeleteOutline,"Odstrániť platbu z ${p.date.pretty()}") } }
            }
        }
        Section("Záloha a súkromie", "Bez účtu, reklám, servera a plateného API.") {
            Text("Odpočty, platby a nastavenia sú v lokálnej databáze. Fotografie sa nezahŕňajú do zálohy ani neodosielajú na server. Rozpoznávanie používa model priamo v aplikácii.", fontSize = 13.sp, color = Muted)
            OutlinedButton(enabled = !model.busy, onClick = onExport, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.FileUpload, null); Spacer(Modifier.width(8.dp)); Text("Exportovať zálohu") }
            OutlinedButton(enabled = !model.busy, onClick = onRestore, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("Obnoviť zo zálohy") }
            Text("Záloha je čitateľný súbor JSON bez šifrovania. Uložte ju na bezpečné miesto. Odinštalovanie aplikácie vymaže lokálnu databázu.", fontSize = 12.sp, color = Muted)
        }
        Text("Preplatok alebo nedoplatok • verzia 1.0\nAndroid 8 a novší • slovenská aplikácia", fontSize = 12.sp, color = Muted)
    }
    if(deleting != null) AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Odstrániť platbu?") }, text = { Text("Platba ${deleting!!.amount.display()} € z ${deleting!!.date.pretty()} sa odstráni.") }, confirmButton = { TextButton(onClick = { val id = deleting!!.id; deleting = null; model.change({ deletePayment(id) }) }) { Text("Odstrániť") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Zrušiť") } })
}

@Composable
internal fun Scanner(scan: Scan, busy: Boolean, onCrop: (Float,Float,Float,Float) -> Unit, onPick: (String) -> Unit, onManual: () -> Unit) {
    val bitmap = scan.bitmap
    val suggested = scan.candidates.firstOrNull()?.rect
    fun normalized(rect: android.graphics.Rect?): List<Float> = if(rect == null) listOf(0.1f,0.25f,0.9f,0.75f) else listOf((rect.left.toFloat()/bitmap.width - 0.02f).coerceIn(0f,0.98f), (rect.top.toFloat()/bitmap.height - 0.03f).coerceIn(0f,0.98f), (rect.right.toFloat()/bitmap.width + 0.02f).coerceIn(0.02f,1f), (rect.bottom.toFloat()/bitmap.height + 0.03f).coerceIn(0.02f,1f))
    var crop by remember(bitmap) { mutableStateOf(normalized(suggested)) }
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    PageColumn {
        Text("Návrh oblasti číslic", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Rámik je odhad podľa rozpoznaného textu, nie isté určenie displeja. Potiahnutím cez fotografiu označte celý displej. Môžete použiť aj posuvníky.", color = Muted, fontSize = 13.sp)
        Box(Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat()/bitmap.height)) {
            Image(bitmap.asImageBitmap(), "Fotografia merača", modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize().pointerInput(bitmap,busy) {
                if(!busy) detectDragGestures(onDragStart = { dragStart = it }, onDrag = { change, _ ->
                    change.consume()
                    val p = change.position
                    val l = (minOf(dragStart.x,p.x)/size.width).coerceIn(0f,0.98f)
                    val t = (minOf(dragStart.y,p.y)/size.height).coerceIn(0f,0.98f)
                    crop = listOf(l,t,(maxOf(dragStart.x,p.x)/size.width).coerceIn(l+0.01f,1f),(maxOf(dragStart.y,p.y)/size.height).coerceIn(t+0.01f,1f))
                })
            }) {
                drawRect(Color(0x77000000), Offset.Zero, Size(size.width, crop[1]*size.height))
                drawRect(Color(0x77000000), Offset(0f,crop[3]*size.height), Size(size.width,(1-crop[3])*size.height))
                drawRect(Color(0x77000000), Offset(0f,crop[1]*size.height), Size(crop[0]*size.width,(crop[3]-crop[1])*size.height))
                drawRect(Color(0x77000000), Offset(crop[2]*size.width,crop[1]*size.height), Size((1-crop[2])*size.width,(crop[3]-crop[1])*size.height))
                drawRect(Color(0xFF65FFCD), Offset(crop[0]*size.width,crop[1]*size.height),Size((crop[2]-crop[0])*size.width,(crop[3]-crop[1])*size.height),style = Stroke(3.dp.toPx()))
            }
        }
        listOf("Ľavý okraj","Horný okraj","Pravý okraj","Dolný okraj").forEachIndexed { index,label ->
            Column { Text(label, fontSize = 12.sp, color = Muted); Slider(enabled = !busy, value = crop[index], onValueChange = { value ->
                val next = crop.toMutableList()
                next[index] = when(index) { 0 -> value.coerceIn(0f,crop[2]-0.01f); 1 -> value.coerceIn(0f,crop[3]-0.01f); 2 -> value.coerceIn(crop[0]+0.01f,1f); else -> value.coerceIn(crop[1]+0.01f,1f) }
                crop = next
            }) }
        }
        Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { onCrop(crop[0],crop[1],crop[2],crop[3]) }) { Icon(Icons.Rounded.Crop, null); Spacer(Modifier.width(8.dp)); Text("Rozpoznať označený výrez") }
        Section("Nájdené čísla · overenie je povinné", "OCR môže zameniť číslice, vynechať čiarku alebo prečítať sériové číslo. Spoľahlivosť nemožno zaručiť.") {
            if(scan.candidates.isEmpty()) Text("Číslo sa nepodarilo rozpoznať. Vyrežte displej alebo zadajte stav ručne.")
            scan.candidates.forEach { c ->
                OutlinedButton(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { onPick(c.text) }) { Text("Skontrolovať návrh: ${c.text}") }
            }
        }
        OutlinedButton(enabled = !busy, onClick = onManual, modifier = Modifier.fillMaxWidth()) { Text("Zadať stav ručne") }
    }
}
