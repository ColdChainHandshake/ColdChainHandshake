package com.coldchain.handshake.data.remote

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.serializer.KotlinXSerializer
import kotlinx.serialization.json.Json

/**
 * Safe provider for the SupabaseClient instance.
 * Uses placeholder credentials by default so offline buffering and unit testing
 * work safely without requiring real cloud secrets.
 */
object SupabaseClientProvider {

    const val PLACEHOLDER_URL = "https://placeholder-coldchain.supabase.co"
    const val PLACEHOLDER_KEY = "placeholder-anon-key"

    @Volatile
    private var client: SupabaseClient? = null

    @Volatile
    private var currentUrl: String = PLACEHOLDER_URL

    @Volatile
    private var currentKey: String = PLACEHOLDER_KEY

    /**
     * Dynamically initialize with remote project credentials without hardcoding secrets.
     */
    fun initialize(url: String, key: String) {
        synchronized(this) {
            currentUrl = url
            currentKey = key
            client = null
        }
    }

    /**
     * Check if non-placeholder credentials have been configured.
     */
    fun isConfigured(): Boolean {
        return currentUrl != PLACEHOLDER_URL &&
                currentKey != PLACEHOLDER_KEY &&
                currentUrl.isNotBlank() &&
                currentKey.isNotBlank()
    }

    /**
     * Get or create the SupabaseClient singleton.
     */
    fun getClient(): SupabaseClient {
        return client ?: synchronized(this) {
            client ?: buildClient(currentUrl, currentKey).also { client = it }
        }
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun buildClient(url: String, key: String): SupabaseClient {
        return createSupabaseClient(
            supabaseUrl = url,
            supabaseKey = key
        ) {
            install(Postgrest) {
                serializer = KotlinXSerializer(Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                    explicitNulls = false
                })
            }
        }
    }


    /**
     * Inject custom client or mock for testing.
     */
    fun setClientForTesting(customClient: SupabaseClient?) {
        synchronized(this) {
            client = customClient
        }
    }

    /**
     * Reset to default placeholder state.
     */
    fun reset() {
        synchronized(this) {
            currentUrl = PLACEHOLDER_URL
            currentKey = PLACEHOLDER_KEY
            client = null
        }
    }
}
