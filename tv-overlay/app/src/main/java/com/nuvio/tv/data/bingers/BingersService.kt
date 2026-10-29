package com.nuvio.tv.data.bingers

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.nuvio.android.bingers.*
import com.nuvio.android.cinemana.CinemanaTmdbMatcher
import com.nuvio.android.cinemana.CinemanaClient
import com.nuvio.tv.core.tracking.*
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class BingersUiState(val linked: Boolean = false, val enabled: Boolean = false,
    val pending: Int = 0, val unmatched: Int = 0, val busy: Boolean = false, val message: String = "غير مرتبط")

@Singleton
class BingersService @Inject constructor(
    @ApplicationContext context: Context,
    profileDataStore: ProfileDataStore,
    private val authManager: AuthManager,
) : ProfileScopedCredentialStore {
    private var preferences: SharedPreferences? = null
    private val mutex = Mutex()
    private val removed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val state = MutableStateFlow(BingersUiState())
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
    private val client = BingersClient { method, url, headers, body ->
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url)
            headers.forEach { (name, value) -> request.header(name, value) }
            if (method == "POST") request.post(body.orEmpty().toRequestBody("application/json".toMediaType())) else request.get()
            // Dedicated client has no network logger/interceptor: never expose magic tokens or cookies.
            http.newCall(request.build()).execute().use {
                BingersResponse(it.code, it.body.string(), it.headers.values("Set-Cookie"))
            }
        }
    }

    private val activeProfileId = profileDataStore.activeProfileId.stateIn(scope, SharingStarted.Eagerly, 1)
    init {
        preferences = context.getSharedPreferences("nuvio_bingers_private", Context.MODE_PRIVATE)
        scope.launch {
            combine(activeProfileId, authManager.authState) { profile, auth -> profile to auth }
                .collectLatest { state.value = BingersUiState(); refresh() }
        }
    }
    private suspend fun httpGetText(url: String): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use {
            check(it.isSuccessful) { "Provider request failed" }
            it.body.string()
        }
    }
    private data class MatcherSettings(val apiKey: String, val language: String = "ar") {
        val enabled get() = apiKey.isNotBlank()
    }
    fun profileKey(profile: Int = activeProfileId.value): String? {
        val owner = (authManager.authState.value as? AuthState.FullAccount)?.userId ?: "local"
        return MessageDigest.getInstance("SHA-256").digest("$owner:$profile".toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun encryptionKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("nuvio-bingers-v1", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("nuvio-bingers-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    private fun load(key: String): JSONObject {
        val raw = preferences?.getString(key, null) ?: return JSONObject()
        return try {
            val bytes = Base64.decode(raw, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(key.toByteArray())
            JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        } catch (_: Exception) { throw BingersFailure(401, "تعذر قراءة جلسة Bingers المحفوظة؛ أعد ربط الحساب") }
    }
    private fun save(key: String, data: JSONObject) {
        if (key in removed) throw BingersFailure(401, "تم حذف هذا الملف الشخصي")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        cipher.updateAAD(key.toByteArray())
        val raw = Base64.encodeToString(cipher.iv + cipher.doFinal(data.toString().toByteArray()), Base64.NO_WRAP)
        check(preferences?.edit()?.putString(key, raw)?.commit() == true) { "تعذر حفظ مزامنة Bingers" }
    }
    private fun publish(key: String, data: JSONObject, message: String, busy: Boolean = false) {
        data.put("lastMessage", message)
        if (!busy && data.optString("cookie").isNotBlank()) runCatching { save(key, data) }
        if (profileKey() == key) state.value = BingersUiState(
            linked = data.optString("cookie").isNotBlank(), enabled = data.optBoolean("enabled"),
            pending = data.optJSONArray("queue")?.length() ?: 0,
            unmatched = data.optJSONArray("failed")?.length() ?: 0, busy = busy, message = message)
    }
    private fun integrationAvailable(): Boolean {
        // Official external API requires Legend/Icon. Private session protocol is not shipped enabled.
        state.value = BingersUiState(message = "ربط التطبيقات في Bingers يتطلب Legend أو Icon؛ التتبع المجاني غير متاح رسمياً")
        return false
    }
    fun refresh() {
        if (!integrationAvailable()) return
        val key = profileKey() ?: return
        scope.launch { mutex.withLock {
            try { val data = load(key); publish(key, data, if (data.optString("cookie").isBlank()) "غير مرتبط" else data.optString("lastMessage").ifBlank { "مرتبط • المزامنة عند اكتمال 80٪ من المشاهدة" }) }
            catch (error: BingersFailure) { state.value = BingersUiState(message = error.reason) }
        } }
    }
    fun link(magicLink: String) {
        if (!integrationAvailable()) return
        val key = profileKey() ?: return
        scope.launch { mutex.withLock {
            state.value = state.value.copy(busy = true, message = "جاري ربط الحساب…")
            try {
                val session = client.login(magicLink)
                val old = runCatching { load(key) }.getOrElse { JSONObject() }
                val data = if (old.optString("account") == session.userId) old else JSONObject()
                data.put("account", session.userId).put("cookie", session.cookie).put("enabled", false)
                removed.remove(key)
                save(key, data)
                publish(key, data, "تم الربط؛ فعّل مزامنة المشاهدات. صلاحية الحساب المجاني تُعرف عند أول مزامنة فعلية")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { publish(key, runCatching { load(key) }.getOrElse { JSONObject() }, (error as? BingersFailure)?.reason ?: "تعذر الربط؛ اطلب رابط دخول جديداً") }
        } }
    }
    fun setEnabled(enabled: Boolean) {
        if (!integrationAvailable()) return
        val key = profileKey() ?: return
        scope.launch { mutex.withLock {
            try {
                val data = load(key)
                data.put("enabled", enabled && data.optString("cookie").isNotBlank())
                save(key, data); publish(key, data, if (enabled) "مزامنة المشاهدات مفعلة عند 80٪" else "المزامنة متوقفة")
            } catch (error: Exception) { showError(key, error) }
        } }
    }
    fun disconnect() {
        val key = profileKey() ?: return
        scope.launch { mutex.withLock {
            preferences?.edit()?.remove(key)?.commit()
            if (profileKey() == key) state.value = BingersUiState(message = "تم فصل الحساب وحذف الجلسة والمزامنات المحلية المعلقة")
        } }
    }
    override fun removeProfile(profileId: Int) {
        val key = profileKey(profileId) ?: return
        removed.add(key)
        preferences?.edit()?.remove(key)?.commit()
    }

    fun onScrobble(profileId: Int, action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
        if (!integrationAvailable()) return
        if (!BingersClient.completed(action.wireValue, event.progressPercent)) return
        val key = profileKey(profileId) ?: return
        scope.launch { mutex.withLock {
            try {
                val data = load(key)
                if (!data.optBoolean("enabled") || data.optString("cookie").isBlank()) return@withLock
                val media = event.media
                if (media.kind != TrackingMediaKind.MOVIE && media.episode == null) return@withLock
                val id = media.stableKey + ":${media.episode?.season}:${media.episode?.number}"
                val queue = data.optJSONArray("queue") ?: JSONArray()
                if ((0 until queue.length()).any { queue.optJSONObject(it)?.optString("key") == id }) return@withLock
                if (queue.length() >= 100) throw BingersFailure(429, "قائمة Bingers ممتلئة؛ أعد محاولة المزامنات المعلقة")
                val item = JSONObject().put("key", id).put("title", media.title).put("year", media.year)
                    .put("tmdb", media.ids.tmdb).put("imdb", media.ids.imdb)
                    .put("season", media.episode?.season).put("episode", media.episode?.number)
                    .put("catalogId", media.catalog?.contentId).put("videoId", media.catalog?.videoId)
                queue.put(item); data.put("queue", queue); save(key, data)
                flush(key, data)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { showError(key, error) }
        } }
    }
    override fun clearAllProfiles() {
        preferences?.edit()?.clear()?.commit()
        state.value = BingersUiState()
    }
    fun onPlayback(action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
        onScrobble(activeProfileId.value, action, event)
    }
    fun retry() {
        if (!integrationAvailable()) return
        val key = profileKey() ?: return
        scope.launch { mutex.withLock {
            try {
                val data = load(key)
                if (!data.optBoolean("enabled")) { publish(key, data, "فعّل المزامنة أولاً"); return@withLock }
                if (client.session(data.optString("cookie")) != data.optString("account")) throw BingersFailure(401, "تغيّر حساب Bingers؛ أعد الربط")
                val failed = data.optJSONArray("failed") ?: JSONArray()
                val queue = data.optJSONArray("queue") ?: JSONArray()
                for (i in 0 until failed.length()) queue.put(failed.getJSONObject(i))
                data.put("queue", queue).put("failed", JSONArray()); save(key, data)
                flush(key, data)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { showError(key, error) }
        } }
    }
    private suspend fun flush(key: String, data: JSONObject) {
        val queue = data.optJSONArray("queue") ?: JSONArray()
        publish(key, data, "جاري مزامنة المشاهدات…", true)
        while (queue.length() > 0 && profileKey() == key && data.optBoolean("enabled")) {
            val item = queue.getJSONObject(0)
            try {
            var batch = item.optString("batch")
            if (batch.isBlank()) {
                var tmdb = BingersClient.tmdbId(item.optString("catalogId"))
                    ?: item.optString("tmdb").toLongOrNull()
                val titles = mutableListOf(item.optString("title").takeUnless { it == "null" }.orEmpty())
                var year = item.optString("year").toIntOrNull()
                var imdb = item.optString("imdb").takeIf { it.matches(Regex("tt[0-9]+")) }
                val episode = item.optString("episode").toIntOrNull()
                val settings = MatcherSettings(BuildConfig.TMDB_API_KEY)
                if (tmdb == null && imdb == null) {
                    val cinemanaId = listOf(item.optString("catalogId"), item.optString("videoId"))
                        .firstNotNullOfOrNull { Regex("^cinemana(?:-series)?:([0-9]+)").find(it)?.groupValues?.get(1) }
                    if (cinemanaId != null) {
                        try {
                            val info = CinemanaClient(transport = ::httpGetText).info(cinemanaId)
                            titles.addAll(listOf("en_title", "ar_title", "other_title").map { info.optString(it) }.filter { it.isNotBlank() && it != "null" })
                            year = year ?: info.optString("year").take(4).toIntOrNull()
                            if (episode == null) imdb = Regex("tt[0-9]+").find(info.optString("imdbUrlRef"))?.value
                        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* known title may still resolve */ }
                    }
                    if (imdb == null && settings.enabled) {
                        val matched = CinemanaTmdbMatcher(settings.apiKey, settings.language, ::httpGetText).match(titles, year, episode != null)
                        tmdb = matched?.tmdbId?.toLong()
                        if (matched != null) { year = matched.year; titles.add(matched.title) }
                    }
                }
                if (tmdb != null && settings.enabled) {
                    try {
                        val kind = if (episode == null) "movie" else "tv"
                        val details = JSONObject(httpGetText("https://api.themoviedb.org/3/$kind/$tmdb?api_key=${java.net.URLEncoder.encode(settings.apiKey, "UTF-8")}&language=en-US"))
                        titles.addAll(0, listOf(details.optString(if (episode == null) "title" else "name"),
                            details.optString(if (episode == null) "original_title" else "original_name")))
                    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* retain known IDs and original title */ }
                }
                publish(key, data, "جاري مطابقة عنوان الحلقة في كاتلوغ Bingers…", true)
                val entity = client.resolve(BingersMedia(titles, year, tmdb,
                    imdb, item.optString("season").toIntOrNull(), episode))
                publish(key, data, "جاري قراءة سجل المشاهدة من Bingers…", true)
                batch = client.prepare(entity, data.optString("cookie")).orEmpty()
                if (batch.isNotBlank()) { item.put("batch", batch); save(key, data) }
            }
            if (key in removed) return
            if (batch.isNotBlank()) {
                publish(key, data, "جاري إرسال المشاهدة إلى Bingers…", true)
                client.push(data.optString("cookie"), batch)
            }
            queue.remove(0); data.put("queue", queue); save(key, data)
            } catch (error: BingersFailure) {
                if (error.status != 422) throw error
                val failed = data.optJSONArray("failed") ?: JSONArray()
                if (failed.length() >= 100) throw BingersFailure(429, "قائمة العناوين غير المطابقة ممتلئة؛ أعد المحاولة لاحقاً")
                item.put("failure", error.reason)
                failed.put(item); queue.remove(0)
                data.put("queue", queue).put("failed", failed); save(key, data)
            }
        }
        val failedCount = data.optJSONArray("failed")?.length() ?: 0
        publish(key, data, if (failedCount > 0) "$failedCount عنوان لم تتم مطابقته: ${data.optJSONArray("failed")?.optJSONObject(0)?.optString("failure").orEmpty()}" else if (queue.length() == 0) "تمت مزامنة المشاهدات بنجاح" else "المشاهدات محفوظة لإعادة المحاولة")
    }
    private fun showError(key: String, error: Exception) {
        val data = runCatching { load(key) }.getOrElse { JSONObject() }
        if (error is BingersFailure && (error.status in listOf(401, 402, 403) || error.reason.contains("اشتراك"))) {
            data.put("enabled", false)
            runCatching { save(key, data) }
        }
        val stage = state.value.takeIf { profileKey() == key && it.busy }?.message
        val reason = (error as? BingersFailure)?.reason ?: "تعذرت المزامنة؛ المشاهدة محفوظة لإعادة المحاولة"
        publish(key, data, listOfNotNull(stage, reason).joinToString(" • "))
    }
}
