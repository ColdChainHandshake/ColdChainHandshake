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

    private fun buildDatabase(context: Context): ColdChainDatabase {
        return Room.databaseBuilder(
            context,
            ColdChainDatabase::class.java,
            DATABASE_NAME
        )
            .fallbackToDestructiveMigration()
            .build()
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
