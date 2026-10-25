package sk.preplatok.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

class Store(context: Context, databaseName: String = "energy.db") : SQLiteOpenHelper(context, databaseName, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE readings(id TEXT PRIMARY KEY, energy TEXT NOT NULL, day TEXT NOT NULL, value TEXT NOT NULL, note TEXT NOT NULL, source TEXT NOT NULL, UNIQUE(energy,day))")
        db.execSQL("CREATE TABLE payments(id TEXT PRIMARY KEY, energy TEXT NOT NULL, day TEXT NOT NULL, amount TEXT NOT NULL)")
        db.execSQL("CREATE TABLE tariffs(energy TEXT PRIMARY KEY, price TEXT NOT NULL, fixed TEXT NOT NULL, advance TEXT NOT NULL, factor TEXT NOT NULL, start TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun snapshot(): Snapshot {
        val readings = mutableListOf<Reading>(); val payments = mutableListOf<Payment>(); val tariffs = mutableListOf<Tariff>()
        readableDatabase.rawQuery("SELECT * FROM readings ORDER BY day", null).use { c -> while (c.moveToNext()) readings += Reading(c.getString(0), Energy.valueOf(c.getString(1)), LocalDate.parse(c.getString(2)), c.getString(3).toBigDecimal(), c.getString(4), c.getString(5)) }
        readableDatabase.rawQuery("SELECT * FROM payments ORDER BY day", null).use { c -> while (c.moveToNext()) payments += Payment(c.getString(0), Energy.valueOf(c.getString(1)), LocalDate.parse(c.getString(2)), c.getString(3).toBigDecimal()) }
        readableDatabase.rawQuery("SELECT * FROM tariffs", null).use { c -> while (c.moveToNext()) tariffs += Tariff(Energy.valueOf(c.getString(0)), c.getString(1).toBigDecimal(), c.getString(2).toBigDecimal(), c.getString(3).toBigDecimal(), c.getString(4).toBigDecimal(), LocalDate.parse(c.getString(5))) }
        return Snapshot(readings, payments, tariffs)
    }
    fun save(r: Reading) { writableDatabase.insertWithOnConflict("readings", null, ContentValues().apply { put("id", r.id); put("energy", r.energy.name); put("day", r.date.toString()); put("value", r.value.toPlainString()); put("note", r.note); put("source", r.source) }, SQLiteDatabase.CONFLICT_REPLACE) }
    fun save(p: Payment) { writableDatabase.insertOrThrow("payments", null, ContentValues().apply { put("id", p.id); put("energy", p.energy.name); put("day", p.date.toString()); put("amount", p.amount.toPlainString()) }) }
    fun save(t: Tariff) { writableDatabase.insertWithOnConflict("tariffs", null, ContentValues().apply { put("energy", t.energy.name); put("price", t.price.toPlainString()); put("fixed", t.fixed.toPlainString()); put("advance", t.advance.toPlainString()); put("factor", t.factor.toPlainString()); put("start", t.start.toString()) }, SQLiteDatabase.CONFLICT_REPLACE) }
    fun deleteReading(id: String) { writableDatabase.delete("readings", "id=?", arrayOf(id)) }
    fun deletePayment(id: String) { writableDatabase.delete("payments", "id=?", arrayOf(id)) }
    fun export(): String {
        val s = snapshot()
        return JSONObject().put("format", "sk.preplatok.backup").put("version", 1)
            .put("readings", JSONArray().apply { s.readings.forEach { r -> put(JSONObject().put("id", r.id).put("energy", r.energy.name).put("date", r.date.toString()).put("value", r.value.toPlainString()).put("note", r.note).put("source", r.source)) } })
            .put("payments", JSONArray().apply { s.payments.forEach { p -> put(JSONObject().put("id", p.id).put("energy", p.energy.name).put("date", p.date.toString()).put("amount", p.amount.toPlainString())) } })
            .put("tariffs", JSONArray().apply { s.tariffs.forEach { t -> put(JSONObject().put("energy", t.energy.name).put("price", t.price.toPlainString()).put("fixed", t.fixed.toPlainString()).put("advance", t.advance.toPlainString()).put("factor", t.factor.toPlainString()).put("start", t.start.toString())) } }).toString(2)
    }
    fun restore(text: String) {
        val s = Backup.parse(text)
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("readings", null, null); db.delete("payments", null, null); db.delete("tariffs", null, null)
            s.readings.forEach(::save); s.payments.forEach(::save); s.tariffs.forEach(::save)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
object Backup {
    fun parse(text: String): Snapshot {
        require(text.length <= 5_000_000) { "Záloha je príliš veľká (max. 5 MB)." }
        val root = JSONObject(text)
        require(root.getString("format") == "sk.preplatok.backup" && root.getInt("version") == 1) { "Nepodporovaný formát zálohy." }
        fun objects(key: String): List<JSONObject> { val a = root.getJSONArray(key); require(a.length() <= 20000); return (0 until a.length()).map(a::getJSONObject) }
        fun number(o: JSONObject, key: String) = requireNotNull(decimal(o.getString(key))) { "Neplatné číslo v zálohe." }
        val rs = objects("readings").map { Reading(it.getString("id"), Energy.valueOf(it.getString("energy")), LocalDate.parse(it.getString("date")), number(it, "value"), it.getString("note").take(500), it.getString("source").take(80)) }
        val ps = objects("payments").map { Payment(it.getString("id"), Energy.valueOf(it.getString("energy")), LocalDate.parse(it.getString("date")), number(it, "amount")) }
        val ts = objects("tariffs").map { Tariff(Energy.valueOf(it.getString("energy")), number(it, "price"), number(it, "fixed"), number(it, "advance"), number(it, "factor"), LocalDate.parse(it.getString("start"))) }
        require(rs.map { it.id }.distinct().size == rs.size && ps.map { it.id }.distinct().size == ps.size && ts.map { it.energy }.distinct().size == ts.size) { "Duplicitné záznamy v zálohe." }
        rs.forEach { r -> require(r.id.length in 1..80 && r.date.year in 1900..LocalDate.now().year && r.date <= LocalDate.now()) }
        rs.groupBy { it.energy }.values.forEach { readings ->
            readings.sortedBy { it.date }.zipWithNext().forEach { (a,b) -> require(a.date < b.date && a.value <= b.value) { "Neplatné poradie odpočtov v zálohe." } }
        }
        ps.forEach { require(it.amount.signum() > 0 && it.date <= LocalDate.now() && it.date.year >= 1900 && it.id.length in 1..80) }
        ts.forEach { require(it.factor.signum() > 0 && it.start.year in 1900..2200 && (it.energy == Energy.GAS || it.factor.compareTo(java.math.BigDecimal.ONE) == 0)) }
        return Snapshot(rs, ps, ts)
    }
}
