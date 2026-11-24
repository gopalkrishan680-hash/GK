package com.gk.pricehunt.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _enabledStores = MutableStateFlow(loadStores())
    val enabledStores: StateFlow<Set<Store>> = _enabledStores

    private fun loadStores(): Set<Store> {
        val saved = prefs.getStringSet("stores", null)
            ?: return Store.entries.filter { it.enabledByDefault }.toSet()
        return saved.mapNotNull { Store.byId(it) }.toSet()
    }

    fun setStoreEnabled(store: Store, enabled: Boolean) {
        val s = _enabledStores.value.toMutableSet().apply { if (enabled) add(store) else remove(store) }
        prefs.edit().putStringSet("stores", s.map { it.id }.toSet()).apply()
        _enabledStores.value = s
    }

    /** Background price-check interval in hours; 0 = off. */
    var checkIntervalHours: Int
        get() = prefs.getInt("interval", 6)
        set(v) = prefs.edit().putInt("interval", v).apply()

    /** Notify on every drop (not only when the target price is reached). */
    var alertOnAnyDrop: Boolean
        get() = prefs.getBoolean("anyDrop", true)
        set(v) = prefs.edit().putBoolean("anyDrop", v).apply()

    var hideSponsored: Boolean
        get() = prefs.getBoolean("hideSponsored", false)
        set(v) = prefs.edit().putBoolean("hideSponsored", v).apply()

    /** Temporarily hide the on-screen compare bubble without turning the service off. */
    var overlayPaused: Boolean
        get() = prefs.getBoolean("overlayPaused", false)
        set(v) = prefs.edit().putBoolean("overlayPaused", v).apply()

    var askedNotificationPermission: Boolean
        get() = prefs.getBoolean("askedNotif", false)
        set(v) = prefs.edit().putBoolean("askedNotif", v).apply()
}
