package com.example.security

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.AppCheckTokenResult
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "AppCheckInit"
private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"

// SharedPreferences flag marking that a Play Integrity token has been
// force-refreshed at least once after the switch from reCAPTCHA (v1.0.2).
// Firebase App Check caches tokens on the device: getToken(false) returns
// the cached token when one exists, even if it was issued by the previous
// provider. After the provider switch that cache can therefore keep
// serving a stale token that Firebase AI Logic rejects
// ("Firebase App Check token is invalid"), while the warm-up code only
// refreshed when getToken(false) *failed* -- so a stale cached token was
// never refreshed. Forcing one refresh after the switch discards it.
private const val APPCHECK_PREFS = "app_check"
private const val KEY_PLAY_INTEGRITY_TOKEN_REFRESHED = "play_integrity_token_refreshed_v1"

/**
 * Firebase App Check configuration.
 *
 * Release builds use Play Integrity: it attests the app from the
 * signing certificate whose SHA-1/SHA-256 fingerprints are registered
 * for this Firebase Android app, so no site key is needed. Play
 * Integrity is the provider Firebase enables by default for Android.
 *
 * Release builds previously used the reCAPTCHA provider with a
 * reCAPTCHA v3 site key that was never registered as a reCAPTCHA
 * Enterprise key for this app in Firebase App Check, so Firebase AI
 * Logic rejected its tokens ("Firebase App Check token is invalid").
 *
 * v1.0.2 switched the release provider to Play Integrity, but the
 * error persisted because (1) the warm-up only refreshed the token
 * when getToken(false) failed, so a stale token cached under the old
 * provider kept being sent, and (2) no Gemini call ever retried with
 * a freshly forced token. v1.0.3 force-refreshes the token once after
 * the provider switch and retries Gemini calls once with a forced
 * refresh when Firebase AI Logic rejects the token.
 *
 * Play Integrity still needs the release signing certificate
 * fingerprints (SHA-1 / SHA-256) registered for this Firebase Android
 * app in the Firebase console; without them attestation cannot pass
 * no matter what the client code does.
 *
 * Debug builds keep the Debug provider, with the debug token passed
 * via the launch Intent extra when present.
 */
fun configureAppCheck(context: Context, intent: Intent?) {
    try {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context)
        }
        if (BuildConfig.DEBUG) {
            val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)
            if (!debugToken.isNullOrEmpty()) {
                val persistenceKey = FirebaseApp.getInstance().persistenceKey
                val prefs = context.getSharedPreferences(
                    "$DEBUG_STORE_PREFIX$persistenceKey",
                    Context.MODE_PRIVATE
                )
                prefs.edit()
                    .putString(DEBUG_SECRET_KEY, debugToken)
                    .commit()
                Log.d(TAG, "App Check debug secret configured successfully.")
            } else {
                Log.w(TAG, "No debug token found in Intent extra.")
            }
            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
            Log.d(TAG, "DebugAppCheckProviderFactory installed.")
        } else {
            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                PlayIntegrityAppCheckProviderFactory.getInstance()
            )
            FirebaseAppCheck.getInstance().setTokenAutoRefreshEnabled(true)
            Log.d(TAG, "PlayIntegrityAppCheckProviderFactory installed.")
        }
        // Warm up the token before the first Gemini (Firebase AI Logic)
        // call, so a stale or missing token is refreshed early instead
        // of failing the first command.
        warmUpAppCheckToken(context)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize Firebase App Check", e)
    }
}

private fun warmUpAppCheckToken(context: Context) {
    val appCheck = FirebaseAppCheck.getInstance()
    // First run after the provider switch (release only): force a fresh
    // token so any token cached under the previous provider is discarded
    // instead of being reused by Firebase AI Logic.
    val prefs = context.getSharedPreferences(APPCHECK_PREFS, Context.MODE_PRIVATE)
    val forceRefresh = !BuildConfig.DEBUG &&
        !prefs.getBoolean(KEY_PLAY_INTEGRITY_TOKEN_REFRESHED, false)
    appCheck.getToken(forceRefresh)
        .addOnSuccessListener { token: AppCheckTokenResult ->
            if (token.token.isNotBlank()) {
                prefs.edit().putBoolean(KEY_PLAY_INTEGRITY_TOKEN_REFRESHED, true).apply()
                Log.d(TAG, "App Check token ready.")
            }
        }
        .addOnFailureListener { error ->
            Log.w(TAG, "App Check token fetch failed, forcing a refresh.", error)
            appCheck.getToken(true)
                .addOnSuccessListener { token: AppCheckTokenResult ->
                    if (token.token.isNotBlank()) {
                        prefs.edit().putBoolean(KEY_PLAY_INTEGRITY_TOKEN_REFRESHED, true).apply()
                        Log.d(TAG, "App Check token refreshed.")
                    }
                }
                .addOnFailureListener { refreshError ->
                    Log.e(TAG, "App Check token refresh failed.", refreshError)
                }
        }
}

/**
 * Returns true when [error] (or one of its causes) is the
 * "Firebase App Check token is invalid" rejection from Firebase AI Logic.
 */
fun isAppCheckTokenError(error: Throwable?): Boolean {
    var current = error
    while (current != null) {
        val message = current.message.orEmpty()
        if (message.contains("App Check", ignoreCase = true) ||
            message.contains("token is invalid", ignoreCase = true) ||
            message.contains("app-check", ignoreCase = true)
        ) {
            return true
        }
        current = current.cause
    }
    return false
}

/**
 * Forces a fresh App Check token from the installed provider and returns
 * true when a non-blank token was obtained. Used before retrying a Gemini
 * call whose token Firebase AI Logic rejected.
 */
suspend fun refreshAppCheckToken(): Boolean {
    return try {
        val token = FirebaseAppCheck.getInstance().getToken(true).awaitTask()
        val ok = token.token.isNotBlank()
        if (ok) Log.d(TAG, "App Check token force-refreshed.")
        ok
    } catch (e: Exception) {
        Log.e(TAG, "Forced App Check token refresh failed.", e)
        false
    }
}

/**
 * Makes sure a usable App Check token exists, forcing a refresh when the
 * cached token cannot be obtained. Returns false only when no token could
 * be fetched at all (e.g. Play Integrity attestation failed on the device).
 */
suspend fun ensureAppCheckToken(): Boolean {
    return try {
        FirebaseAppCheck.getInstance().getToken(false).awaitTask()
        true
    } catch (e: Exception) {
        Log.w(TAG, "Cached App Check token unavailable, forcing refresh.", e)
        refreshAppCheckToken()
    }
}

private suspend fun <T> Task<T>.awaitTask(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result ->
            if (continuation.isActive) continuation.resume(result)
        }.addOnFailureListener { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }.addOnCanceledListener {
            if (continuation.isActive) continuation.cancel()
        }
    }
