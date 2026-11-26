package com.example.security

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "AppCheckInit"
private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"

internal const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
internal const val DEBUG_STORE_NAME = "com.google.firebase.appcheck.debug.store"
internal const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"

internal const val APP_CHECK_STORE_PREFIX = "com.google.firebase.appcheck.store."
internal const val APP_CHECK_STORE_NAME = "com.google.firebase.appcheck.store"
internal const val APP_CHECK_PREFS_NAME = "app_check"
internal const val BACKUP_DEBUG_PREFS_NAME = "phone_agent_debug_identity"

const val MAX_APP_CHECK_ATTEMPTS = 3
const val DEFAULT_APP_CHECK_RETRY_DELAY_MS = 500L
private const val TOKEN_FETCH_TIMEOUT_MS = 20_000L

const val ERROR_APP_CHECK_EXHAUSTED_AR =
    "تعذر التحقق من أمان التطبيق عبر Firebase App Check بعد 3 محاولات لمسح الرمز وتحديثه. " +
        "يرجى التأكد من الاتصال بالإنترنت وتحديث خدمات Google Play ومن تسجيل بصمات التطبيق في Firebase، ثم إعادة المحاولة."

private val appContextRef = AtomicReference<Context?>(null)
private val lastInitErrorRef = AtomicReference<Throwable?>(null)
private val isProviderConfigured = AtomicBoolean(false)
private val cachedDebugSecretRef = AtomicReference<String?>(null)

enum class DisconnectionCategory(val titleAr: String) {
    APP_CHECK_INVALID("رفض رمز App Check"),
    MODEL_BUSY("خوادم النموذج مشغولة مؤقتاً"),
    NETWORK_ERROR("خطأ في الشبكة والاتصال"),
    MODEL_ERROR("خطأ في استجابة النموذج"),
    UNKNOWN("خطأ غير متوقع")
}

data class ModelErrorClassification(
    val category: DisconnectionCategory,
    val exactErrorMessage: String,
    val explanationAr: String
)

class AppCheckVerificationException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

interface AppCheckTokenClient {
    suspend fun getToken(forceRefresh: Boolean): String
    suspend fun clearStoredTokens()
}

class FirebaseAppCheckClient(
    private val context: Context? = null
) : AppCheckTokenClient {

    private fun resolveContext(): Context? {
        return context?.applicationContext
            ?: context
            ?: appContextRef.get()
            ?: runCatching { FirebaseApp.getInstance().applicationContext }.getOrNull()
    }

    override suspend fun getToken(forceRefresh: Boolean): String {
        val ctx = resolveContext()
        if (ctx != null && (!isProviderConfigured.get() || FirebaseApp.getApps(ctx).isEmpty())) {
            configureAppCheck(ctx, null)
        }

        lastInitErrorRef.get()?.let { initErr ->
            throw AppCheckVerificationException(
                "تعذر تهيئة خدمة الحماية (Firebase App Check): ${initErr.localizedMessage ?: "خطأ غير معروف"}",
                initErr
            )
        }

        val result = try {
            withTimeout(TOKEN_FETCH_TIMEOUT_MS) {
                FirebaseAppCheck.getInstance().getToken(forceRefresh).awaitTask()
            }
        } catch (e: TimeoutCancellationException) {
            throw AppCheckVerificationException(
                "انتهت مهلة التحقق من رمز الحماية (Firebase App Check).",
                e
            )
        }

        val token = result.token
        if (token.isBlank()) {
            throw AppCheckVerificationException("أعاد Firebase App Check رمزاً فارغاً.")
        }
        return token
    }

    override suspend fun clearStoredTokens() {
        val ctx = resolveContext()
        if (ctx == null) {
            safeLogW(TAG, "Cannot clear App Check SharedPreferences: Context is unavailable.")
            return
        }
        val cleared = clearStoredAppCheckTokens(ctx)
        safeLogI(TAG, "Cleared $cleared App Check SharedPreferences store(s).")
    }
}

/**
 * Returns a stable App Check debug secret token for debug/preview environments.
 * If provided in intent extra, that value is prioritized.
 * Otherwise, retrieves from persistent backup prefs or Firebase debug store.
 * If none exists, creates a stable UUID once and persists it synchronously with .commit().
 */
