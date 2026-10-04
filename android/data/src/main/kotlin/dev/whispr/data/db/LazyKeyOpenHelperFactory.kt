package dev.whispr.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Defers loading SQLCipher and unwrapping the passphrase (a Keystore
 * operation) until the database is first opened. Room builds its open helper
 * eagerly, often on the main thread via dependency injection, but only opens
 * the database on its query executors.
 */
class LazyKeyOpenHelperFactory(private val passphrase: () -> ByteArray) : SupportSQLiteOpenHelper.Factory {

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        object : SupportSQLiteOpenHelper {
            @Volatile private var walEnabled: Boolean? = null

            private val delegate: SupportSQLiteOpenHelper by lazy {
                System.loadLibrary("sqlcipher")
                SupportOpenHelperFactory(passphrase()).create(configuration).also { helper ->
                    walEnabled?.let(helper::setWriteAheadLoggingEnabled)
                }
            }

            override val databaseName: String? get() = configuration.name

            override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
                walEnabled = enabled
            }

            override val writableDatabase: SupportSQLiteDatabase get() = delegate.writableDatabase

            override val readableDatabase: SupportSQLiteDatabase get() = delegate.readableDatabase

            override fun close() = delegate.close()
        }
}
