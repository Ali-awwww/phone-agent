package com.example.security

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

private const val TAG = "AppCheckInit"
private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"

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
            Log.d(TAG, "PlayIntegrityAppCheckProviderFactory installed.")
        }

        // Warm up the token before the first Gemini (Firebase AI Logic)
        // call, so a stale or missing token is refreshed early instead
        // of failing the first command.
        FirebaseAppCheck.getInstance().getToken(false)
            .addOnSuccessListener {
                Log.d(TAG, "App Check token ready.")
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "App Check token fetch failed, forcing a refresh.", error)
                FirebaseAppCheck.getInstance().getToken(true)
                    .addOnSuccessListener {
                        Log.d(TAG, "App Check token refreshed.")
                    }
                    .addOnFailureListener { refreshError ->
                        Log.e(TAG, "App Check token refresh failed.", refreshError)
                    }
            }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize Firebase App Check", e)
    }
}
