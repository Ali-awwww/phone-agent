package com.example.security

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * تفضيلات الذاكرة الدائمة والعادات والروتين الصباحي (المرحلة 6):
 * تُحفظ في SharedPreferences محلية خاصة بالتطبيق (Context.MODE_PRIVATE)
 * ومستبعدة بالكامل من النسخ الاحتياطي السحابي أو النقل بين الأجهزة (backup_rules & data_extraction_rules).
 */
class MemoryAndHabitPreferences(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val _morningRoutineApproved = MutableStateFlow(
        prefs.getBoolean(KEY_MORNING_ROUTINE_APPROVED, false)
    )
    val morningRoutineApproved: StateFlow<Boolean> = _morningRoutineApproved.asStateFlow()

    private val _predictiveLearningEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_PREDICTIVE_LEARNING_ENABLED, false)
    )
    val predictiveLearningEnabled: StateFlow<Boolean> = _predictiveLearningEnabled.asStateFlow()

    private val _anonymizedHabitGeminiConsent = MutableStateFlow(
        prefs.getBoolean(KEY_ANONYMIZED_HABIT_GEMINI_CONSENT, false)
    )
    val anonymizedHabitGeminiConsent: StateFlow<Boolean> = _anonymizedHabitGeminiConsent.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            KEY_MORNING_ROUTINE_APPROVED ->
                _morningRoutineApproved.value = prefs.getBoolean(KEY_MORNING_ROUTINE_APPROVED, false)
            KEY_PREDICTIVE_LEARNING_ENABLED ->
                _predictiveLearningEnabled.value = prefs.getBoolean(KEY_PREDICTIVE_LEARNING_ENABLED, false)
            KEY_ANONYMIZED_HABIT_GEMINI_CONSENT ->
                _anonymizedHabitGeminiConsent.value = prefs.getBoolean(KEY_ANONYMIZED_HABIT_GEMINI_CONSENT, false)
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun isMorningRoutineApproved(): Boolean =
        prefs.getBoolean(KEY_MORNING_ROUTINE_APPROVED, false)

    fun setMorningRoutineApproved(approved: Boolean) {
        prefs.edit().putBoolean(KEY_MORNING_ROUTINE_APPROVED, approved).commit()
        _morningRoutineApproved.value = approved
    }

    fun isPredictiveLearningEnabled(): Boolean =
        prefs.getBoolean(KEY_PREDICTIVE_LEARNING_ENABLED, false)

    fun setPredictiveLearningEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PREDICTIVE_LEARNING_ENABLED, enabled).commit()
        _predictiveLearningEnabled.value = enabled
        if (!enabled && isAnonymizedHabitGeminiConsent()) {
            setAnonymizedHabitGeminiConsent(false)
        }
    }

    fun isAnonymizedHabitGeminiConsent(): Boolean =
        prefs.getBoolean(KEY_ANONYMIZED_HABIT_GEMINI_CONSENT, false)

    fun setAnonymizedHabitGeminiConsent(consented: Boolean) {
        prefs.edit().putBoolean(KEY_ANONYMIZED_HABIT_GEMINI_CONSENT, consented).commit()
        _anonymizedHabitGeminiConsent.value = consented
    }

    fun getTurnsSinceLastSummary(): Int =
        prefs.getInt(KEY_TURNS_SINCE_LAST_SUMMARY, 0)

    fun setTurnsSinceLastSummary(count: Int) {
        prefs.edit().putInt(KEY_TURNS_SINCE_LAST_SUMMARY, count.coerceAtLeast(0)).commit()
    }

    fun incrementTurnsSinceLastSummary(): Int {
        val next = getTurnsSinceLastSummary() + 1
        setTurnsSinceLastSummary(next)
        return next
    }

    fun getLastSyncedActionLogTimestamp(): Long =
        prefs.getLong(KEY_LAST_SYNCED_ACTION_LOG_TS, 0L)

    fun setLastSyncedActionLogTimestamp(timestamp: Long) {
        prefs.edit().putLong(KEY_LAST_SYNCED_ACTION_LOG_TS, timestamp).commit()
    }

    fun getLastSyncedUsageTimestamp(): Long =
        prefs.getLong(KEY_LAST_SYNCED_USAGE_TS, 0L)

    fun setLastSyncedUsageTimestamp(timestamp: Long) {
        prefs.edit().putLong(KEY_LAST_SYNCED_USAGE_TS, timestamp).commit()
    }

    fun getDismissedSuggestionKeys(): Set<String> =
        prefs.getStringSet(KEY_DISMISSED_SUGGESTIONS, emptySet())?.toSet() ?: emptySet()

    fun addDismissedSuggestionKey(key: String) {
        val current = getDismissedSuggestionKeys().toMutableSet()
        current.add(key)
        prefs.edit().putStringSet(KEY_DISMISSED_SUGGESTIONS, current).commit()
    }

    fun clearDismissedSuggestionKeys() {
        prefs.edit().remove(KEY_DISMISSED_SUGGESTIONS).commit()
    }

    companion object {
        const val PREFS_NAME = "wakeel_memory_habit_settings"
        const val KEY_MORNING_ROUTINE_APPROVED = "morning_routine_approved"
        const val KEY_PREDICTIVE_LEARNING_ENABLED = "predictive_learning_enabled"
        const val KEY_ANONYMIZED_HABIT_GEMINI_CONSENT = "anonymized_habit_gemini_consent"
        const val KEY_TURNS_SINCE_LAST_SUMMARY = "turns_since_last_summary"
        const val KEY_LAST_SYNCED_ACTION_LOG_TS = "last_synced_action_log_ts"
        const val KEY_LAST_SYNCED_USAGE_TS = "last_synced_usage_ts"
        const val KEY_DISMISSED_SUGGESTIONS = "dismissed_suggestion_keys"
    }
}
