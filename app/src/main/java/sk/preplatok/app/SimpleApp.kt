package sk.preplatok.app

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleEnergyApp(model: AppModel = viewModel(), startDestination: String = "home") {
    var route by rememberSaveable { mutableStateOf(startDestination) }
    var energyName by rememberSaveable { mutableStateOf(Energy.ELECTRICITY.name) }
    val energy=Energy.valueOf(energyName)
    var invoiceMode by rememberSaveable { mutableStateOf(false) }
    var draftId by rememberSaveable { mutableStateOf<String?>(null) }
    var draftValue by rememberSaveable { mutableStateOf("") }
    var draftDate by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var draftNote by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf("Ručne") }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    fun newDraft() { draftId=null;draftValue="";draftDate=LocalDate.now().toString();draftNote="";source="Ručne";model.scan=null;model.proof=null;error=null }
    fun photo(uri: android.net.Uri) { model.recognize(uri,energy,invoiceMode);route=if(invoiceMode)"invoice" else "review" }
    val gallery=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { if(it!=null)photo(it) }
    val invoicePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { if(it!=null)photo(it) }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { if(it!=null)model.export(it) }
    val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { if(it!=null)model.inspectBackup(it) }
    fun startCamera(invoice: Boolean) { if(!invoice)newDraft();invoiceMode=invoice;model.invoice=null;route="camera" }
    fun saveReading() {
        val number=decimal(draftValue);val date=draftDate.date()
        if(number==null || date==null || date.year<1900) { error="Zadajte číslo z merača a platný dátum.";return }
        val r=Reading(draftId ?: UUID.randomUUID().toString(),energy,date,number,draftNote,source)
        error=Calculations.validate(model.data.readings,r)
        if(error==null)model.change({save(r)}) { route="home";model.scan=null;model.proof=null;model.message="Odpočet bol uložený." }
    }
    LaunchedEffect(model.scan) { model.scan?.let { scan -> selected=scan.candidates.indexOfFirst { !it.blocked }.coerceAtLeast(0);draftValue=scan.candidates.getOrNull(selected)?.text ?: "";source="OCR · potvrdené používateľom" } }
    BackHandler(enabled=route!="home") { if(!model.busy) { route=when(route){"camera"->if(invoiceMode)"settings" else "home";"advanced","invoice"->"settings";else->"home"};error=null } }
    Scaffold(topBar={TopAppBar(title={Text(when(route){"home"->"Preplatok alebo nedoplatok";"camera"->if(invoiceMode)"Faktúra" else energy.title;"review"->"Je číslo správne?";"manual"->"Zadajte stav merača";"history"->"História · ${energy.title}";"invoice"->"Skontrolujte faktúru";"advanced"->"Ďalšie možnosti";else->"Ceny a zálohy"},fontSize=19.sp,fontWeight=FontWeight.Bold)},navigationIcon={if(route!="home")IconButton(enabled=!model.busy,onClick={route=if(route in listOf("advanced","invoice"))"settings" else "home";error=null}){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Späť")}})}) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if(model.busy || !model.ready) { LinearProgressIndicator(Modifier.fillMaxWidth());Text(if(invoiceMode)"Čítam faktúru v telefóne…" else "Hľadám displej a čítam číslice…",Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall) }
            if(model.ready) when(route) {
                "home" -> SimpleHome(model,energy,onEnergy={energyName=it.name},onCamera={startCamera(false)},onGallery={newDraft();invoiceMode=false;gallery.launch("image/*")},onManual={newDraft();route="manual"},onHistory={route="history"},onSettings={route="settings"})
                "camera" -> CameraScreen(invoiceMode,onPhoto={photo(it)},onGallery={if(invoiceMode)invoicePicker.launch(arrayOf("image/*","application/pdf")) else gallery.launch("image/*")},onError={model.message=it})
                "review" -> ReviewReading(model,energy,selected,draftValue,error,onSelect={index->selected=index;draftValue=model.scan!!.candidates[index].text},onConfirm={saveReading()},onCorrect={source="Ručne opravené";route="manual"},onCrop={route="crop"},onRetry={startCamera(false)})
                "manual" -> PageColumn {
                    Text(energy.title,style=MaterialTheme.typography.headlineSmall)
                    NumberField("Stav merača (${energy.unit})",draftValue){draftValue=it;error=null}
                    DateField("Dátum",draftDate){draftDate=it}
                    if(error!=null)Text(error!!,color=MaterialTheme.colorScheme.error)
                    Button(enabled=!model.busy,onClick={saveReading()},modifier=Modifier.fillMaxWidth().heightIn(min=60.dp)){Text("Potvrdiť odpočet")}
                }
                "crop" -> model.scan?.let { scan -> Scanner(scan,model.busy,model::recognizeCrop,onPick={draftValue=it;selected=model.scan!!.candidates.indexOfFirst { c->c.text==it }.coerceAtLeast(0);route="review"},onManual={route="manual";source="Ručne opravené"}) }
                "history" -> History(model,energy,onAdd={newDraft();route="manual"},onEdit={r->draftId=r.id;draftValue=r.value.toPlainString();draftDate=r.date.toString();draftNote=r.note;source="Ručne upravené";route="manual"})
                "settings" -> SimpleSettings(model,energy,onInvoice={startCamera(true)},onInvoiceGallery={invoiceMode=true;model.invoice=null;invoicePicker.launch(arrayOf("image/*","application/pdf"))},onAdvanced={route="advanced"})
                "invoice" -> InvoiceReview(model,energy,onDone={route="settings"})
                "advanced" -> Settings(model,energy,onExport={export.launch("energie-${LocalDate.now()}.json")},onRestore={restore.launch(arrayOf("application/json","text/plain","application/octet-stream"))})
            }
        }
    }
    model.message?.let { text -> AlertDialog(onDismissRequest={model.message=null},title={Text("Informácia")},text={Text(text)},confirmButton={TextButton(onClick={model.message=null}){Text("Rozumiem")}}) }
    if(model.restoreCount!=null)AlertDialog(onDismissRequest=model::cancelRestore,title={Text("Nahradiť údaje zálohou?")},text={Text("Záloha: ${model.restoreCount}. Všetky súčasné údaje sa nahradia. Pred obnovou si ich zálohujte.")},confirmButton={TextButton(enabled=!model.busy,onClick=model::confirmRestore){Text("Nahradiť údaje")}},dismissButton={TextButton(onClick=model::cancelRestore){Text("Zrušiť")}})
}

@Composable
private fun SimpleHome(model: AppModel,e: Energy,onEnergy:(Energy)->Unit,onCamera:()->Unit,onGallery:()->Unit,onManual:()->Unit,onHistory:()->Unit,onSettings:()->Unit) {
    val summary=Calculations.summary(model.data,e)
    val tariff=model.data.tariff(e)
    PageColumn {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) { Energy.entries.forEach { energy ->
            val icon=when(energy){Energy.ELECTRICITY->Icons.Rounded.Bolt;Energy.WATER->Icons.Rounded.WaterDrop;Energy.GAS->Icons.Rounded.LocalFireDepartment}
            FilledTonalButton(onClick={onEnergy(energy)},colors=ButtonDefaults.filledTonalButtonColors(containerColor=if(e==energy)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),modifier=Modifier.weight(1f),contentPadding=PaddingValues(horizontal=4.dp,vertical=16.dp)) { Column(horizontalAlignment=Alignment.CenterHorizontally){Icon(icon,null);Text(energy.title,fontSize=14.sp,fontWeight=if(e==energy)FontWeight.Bold else FontWeight.Normal)} }
        } }
        Text("${e.title} pod kontrolou",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
        Text("Stačí odfotiť displej. Číslo potom skontrolujete.",style=MaterialTheme.typography.bodyLarge)
        Button(enabled=!model.busy,onClick=onCamera,modifier=Modifier.fillMaxWidth().heightIn(min=96.dp)) { Icon(Icons.Rounded.PhotoCamera,null,Modifier.size(30.dp));Spacer(Modifier.width(12.dp));Text("Odfotiť merač",fontSize=23.sp,fontWeight=FontWeight.Bold) }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) { OutlinedButton(enabled=!model.busy,onClick=onGallery,modifier=Modifier.weight(1f)){Text("Vybrať fotku")};OutlinedButton(enabled=!model.busy,onClick=onManual,modifier=Modifier.weight(1f)){Text("Zadať ručne")} }
        Section("Váš prehľad") {
            if(summary==null)Text("Pridajte dva odpočty na výpočet spotreby.") else {
                Text("Spotreba ${summary.consumption.measurement()} ${e.unit}",style=MaterialTheme.typography.titleLarge)
                if(tariff.price.signum()>0) { Text("${if(summary.balance.signum()>=0)"Priebežný preplatok" else "Priebežný nedoplatok"} ${summary.balance.abs().display()} €",fontWeight=FontWeight.Bold);Text("Náklady ${summary.cost.display()} € · zaplatené zálohy ${summary.paid.display()} €") } else Text("Pre výpočet nákladov nastavte cenu energie.")
                Text("${summary.first.date} – ${summary.last.date} (posledný deň bez platieb)",style=MaterialTheme.typography.bodySmall)
                if(!summary.baselineExact)Text("Chýba stav k začiatku obdobia. Výpočet začína prvým dostupným odpočtom.",style=MaterialTheme.typography.bodySmall)
            }
            model.data.readings.filter { it.energy==e }.maxByOrNull { it.date }?.let { Text("Posledný stav: ${it.value.measurement()} ${e.unit} · ${it.date}",style=MaterialTheme.typography.bodySmall) }
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick=onHistory,modifier=Modifier.weight(1f)){Text("História")};OutlinedButton(onClick=onSettings,modifier=Modifier.weight(1f)){Text("Ceny a zálohy")} }
        Text("Bez internetu. Vaše údaje zostávajú v telefóne.",style=MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ReviewReading(model: AppModel,energy: Energy,selected: Int,value: String,error: String?,onSelect:(Int)->Unit,onConfirm:()->Unit,onCorrect:()->Unit,onCrop:()->Unit,onRetry:()->Unit) {
    val scan=model.scan
    val candidate=scan?.candidates?.getOrNull(selected)
    var alternatives by remember { mutableStateOf(false) }
    var wholePhoto by remember(scan) { mutableStateOf(false) }
    val enlarged=remember(scan,selected) {
        if(scan!=null && candidate!=null) {
            val b=scan.bitmap;val r=candidate.rect;val margin=r.height()/2
            MeterOcr.crop(b,((r.left-margin).coerceAtLeast(0)).toFloat()/b.width,((r.top-margin).coerceAtLeast(0)).toFloat()/b.height,((r.right+margin).coerceAtMost(b.width)).toFloat()/b.width,((r.bottom+margin).coerceAtMost(b.height)).toFloat()/b.height)
        } else scan?.bitmap
    }
    PageColumn {
        if(scan!=null) {
            Image((if(wholePhoto)scan.bitmap else enlarged ?: scan.bitmap).asImageBitmap(),"Displej merača na kontrolu",Modifier.fillMaxWidth().heightIn(max=220.dp))
            TextButton(onClick={wholePhoto=!wholePhoto}){Text(if(wholePhoto)"Priblížiť displej" else "Zobraziť celú fotografiu")}
        }
        if(candidate==null) {
            if(!model.busy) { Text("Číslo sa nepodarilo spoľahlivo prečítať.",style=MaterialTheme.typography.headlineSmall);Text("Nebudeme ho hádať. Zadajte číslo z displeja alebo skúste novú fotku.") }
        } else {
            Text(value,fontSize=48.sp,fontWeight=FontWeight.Bold,modifier=Modifier.fillMaxWidth())
            Text(energy.unit,style=MaterialTheme.typography.titleLarge)
            Text("Porovnajte celé číslo s displejom vrátane desatinnej čiarky.")
            (scan.warnings+candidate.warnings).distinct().forEach { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium) }
        }
        if(error!=null)Text(error,color=MaterialTheme.colorScheme.error)
        Button(enabled=!model.busy && candidate!=null && !candidate.blocked,onClick=onConfirm,modifier=Modifier.fillMaxWidth().heightIn(min=64.dp)){Text("Potvrdiť",fontSize=22.sp)}
        OutlinedButton(enabled=!model.busy,onClick=onCorrect,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)){Text(if(candidate==null)"Zadať ručne" else "Opraviť",fontSize=20.sp)}
        TextButton(enabled=!model.busy,onClick=onRetry,modifier=Modifier.fillMaxWidth()){Text("Odfotiť znova")}
        if((scan?.candidates?.size ?: 0)>1) {
            TextButton(onClick={alternatives=!alternatives}){Text("Iné nájdené čísla")}
            if(alternatives)scan!!.candidates.forEachIndexed { i,c->OutlinedButton(onClick={onSelect(i)}){Text(c.text+if(c.blocked)" · overte typ odpočtu" else "")} }
        }
        if(scan!=null)TextButton(enabled=!model.busy,onClick=onCrop){Text("Upresniť výrez (nepovinné)")}
    }
}

@Composable
private fun SimpleSettings(model: AppModel,energy: Energy,onInvoice:()->Unit,onInvoiceGallery:()->Unit,onAdvanced:()->Unit) {
    val t=model.data.tariff(energy)
    var price by rememberSaveable(t,energy){mutableStateOf(t.price.toPlainString())}
    var advance by rememberSaveable(t,energy){mutableStateOf(t.advance.toPlainString())}
    var priceUnit by rememberSaveable(t,energy){mutableStateOf(t.priceUnit)}
    var factor by rememberSaveable(t,energy){mutableStateOf(t.factor.toPlainString())}
    var payment by rememberSaveable(energy){mutableStateOf("")}
    PageColumn {
        Section("${energy.title} · jednoducho z faktúry") {
            Button(enabled=!model.busy,onClick=onInvoice,modifier=Modifier.fillMaxWidth()){Text("Odfotiť faktúru")}
            OutlinedButton(enabled=!model.busy,onClick=onInvoiceGallery,modifier=Modifier.fillMaxWidth()){Text("Vybrať faktúru (fotka alebo PDF)")}
            Text("Nájdené údaje pred použitím skontrolujete.",style=MaterialTheme.typography.bodySmall)
        }
        Section("Cena a mesačná záloha") {
            if(energy==Energy.GAS)Row { FilterChip(selected=priceUnit=="kwh",onClick={priceUnit="kwh"},label={Text("Cena za kWh")});Spacer(Modifier.width(8.dp));FilterChip(selected=priceUnit=="m3",onClick={priceUnit="m3"},label={Text("Cena za m³")}) }
            NumberField("Konečná cena za ${if(priceUnit=="m3")"m³" else "kWh"} (€)",price){price=it}
            NumberField("Mesačná záloha (€)",advance){advance=it}
            if(energy==Energy.GAS && priceUnit=="kwh")NumberField("Prevod m³ na kWh podľa vyúčtovania",factor){factor=it}
            Button(enabled=!model.busy,onClick={val p=decimal(price);val a=decimal(advance);val f=if(energy==Energy.GAS && priceUnit=="kwh")decimal(factor) else BigDecimal.ONE;if(p==null||a==null||f==null||f.signum()<=0)model.message="Zadajte platné nezáporné sumy a kladný prevod plynu." else model.change({save(t.copy(price=p,advance=a,factor=f,priceUnit=priceUnit))}){model.message="Cena a záloha sú uložené."}},modifier=Modifier.fillMaxWidth()){Text("Uložiť")}
        }
        Section("Zaplatená záloha") {
            NumberField("Uhradená suma (€)",payment){payment=it}
            OutlinedButton(enabled=!model.busy,onClick={val amount=decimal(payment);if(amount==null||amount.signum()<=0)model.message="Zadajte kladnú uhradenú sumu." else model.change({save(Payment(energy=energy,date=LocalDate.now(),amount=amount))}){payment="";model.message="Platba bola uložená."}},modifier=Modifier.fillMaxWidth()){Text("Zapísať dnešnú platbu")}
            Text("Mesačný plán sa nepovažuje za zaplatenú platbu.",style=MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick=onAdvanced,modifier=Modifier.fillMaxWidth()){Text("Ďalšie možnosti")}
    }
}

@Composable
private fun InvoiceReview(model: AppModel,energy: Energy,onDone:()->Unit) {
    val draft=model.invoice
    val tariff=model.data.tariff(energy)
    var price by rememberSaveable(draft){mutableStateOf(draft?.prices?.singleOrNull()?.value ?: "")}
    var priceUnit by rememberSaveable(draft){mutableStateOf(draft?.prices?.singleOrNull()?.unit ?: if(energy==Energy.WATER)"m3" else "kwh")}
    var factor by rememberSaveable(draft){mutableStateOf(tariff.factor.toPlainString())}
    var advance by rememberSaveable(draft){mutableStateOf(draft?.advances?.singleOrNull()?.value ?: "")}
    var start by rememberSaveable(draft){mutableStateOf(draft?.periods?.singleOrNull()?.first?.toString() ?: "")}
    var end by rememberSaveable(draft){mutableStateOf(draft?.periods?.singleOrNull()?.second?.toString() ?: "")}
    var checked by rememberSaveable(draft){mutableStateOf(false)}
    PageColumn {
        if(draft==null && !model.busy)Text("Faktúru sa nepodarilo prečítať. Údaje môžete zadať v cenách ručne.")
        if(draft!=null) {
            model.proof?.let { Image(it.asImageBitmap(),"Faktúra na kontrolu",Modifier.fillMaxWidth().heightIn(max=280.dp)) }
            Text("Skontrolujte údaje podľa faktúry",style=MaterialTheme.typography.headlineSmall)
            draft.warnings.forEach { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error) }
            NumberField("Konečná cena za ${if(priceUnit=="m3")"m³" else "kWh"} (€)",price){price=it;checked=false}
            draft.prices.forEach { field -> TextButton(onClick={price=field.value;priceUnit=field.unit ?: priceUnit;checked=false}){Text("${field.value} · ${field.evidence}")};field.warning?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error) } }
            if(energy==Energy.GAS && priceUnit!="m3")NumberField("Prevod m³ na kWh podľa vyúčtovania",factor){factor=it;checked=false}
            NumberField("Mesačná záloha (€)",advance){advance=it;checked=false}
            draft.advances.forEach { field->TextButton(onClick={advance=field.value;checked=false}){Text("${field.value} € · ${field.evidence}")};field.warning?.let { Text(it,style=MaterialTheme.typography.bodySmall) } }
            DateField("Obdobie od",start){start=it;checked=false}
            DateField("Obdobie do",end){end=it;checked=false}
            if(draft.periods.size>1)draft.periods.forEach { period->TextButton(onClick={start=period.first.toString();end=period.second.toString();checked=false}){Text("${period.first} – ${period.second}")} }
            Text("Nevyplnené položky sa nezmenia. Cena sa použije na celé sledované obdobie. Záloha z faktúry je iba plán, nie doklad o úhrade.",style=MaterialTheme.typography.bodySmall)
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(checked=checked,onCheckedChange={checked=it});Text("Skontroloval/a som cenu vrátane DPH, jednotku, zálohu a obdobie.",Modifier.weight(1f))}
            Button(enabled=!model.busy && checked,onClick={
                val p=if(price.isBlank())tariff.price else decimal(price);val a=if(advance.isBlank())tariff.advance else decimal(advance)
                val f=if(energy==Energy.GAS && price.isNotBlank()) { if(priceUnit=="m3")BigDecimal.ONE else decimal(factor) } else tariff.factor
                val s=if(start.isBlank())tariff.start else start.date();val e=if(end.isBlank())tariff.end else end.date()
                if(p==null||a==null||f==null||f.signum()<=0||s==null||s.year !in 1900..2200 || (end.isNotBlank()&&e==null) || (e!=null&&(e<s||e.year>2200)))model.message="Skontrolujte sumy, prevod plynu a dátumy obdobia."
                else if(price.isBlank()&&advance.isBlank()&&start.isBlank()&&end.isBlank())model.message="Nenašli sa údaje na uloženie. Vyplňte ich ručne."
                else model.change({save(tariff.copy(price=p,advance=a,start=s,end=e,factor=f,priceUnit=if(price.isBlank())tariff.priceUnit else priceUnit))}){model.invoice=null;model.proof=null;onDone();model.message="Potvrdené údaje z faktúry boli uložené."}
            },modifier=Modifier.fillMaxWidth().heightIn(min=60.dp)){Text("Potvrdiť údaje z faktúry")}
        }
    }
}
