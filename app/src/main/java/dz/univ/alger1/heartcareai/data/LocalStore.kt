package dz.univ.alger1.heartcareai.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Base64
import android.util.Patterns
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Stockage de démonstration : SQLite sur un seul appareil, sans serveur.
 * Les mots de passe sont dérivés avec PBKDF2 (sel aléatoire par compte).
 * Les données médicales ne sont PAS chiffrées au repos : ne pas utiliser
 * avec de vraies données de patients ni diffuser comme service de santé.
 */
class LocalStore(private val context: Context) : SQLiteOpenHelper(context, "heartcare_local.db", null, 1) {
    private val session = context.getSharedPreferences("heartcare_session", Context.MODE_PRIVATE)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE users (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              name TEXT NOT NULL,
              email TEXT NOT NULL UNIQUE COLLATE NOCASE,
              password_hash TEXT NOT NULL,
              salt TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE assessments (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              user_id INTEGER NOT NULL,
              payload TEXT NOT NULL,
              score REAL,
              source TEXT NOT NULL,
              created_at INTEGER NOT NULL,
              FOREIGN KEY(user_id) REFERENCES users(id)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_assessment_owner ON assessments(user_id, created_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Prévoir de vraies migrations lors d'une évolution du schéma.
        if (oldVersion != newVersion) throw IllegalStateException("Migration SQL nécessaire")
    }

    fun createUser(name: String, email: String, password: String): AppUser {
        val cleanName = name.trim()
        val cleanEmail = email.trim().lowercase()
        require(cleanName.length >= 2) { "Nom : au moins deux caractères." }
        require(Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()) { "Adresse e-mail invalide." }
        require(password.length >= 8) { "Mot de passe : au moins huit caractères." }
        val saltBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cv = ContentValues().apply {
            put("name", cleanName)
            put("email", cleanEmail)
            put("salt", Base64.encodeToString(saltBytes, Base64.NO_WRAP))
            put("password_hash", hash(password, saltBytes))
        }
        val id = writableDatabase.insertWithOnConflict(
            "users", null, cv, SQLiteDatabase.CONFLICT_IGNORE
        )
        require(id != -1L) { "Cet e-mail est déjà enregistré." }
        session.edit().putLong("current_user", id).apply()
        return AppUser(id, cleanName, cleanEmail)
    }

    fun login(email: String, password: String): AppUser {
        val cleanEmail = email.trim().lowercase()
        val cursor = readableDatabase.query(
            "users", arrayOf("id", "name", "email", "salt", "password_hash"),
            "email = ?", arrayOf(cleanEmail), null, null, null
        )
        cursor.use {
            require(it.moveToFirst()) { "E-mail ou mot de passe incorrect." }
            val salt = Base64.decode(it.getString(it.getColumnIndexOrThrow("salt")), Base64.NO_WRAP)
            val candidate = hash(password, salt)
            val stored = it.getString(it.getColumnIndexOrThrow("password_hash"))
            require(MessageDigest.isEqual(candidate.toByteArray(), stored.toByteArray())) {
                "E-mail ou mot de passe incorrect."
            }
            val user = AppUser(
                it.getLong(it.getColumnIndexOrThrow("id")),
                it.getString(it.getColumnIndexOrThrow("name")),
                it.getString(it.getColumnIndexOrThrow("email"))
            )
            session.edit().putLong("current_user", user.id).apply()
            return user
        }
    }

    fun currentUser(): AppUser? {
        val id = session.getLong("current_user", -1)
        return if (id <= 0) null else getUser(id)
    }

    fun getUser(id: Long): AppUser? {
        readableDatabase.query("users", arrayOf("id", "name", "email"),
            "id = ?", arrayOf(id.toString()), null, null, null).use {
            return if (it.moveToFirst()) AppUser(it.getLong(0), it.getString(1), it.getString(2)) else null
        }
    }

    fun updateName(userId: Long, name: String): AppUser {
        val trimmed = name.trim()
        require(trimmed.length >= 2) { "Nom trop court." }
        val values = ContentValues().apply { put("name", trimmed) }
        require(writableDatabase.update("users", values, "id = ?", arrayOf(userId.toString())) == 1) {
            "Compte introuvable."
        }
        return requireNotNull(getUser(userId))
    }

    fun logout() { session.edit().remove("current_user").apply() }

    fun deleteAccount(userId: Long) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("assessments", "user_id = ?", arrayOf(userId.toString()))
            writableDatabase.delete("users", "id = ?", arrayOf(userId.toString()))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
        logout()
    }

    fun saveAssessment(userId: Long, data: HeartInputs, score: Double?, source: String, editId: Long? = null): Long {
        require(getUser(userId) != null) { "Compte introuvable." }
        val values = ContentValues().apply {
            put("payload", data.toJson())
            if (score == null) putNull("score") else put("score", score)
            put("source", source)
        }
        return if (editId == null) {
            values.put("user_id", userId)
            values.put("created_at", System.currentTimeMillis())
            val id = writableDatabase.insert("assessments", null, values)
            require(id > 0) { "Impossible d'enregistrer l'évaluation." }
            id
        } else {
            val count = writableDatabase.update("assessments", values,
                "id = ? AND user_id = ?", arrayOf(editId.toString(), userId.toString()))
            require(count == 1) { "Évaluation introuvable." }
            editId
        }
    }

    fun listAssessments(userId: Long): List<Assessment> {
        val result = mutableListOf<Assessment>()
        readableDatabase.query("assessments", null, "user_id = ?", arrayOf(userId.toString()),
            null, null, "created_at DESC, id DESC").use { c ->
            while (c.moveToNext()) result.add(mapAssessment(c))
        }
        return result
    }

    fun getAssessment(userId: Long, id: Long): Assessment? {
        readableDatabase.query("assessments", null, "user_id = ? AND id = ?",
            arrayOf(userId.toString(), id.toString()), null, null, null).use { c ->
            return if (c.moveToFirst()) mapAssessment(c) else null
        }
    }

    fun deleteAssessment(userId: Long, id: Long): Boolean =
        writableDatabase.delete("assessments", "id = ? AND user_id = ?",
            arrayOf(id.toString(), userId.toString())) == 1

    private fun mapAssessment(c: Cursor): Assessment {
        val scoreIdx = c.getColumnIndexOrThrow("score")
        return Assessment(
            id = c.getLong(c.getColumnIndexOrThrow("id")),
            userId = c.getLong(c.getColumnIndexOrThrow("user_id")),
            input = HeartInputs.fromJson(c.getString(c.getColumnIndexOrThrow("payload"))),
            score = if (c.isNull(scoreIdx)) null else c.getDouble(scoreIdx),
            source = c.getString(c.getColumnIndexOrThrow("source")),
            createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"))
        )
    }

    private fun hash(password: String, salt: ByteArray): String {
        val spec = PBEKeySpec(password.toCharArray(), salt, 150_000, 256)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } finally { spec.clearPassword() }
    }
}
