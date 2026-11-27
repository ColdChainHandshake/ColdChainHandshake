package com.coldchain.handshake.data.local

import android.content.Context
import androidx.room.Room

/**
 * Thread-safe provider for the ColdChainDatabase singleton.
 * Avoids heavyweight DI frameworks while ensuring a single database instance.
 */
object DatabaseProvider {

    private const val DATABASE_NAME = "coldchain_handshake.db"

    @Volatile
    private var instance: ColdChainDatabase? = null

    /**
     * Returns the application-wide ColdChainDatabase singleton.
     */
    fun getDatabase(context: Context): ColdChainDatabase {
        return instance ?: synchronized(this) {
            instance ?: buildDatabase(context.applicationContext).also { instance = it }
        }
    }

    internal fun buildDatabase(context: Context, name: String = DATABASE_NAME): ColdChainDatabase {
        return Room.databaseBuilder(
            context,
            ColdChainDatabase::class.java,
            name
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()
    }

    /**
     * Closes the singleton, because a test environment can outlive the connection behind it.
     */
    internal fun closeForTesting() {
        synchronized(this) {
            instance?.close()
            instance = null
        }
    }

    /**
     * Factory for an isolated in-memory database instance, useful for tests.
     */
    fun createInMemoryDatabase(context: Context): ColdChainDatabase {
        return Room.inMemoryDatabaseBuilder(
            context,
            ColdChainDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()
    }
}
