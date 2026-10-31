package com.coldchain.handshake.data.local

import android.content.Context
import com.coldchain.handshake.data.remote.ProfileCache
import com.coldchain.handshake.repository.MemberRole
import com.coldchain.handshake.repository.UserProfile

class SharedPreferencesProfileCache(context: Context) : ProfileCache {

    private val prefs = context.applicationContext.getSharedPreferences("coldchain_profile", Context.MODE_PRIVATE)

    override fun load(userId: String): UserProfile? {
        if (prefs.getString(KEY_USER, null) != userId) return null
        val name = prefs.getString(KEY_NAME, null) ?: return null
        val role = prefs.getString(KEY_ROLE, null) ?: return null
        return UserProfile(name, MemberRole.valueOf(role))
    }

    override fun save(userId: String, profile: UserProfile) {
        prefs.edit()
            .putString(KEY_USER, userId)
            .putString(KEY_NAME, profile.displayName)
            .putString(KEY_ROLE, profile.role.name)
            .apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_USER = "user_id"
        const val KEY_NAME = "display_name"
        const val KEY_ROLE = "role"
    }
}
