package sk.preplatok.app

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

open class BaseUiTest {
    @get:Rule val compose=createComposeRule()
    protected val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val db="ui-${java.util.UUID.randomUUID()}.db"
    private val models=ViewModelStore()
    protected lateinit var model: AppModel
    protected open val destination="home"
    protected open fun prepare() = Unit
    @Before fun setup() {
        compose.runOnUiThread { model=AppModel(context.applicationContext as Application,db);models.put("test",model) }
        compose.runOnUiThread { prepare() }
        compose.setContent { MaterialTheme { SimpleEnergyApp(model,destination) } }
        compose.waitUntil(10000) { model.ready }
    }
    @After fun cleanup() { compose.runOnUiThread { models.clear() };context.deleteDatabase(db) }
}
class SimpleUiTest: BaseUiTest() {
    @Test fun homeShowsThreeEnergiesAndOneLargeCameraAction() {
        listOf("Elektrina","Voda","Plyn","Odfotiť merač","Zadať ručne").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("Ďalšie možnosti").assertDoesNotExist()
        val folder=context.getExternalFilesDir("test-evidence")!!.apply { mkdirs() }
        File(folder,"home.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        // Gradle uninstalls the test app after the suite, so preserve visual evidence outside app data first.
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        val command=automation.executeShellCommand("cp ${File(folder,"home.png").absolutePath} /data/local/tmp/preplatok-home.png")
        android.os.ParcelFileDescriptor.AutoCloseInputStream(command).use { it.readBytes() }
        val listing=automation.executeShellCommand("ls /data/local/tmp/preplatok-home.png")
        val result=android.os.ParcelFileDescriptor.AutoCloseInputStream(listing).bufferedReader().use { it.readText().trim() }
        org.junit.Assert.assertEquals("/data/local/tmp/preplatok-home.png",result)
    }
    @Test fun invalidManualInputCannotCreateAReading() {
        compose.onNodeWithText("Zadať ručne").performClick()
        compose.onNodeWithText("Stav merača (kWh)").performTextInput("12O45")
        compose.onNodeWithText("Potvrdiť odpočet").performClick()
        compose.onNodeWithText("Zadajte číslo z merača a platný dátum.").assertExists()
        org.junit.Assert.assertTrue(model.data.readings.isEmpty())
    }
}
class InvoiceUiTest: BaseUiTest() {
    override val destination="invoice"
    override fun prepare() { model.invoice=InvoiceParser.parse("Cena 0,25 EUR/kWh\nMesačná záloha 50 EUR",Energy.ELECTRICITY) }
    @Test fun invoiceRequiresReviewBeforeItChangesPrices() {
        compose.onNodeWithText("Potvrdiť údaje z faktúry").performScrollTo().assertIsNotEnabled()
        org.junit.Assert.assertTrue(model.data.tariffs.isEmpty())
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("Potvrdiť údaje z faktúry").performScrollTo().performClick()
        compose.waitUntil(10000){model.data.tariffs.isNotEmpty()}
        org.junit.Assert.assertEquals(0,model.data.tariffs.single().price.compareTo("0.25".toBigDecimal()))
        org.junit.Assert.assertTrue(model.data.payments.isEmpty())
    }
}
class ReadingReviewUiTest: BaseUiTest() {
    override val destination="review"
    override fun prepare() { model.scan=Scan(android.graphics.Bitmap.createBitmap(200,80,android.graphics.Bitmap.Config.ARGB_8888),listOf(Candidate("12345",android.graphics.Rect(0,0,200,80)))) }
    @Test fun recognizedValueIsSavedOnlyAfterConfirm() {
        compose.onNodeWithText("12345").assertIsDisplayed()
        org.junit.Assert.assertTrue(model.data.readings.isEmpty())
        compose.onNodeWithText("Potvrdiť").performScrollTo().performClick()
        compose.waitUntil(10000){model.data.readings.size==1}
        org.junit.Assert.assertEquals("12345",model.data.readings.single().value.toPlainString())
    }
    @Test fun conflictingUnitBlocksConfirmAndAllowsCorrection() {
        compose.runOnIdle { model.scan=model.scan!!.copy(candidates=listOf(model.scan!!.candidates.single().copy(blocked=true,warnings=listOf("Nesprávna jednotka")))) }
        compose.onNodeWithText("Potvrdiť").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Opraviť").performScrollTo().performClick()
        compose.onNodeWithText("Stav merača (kWh)").assertExists()
        org.junit.Assert.assertTrue(model.data.readings.isEmpty())
    }
}
