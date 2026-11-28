package com.example.security

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * تفضيلات وضع الخصوصية (Privacy Mode):
 * تحفظ حالة وضع الخصوصية في SharedPreferences خاصة ومشفرة بحساب التطبيق (Context.MODE_PRIVATE)،
 * وتتيح مراقبة الحالة عبر StateFlow ليطبَّق فوراً على كافة مسارات التطبيق:
 * المحادثة الرئيسية، المحادثة المصغّرة، محرك اللعب، قراءة الشاشة، وسجلات الإجراءات.
 */
class PrivacyPreferences(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val _privacyModeEnabled = MutableStateFlow(prefs.getBoolean(KEY_PRIVACY_MODE, false))
    val privacyModeEnabled: StateFlow<Boolean> = _privacyModeEnabled.asStateFlow()

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_PRIVACY_MODE) {
            _privacyModeEnabled.value = prefs.getBoolean(KEY_PRIVACY_MODE, false)
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    fun isPrivacyModeEnabled(): Boolean = prefs.getBoolean(KEY_PRIVACY_MODE, false)

    fun setPrivacyModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PRIVACY_MODE, enabled).commit()
        _privacyModeEnabled.value = enabled
    }

    companion object {
        const val PREFS_NAME = "wakeel_privacy_settings"
        const val KEY_PRIVACY_MODE = "privacy_mode_enabled"
    }
}
