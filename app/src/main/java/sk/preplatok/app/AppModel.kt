package sk.preplatok.app

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppModel(application: Application) : AndroidViewModel(application) {
    private val store = Store(application)
    var data by mutableStateOf(Snapshot(emptyList(), emptyList(), emptyList())); private set
    var ready by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    var scan by mutableStateOf<Scan?>(null)
    var proof by mutableStateOf<android.graphics.Bitmap?>(null)
    private var pendingRestore: String? = null
    var restoreCount by mutableStateOf<String?>(null); private set
    init { viewModelScope.launch { data = withContext(Dispatchers.IO) { store.snapshot() }; ready = true } }
    fun change(action: Store.() -> Unit, done: () -> Unit = {}) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { data = withContext(Dispatchers.IO) { store.action(); store.snapshot() }; done() }
            catch (e: Exception) { message = "Zmenu sa nepodarilo uložiť: ${e.localizedMessage}" }
            finally { busy = false }
        }
    }
    fun recognize(uri: Uri) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { scan = MeterOcr.recognize(MeterOcr.load(getApplication(), uri)) }
            catch (e: Exception) { message = "Fotografiu sa nepodarilo rozpoznať. Skúste inú fotku alebo ručný odpočet. ${e.localizedMessage ?: ""}" }
            finally { busy = false }
        }
    }
    fun recognizeCrop(left: Float, top: Float, right: Float, bottom: Float) {
        val bitmap = scan?.bitmap ?: return
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { scan = MeterOcr.recognize(withContext(Dispatchers.Default) { MeterOcr.crop(bitmap, left, top, right, bottom) }) }
            catch (e: Exception) { message = "Výrez sa nepodarilo rozpoznať. Zadajte odpočet ručne." }
            finally { busy = false }
        }
    }
    fun export(uri: Uri) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { val text = store.export(); getApplication<Application>().contentResolver.openOutputStream(uri, "wt").use { requireNotNull(it).write(text.toByteArray(Charsets.UTF_8)) } }; message = "Záloha bola uložená. Obsahuje odpočty, platby a nastavenia." }
            catch (e: Exception) { message = "Zálohu sa nepodarilo uložiť." }
            finally { busy = false }
        }
    }
    fun inspectBackup(uri: Uri) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                val pair = withContext(Dispatchers.IO) {
                    val text = getApplication<Application>().contentResolver.openInputStream(uri).use { input ->
                        val stream = requireNotNull(input)
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 5_000_000) { "Záloha je príliš veľká." }
                            output.write(buffer, 0, count)
                        }
                        String(output.toByteArray(), Charsets.UTF_8)
                    }
                    text to Backup.parse(text)
                }
                pendingRestore = pair.first
                restoreCount = "${pair.second.readings.size} odpočtov a ${pair.second.payments.size} platieb"
            } catch (e: Exception) { message = "Záloha je neplatná alebo nepodporovaná. Existujúce údaje zostali zachované." }
            finally { busy = false }
        }
    }
    fun cancelRestore() { pendingRestore = null; restoreCount = null }
    fun confirmRestore() {
        val text = pendingRestore ?: return
        change({ restore(text) }) { cancelRestore(); message = "Údaje boli obnovené zo zálohy." }
    }
    override fun onCleared() { store.close() }
}
