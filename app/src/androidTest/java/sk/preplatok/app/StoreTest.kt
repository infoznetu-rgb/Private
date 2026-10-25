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
    @Test fun upgradeFromVersionOnePreservesReadingsPaymentsAndPrices() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="migration-${java.util.UUID.randomUUID()}.db"
        context.openOrCreateDatabase(name,0,null).use { db ->
            db.execSQL("CREATE TABLE readings(id TEXT PRIMARY KEY, energy TEXT NOT NULL, day TEXT NOT NULL, value TEXT NOT NULL, note TEXT NOT NULL, source TEXT NOT NULL, UNIQUE(energy,day))")
            db.execSQL("CREATE TABLE payments(id TEXT PRIMARY KEY, energy TEXT NOT NULL, day TEXT NOT NULL, amount TEXT NOT NULL)")
            db.execSQL("CREATE TABLE tariffs(energy TEXT PRIMARY KEY, price TEXT NOT NULL, fixed TEXT NOT NULL, advance TEXT NOT NULL, factor TEXT NOT NULL, start TEXT NOT NULL)")
            db.execSQL("INSERT INTO readings VALUES('r','WATER','2026-01-01','100.5','pôvodný','Ručne')")
            db.execSQL("INSERT INTO payments VALUES('p','WATER','2026-01-02','20')")
            db.execSQL("INSERT INTO tariffs VALUES('WATER','2','1','20','1','2026-01-01')")
            db.version=1
        }
        val store=Store(context,name)
        try {
            val s=store.snapshot();assertEquals("100.5",s.readings.single().value.toPlainString());assertEquals("20",s.payments.single().amount.toPlainString());assertNull(s.tariffs.single().end)
            store.save(s.tariffs.single().copy(end=LocalDate.of(2026,12,31)))
            val backup=store.export();store.restore(backup);assertEquals(LocalDate.of(2026,12,31),store.snapshot().tariffs.single().end)
        } finally { store.close();context.deleteDatabase(name) }
    }
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
