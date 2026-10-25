package sk.preplatok.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class StoreTest {
    @Test fun backupRoundTripAndInvalidBackupLeavesDataIntact() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "test-${java.util.UUID.randomUUID()}.db"
        val store = Store(context, databaseName)
        try {
            val r = Reading(energy = Energy.WATER,date = LocalDate.of(2026,1,1),value = BigDecimal("00123.456"),note = "Kúpeľňa")
            store.save(r)
            store.save(Tariff(Energy.WATER,price = BigDecimal("3.125")))
            store.save(Payment(energy = Energy.WATER,date = LocalDate.of(2026,1,2),amount = BigDecimal("25")))
            val backup = store.export()
            store.deleteReading(r.id)
            store.restore(backup)
            assertEquals(r,store.snapshot().readings.single())
            assertEquals(1,store.snapshot().payments.size)
            val before = store.export()
            try { store.restore(backup.replace("123.456","-123.456")); fail("Invalid value accepted") } catch (_: IllegalArgumentException) { }
            assertEquals(before,store.export())
        } finally { store.close(); context.deleteDatabase(databaseName) }
    }
}