fun getEffectiveDebugSecret(context: Context): String {
    val appContext = context.applicationContext ?: context
    cachedDebugSecretRef.get()?.let { if (it.isNotBlank()) return it }

    val backupPrefs = appContext.getSharedPreferences(BACKUP_DEBUG_PREFS_NAME, Context.MODE_PRIVATE)
    val fromBackup = backupPrefs.getString(DEBUG_SECRET_KEY, null)
    if (!fromBackup.isNullOrBlank()) {
        cachedDebugSecretRef.set(fromBackup)
        persistDebugSecretToFirebaseStore(appContext, fromBackup)
        return fromBackup
    }

    val persistenceKey = runCatching {
        if (FirebaseApp.getApps(appContext).isNotEmpty()) FirebaseApp.getInstance().persistenceKey else null
    }.getOrNull()

    if (!persistenceKey.isNullOrBlank()) {
        val fbPrefs = appContext.getSharedPreferences("$DEBUG_STORE_PREFIX$persistenceKey", Context.MODE_PRIVATE)
        val fromFb = fbPrefs.getString(DEBUG_SECRET_KEY, null)
        if (!fromFb.isNullOrBlank()) {
            cachedDebugSecretRef.set(fromFb)
            backupPrefs.edit().putString(DEBUG_SECRET_KEY, fromFb).commit()
            return fromFb
        }
    }

    val newSecret = UUID.randomUUID().toString()
    cachedDebugSecretRef.set(newSecret)
    backupPrefs.edit().putString(DEBUG_SECRET_KEY, newSecret).commit()
    persistDebugSecretToFirebaseStore(appContext, newSecret)
    return newSecret
}

internal fun persistDebugSecretToFirebaseStore(context: Context, secret: String) {
    val appContext = context.applicationContext ?: context
    val persistenceKey = runCatching {
        if (FirebaseApp.getApps(appContext).isNotEmpty()) FirebaseApp.getInstance().persistenceKey else null
    }.getOrNull()
    if (!persistenceKey.isNullOrBlank()) {
        appContext.getSharedPreferences("$DEBUG_STORE_PREFIX$persistenceKey", Context.MODE_PRIVATE)
            .edit()
            .putString(DEBUG_SECRET_KEY, secret)
            .commit()
    }
}

fun getActiveProjectId(context: Context): String {
    return runCatching {
        if (FirebaseApp.getApps(context).isNotEmpty()) {
            FirebaseApp.getInstance().options.projectId
        } else null
    }.getOrNull() ?: "gen-lang-client-0140553990"
}

/**
 * Configures Firebase App Check provider synchronously for the current build variant.
 * In debug mode, ensures a stable debug secret is installed and logged.
 * In release mode, installs PlayIntegrityAppCheckProviderFactory with auto refresh.
 */
fun configureAppCheck(context: Context, intent: Intent?) {
    val appContext = context.applicationContext ?: context
    appContextRef.set(appContext)

    try {
        if (FirebaseApp.getApps(appContext).isEmpty()) {
            FirebaseApp.initializeApp(appContext)
        }

        if (BuildConfig.DEBUG) {
            val intentToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)
            val effectiveSecret = if (!intentToken.isNullOrEmpty()) {
                cachedDebugSecretRef.set(intentToken)
                appContext.getSharedPreferences(BACKUP_DEBUG_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(DEBUG_SECRET_KEY, intentToken)
                    .commit()
                intentToken
            } else {
                getEffectiveDebugSecret(appContext)
            }

            persistDebugSecretToFirebaseStore(appContext, effectiveSecret)
            safeLogD(TAG, "App Check debug secret configured successfully.")

            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
            safeLogD(TAG, "DebugAppCheckProviderFactory installed.")
        } else {
            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                PlayIntegrityAppCheckProviderFactory.getInstance()
            )
            FirebaseAppCheck.getInstance().setTokenAutoRefreshEnabled(true)
            safeLogD(TAG, "PlayIntegrityAppCheckProviderFactory installed.")
        }

        lastInitErrorRef.set(null)
        isProviderConfigured.set(true)
    } catch (e: Exception) {
        lastInitErrorRef.set(e)
        isProviderConfigured.set(false)
        safeLogE(TAG, "Failed to initialize Firebase App Check", e)
    }
}

/**
 * Synchronously clears all persisted App Check token stores and preferences.
 * In debug mode, immediately restores the stable debug secret into the debug store
 * so DebugAppCheckProviderFactory does not generate random, unregistered tokens.
 */
fun clearStoredAppCheckTokens(context: Context): Int {
    val appContext = context.applicationContext ?: context
    val storeNames = linkedSetOf(
        APP_CHECK_PREFS_NAME,
        APP_CHECK_STORE_NAME,
        DEBUG_STORE_NAME
    )

    val persistenceKey = runCatching {
        if (FirebaseApp.getApps(appContext).isNotEmpty()) {
            FirebaseApp.getInstance().persistenceKey
        } else {
            null
        }
    }.getOrNull()

    if (!persistenceKey.isNullOrBlank()) {
        storeNames.add("$APP_CHECK_STORE_PREFIX$persistenceKey")
        storeNames.add("$DEBUG_STORE_PREFIX$persistenceKey")
    }

    val dataDir = runCatching { appContext.applicationInfo?.dataDir }.getOrNull()
    if (!dataDir.isNullOrBlank()) {
        val sharedPrefsDir = File(dataDir, "shared_prefs")
        if (sharedPrefsDir.isDirectory) {
            sharedPrefsDir.listFiles()
                ?.filter { file ->
                    file.isFile &&
                        file.name.endsWith(".xml") &&
                        (file.name.startsWith("com.google.firebase.appcheck") ||
                            file.name == "$APP_CHECK_PREFS_NAME.xml")
                }
                ?.forEach { file ->
                    storeNames.add(file.name.removeSuffix(".xml"))
                }
        }
    }

    var clearedCount = 0
    for (name in storeNames) {
        val ok = runCatching {
            appContext.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }.onFailure { err ->
            safeLogE(TAG, "Failed to clear SharedPreferences store '$name'.", err)
        }.getOrDefault(false)

        if (ok) {
            clearedCount++
        }
    }

    // Preserve the stable debug secret in debug mode if previously initialized
    if (BuildConfig.DEBUG) {
        cachedDebugSecretRef.get()?.let { secret ->
            persistDebugSecretToFirebaseStore(appContext, secret)
        }
    }

    return clearedCount
}

/**
 * Classifies an exception into App Check rejection, temporary server overload (503/high demand),
 * network issue, or model error.
 */
fun classifyModelError(throwable: Throwable?): ModelErrorClassification {
    if (throwable == null) {
        return ModelErrorClassification(
            category = DisconnectionCategory.UNKNOWN,
            exactErrorMessage = "لا توجد تفاصيل خطأ متوفرة.",
            explanationAr = "تعذر تحديد سبب الخطأ."
        )
    }
    val visited = HashSet<Throwable>()
    var current: Throwable? = throwable
    var isAppCheck = false
    var isOverload = false
    var isNetwork = false
    var isModel = false

    while (current != null && visited.add(current)) {
        val msg = current.message.orEmpty()
        val name = current::class.java.name

        if (isAppCheckTokenError(current) ||
            current is AppCheckVerificationException ||
            msg.contains("token is invalid", ignoreCase = true) ||
            msg.contains("AppCheck", ignoreCase = true) ||
            msg.contains("App Check", ignoreCase = true) ||
            msg.contains("attestation", ignoreCase = true)
        ) {
            isAppCheck = true
            break
        }

        if (isModelOverloadMessage(msg)) {
            isOverload = true
        }

        if (current is java.net.UnknownHostException ||
            current is java.net.SocketTimeoutException ||
            current is java.net.ConnectException ||
            current is java.io.IOException ||
            msg.contains("Unable to resolve host", ignoreCase = true) ||
            msg.contains("network", ignoreCase = true)
        ) {
            isNetwork = true
        }

        if (msg.contains("API_KEY", ignoreCase = true) ||
            msg.contains("404", ignoreCase = true) ||
            msg.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
            msg.contains("quota", ignoreCase = true) ||
            msg.contains("generative", ignoreCase = true) ||
            name.contains("Generative", ignoreCase = true)
        ) {
            isModel = true
        }

        current = current.cause
    }

    val exact = throwable.localizedMessage ?: throwable.message ?: throwable::class.java.simpleName

    return when {
        isAppCheck -> ModelErrorClassification(
            category = DisconnectionCategory.APP_CHECK_INVALID,
            exactErrorMessage = exact,
            explanationAr = "رفضت خوادم Firebase التحقق من حماية التطبيق (Firebase App Check). في بيئة المعاينة، تأكد من تسجيل رمز التصحيح في Firebase Console."
        )
        isOverload -> ModelErrorClassification(
            category = DisconnectionCategory.MODEL_BUSY,
            exactErrorMessage = exact,
            explanationAr = "خوادم الذكاء الاصطناعي في Google تشهد ضغطاً مرتفعاً مؤقتاً (503). هذا ضغط من الخادم نفسه وليس من جهازك؛ أعد المحاولة بعد لحظات."
        )
        isNetwork -> ModelErrorClassification(
            category = DisconnectionCategory.NETWORK_ERROR,
            exactErrorMessage = exact,
            explanationAr = "تعذر الاتصال بخوادم الذكاء الاصطناعي بسبب مشكلة في شبكة الإنترنت أو انقطاع الاتصال."
        )
        isModel -> ModelErrorClassification(
            category = DisconnectionCategory.MODEL_ERROR,
            exactErrorMessage = exact,
            explanationAr = "حدث خطأ من نموذج الذكاء الاصطناعي أو حصة الاستخدام في Firebase AI Logic."
        )
        else -> ModelErrorClassification(
            category = DisconnectionCategory.UNKNOWN,
            exactErrorMessage = exact,
            explanationAr = "تعذر إكمال الاتصال بنموذج الذكاء الاصطناعي مؤقتاً، يمكنك إعادة المحاولة."
        )
    }
}

private fun isModelOverloadMessage(msg: String): Boolean {
    return msg.contains("503", ignoreCase = true) ||
        msg.contains("UNAVAILABLE", ignoreCase = true) ||
        msg.contains("overloaded", ignoreCase = true) ||
        msg.contains("high demand", ignoreCase = true) ||
        msg.contains("currently experiencing high", ignoreCase = true) ||
        msg.contains("try again later", ignoreCase = true) ||
        msg.contains("server capacity", ignoreCase = true) ||
        msg.contains("service is currently unavailable", ignoreCase = true)
}

/**
 * Returns true when [error] (or any exception in its cause chain) indicates a temporary
 * model/server capacity overload (e.g., HTTP 503, UNAVAILABLE, high demand, overloaded),
 * and is NOT an App Check token rejection.
 */
fun isModelOverloadError(error: Throwable?): Boolean {
    if (error == null || isAppCheckTokenError(error)) return false
    val visited = HashSet<Throwable>()
    var current = error
    while (current != null && visited.add(current)) {
        if (isModelOverloadMessage(current.message.orEmpty())) {
            return true
        }
        current = current.cause
    }
    return false
}

/**
 * Returns true when [error] indicates a specific model ID is temporarily or permanently
 * unavailable/unsupported/not found (so we can safely fall back to the alternate model),
 * excluding App Check errors.
 */
fun isModelUnavailableError(error: Throwable?): Boolean {
    if (error == null || isAppCheckTokenError(error)) return false
    val visited = HashSet<Throwable>()
    var current = error
    while (current != null && visited.add(current)) {
        val msg = current.message.orEmpty()
        if (msg.contains("404", ignoreCase = true) ||
            msg.contains("NOT_FOUND", ignoreCase = true) ||
            msg.contains("model not found", ignoreCase = true) ||
            msg.contains("is not found", ignoreCase = true) ||
            msg.contains("is not supported", ignoreCase = true) ||
            msg.contains("unsupported model", ignoreCase = true) ||
            msg.contains("deprecated", ignoreCase = true)
        ) {
            return true
        }
        current = current.cause
    }
    return false
}

/**
 * Returns true when [error] (or any exception in its cause chain) indicates that
 * Firebase App Check attestation/token verification failed or was rejected by Gemini.
 */
fun isAppCheckTokenError(error: Throwable?): Boolean {
    val visited = HashSet<Throwable>()
    var current = error
    while (current != null && visited.add(current)) {
        val className = current::class.java.name
        val message = current.message.orEmpty()
        if (className.contains("AppCheck", ignoreCase = true) ||
            message.contains("App Check", ignoreCase = true) ||
            message.contains("AppCheck", ignoreCase = true) ||
            message.contains("app-check", ignoreCase = true) ||
            message.contains("token is invalid", ignoreCase = true) ||
            message.contains("attestation", ignoreCase = true) ||
            message.contains("Play Integrity", ignoreCase = true)
        ) {
            return true
        }
        current = current.cause
    }
    return false
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

internal fun safeLogD(tag: String, msg: String) {
    try {
        Log.d(tag, msg)
    } catch (_: RuntimeException) {
        // Ignored in plain JVM unit tests
    }
}

internal fun safeLogI(tag: String, msg: String) {
    try {
        Log.i(tag, msg)
    } catch (_: RuntimeException) {
        // Ignored in plain JVM unit tests
    }
}

internal fun safeLogW(tag: String, msg: String, tr: Throwable? = null) {
    try {
        if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
    } catch (_: RuntimeException) {
        // Ignored in plain JVM unit tests
    }
}

internal fun safeLogE(tag: String, msg: String, tr: Throwable? = null) {
    try {
        if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
    } catch (_: RuntimeException) {
        // Ignored in plain JVM unit tests
    }
}
