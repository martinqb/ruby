package com.martin.ruby.ai.models

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.martin.ruby.BuildConfig
import com.martin.ruby.data.dataStore
import com.martin.ruby.security.KeystoreBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Everything chosen on the Models screen, saved on the phone. */
data class ModelChoices(
    val local: List<LocalModel> = emptyList(),
    val remote: List<RemoteEndpoint> = ModelCatalog.REMOTE_PRESETS,
    val chosenLocal: String? = null,
    val chosenRemote: String? = null,
    /** Downloads only on Wi-Fi (the default). */
    val wifiOnly: Boolean = true,
    /** What the tool check found, by model ("local:<id>" or "remote:<id>:<model>"). */
    val tools: Map<String, Boolean> = emptyMap(),
) {
    val localModel: LocalModel? get() = local.firstOrNull { it.id == chosenLocal }
    val remoteEndpoint: RemoteEndpoint? get() = remote.firstOrNull { it.id == chosenRemote }

    companion object {
        fun localKey(model: LocalModel) = "local:${model.id}"
        fun remoteKey(endpoint: RemoteEndpoint) = "remote:${endpoint.id}:${endpoint.model}"
    }
}

class ModelStore(context: Context) {

    private val app = context.applicationContext

    private object Keys {
        val CUSTOM_LOCAL = stringPreferencesKey("models_custom_local")
        val REMOTE = stringPreferencesKey("models_remote")
        val CHOSEN_LOCAL = stringPreferencesKey("models_chosen_local")
        val CHOSEN_REMOTE = stringPreferencesKey("models_chosen_remote")
        val WIFI_ONLY = booleanPreferencesKey("models_wifi_only")
        val TOOLS = stringPreferencesKey("models_tools")
    }

    private val presets = ModelCatalog.localPresets(BuildConfig.PERSONAL_BUILD)

    val choices: Flow<ModelChoices> = app.dataStore.data.map { p ->
        ModelChoices(
            local = presets + decodeLocal(p[Keys.CUSTOM_LOCAL]),
            remote = p[Keys.REMOTE]?.let(::decodeRemote) ?: ModelCatalog.REMOTE_PRESETS,
            chosenLocal = p[Keys.CHOSEN_LOCAL],
            chosenRemote = p[Keys.CHOSEN_REMOTE],
            wifiOnly = p[Keys.WIFI_ONLY] ?: true,
            tools = p[Keys.TOOLS]?.let { s ->
                val o = JSONObject(s)
                o.keys().asSequence().associateWith { o.getBoolean(it) }
            } ?: emptyMap(),
        )
    }

    suspend fun current(): ModelChoices = choices.first()

    suspend fun chooseLocal(id: String?) = app.dataStore.edit { p -> if (id == null) p.remove(Keys.CHOSEN_LOCAL) else p[Keys.CHOSEN_LOCAL] = id }
    suspend fun chooseRemote(id: String?) = app.dataStore.edit { p -> if (id == null) p.remove(Keys.CHOSEN_REMOTE) else p[Keys.CHOSEN_REMOTE] = id }
    suspend fun setWifiOnly(on: Boolean) = app.dataStore.edit { it[Keys.WIFI_ONLY] = on }

    suspend fun addLocal(model: LocalModel) = app.dataStore.edit { p ->
        val list = decodeLocal(p[Keys.CUSTOM_LOCAL]).filterNot { it.id == model.id } + model
        p[Keys.CUSTOM_LOCAL] = encodeLocal(list)
    }

    suspend fun removeLocal(id: String) = app.dataStore.edit { p ->
        p[Keys.CUSTOM_LOCAL] = encodeLocal(decodeLocal(p[Keys.CUSTOM_LOCAL]).filterNot { it.id == id })
        if (p[Keys.CHOSEN_LOCAL] == id) p.remove(Keys.CHOSEN_LOCAL)
    }

    suspend fun saveRemote(endpoint: RemoteEndpoint) = app.dataStore.edit { p ->
        val list = (p[Keys.REMOTE]?.let(::decodeRemote) ?: ModelCatalog.REMOTE_PRESETS)
        val i = list.indexOfFirst { it.id == endpoint.id }
        p[Keys.REMOTE] = encodeRemote(if (i < 0) list + endpoint else list.toMutableList().also { it[i] = endpoint })
    }

    suspend fun removeRemote(id: String) {
        app.dataStore.edit { p ->
            p[Keys.REMOTE] = encodeRemote((p[Keys.REMOTE]?.let(::decodeRemote) ?: ModelCatalog.REMOTE_PRESETS).filterNot { it.id == id })
            if (p[Keys.CHOSEN_REMOTE] == id) p.remove(Keys.CHOSEN_REMOTE)
        }
        clearKey(id)
    }

    suspend fun setTools(key: String, works: Boolean) = app.dataStore.edit { p ->
        val o = p[Keys.TOOLS]?.let { JSONObject(it) } ?: JSONObject()
        p[Keys.TOOLS] = o.put(key, works).toString()
    }

    // ---- API keys: one per entry, wrapped by a Keystore key in no_backup (like the Claude key).

    private fun keyFile(id: String) = File(app.noBackupFilesDir, "model_key_" + id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".bin")

    suspend fun hasKey(id: String): Boolean = withContext(Dispatchers.IO) { keyFile(id).exists() }

    suspend fun loadKey(id: String): String? = withContext(Dispatchers.IO) {
        val f = keyFile(id)
        if (!f.exists()) null else try {
            String(KeystoreBox.load(KEY_ALIAS, f), Charsets.UTF_8)
        } catch (e: KeystoreBox.Unavailable) {
            null
        }
    }

    suspend fun saveKey(id: String, key: String) = withContext(Dispatchers.IO) {
        if (key.isBlank()) keyFile(id).delete() else KeystoreBox.save(KEY_ALIAS, keyFile(id), key.trim().toByteArray(Charsets.UTF_8))
    }

    suspend fun clearKey(id: String) = withContext(Dispatchers.IO) { keyFile(id).delete() }

    companion object {
        private const val KEY_ALIAS = "ruby_model_keys_wrap"

        fun encodeLocal(list: List<LocalModel>): String = JSONArray().apply {
            list.forEach { m ->
                put(JSONObject().put("id", m.id).put("name", m.name).put("url", m.url).put("file", m.fileName).put("bytes", m.bytes ?: -1))
            }
        }.toString()

        fun decodeLocal(s: String?): List<LocalModel> = if (s.isNullOrBlank()) emptyList() else try {
            val a = JSONArray(s)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                LocalModel(
                    id = o.getString("id"), name = o.getString("name"), url = o.getString("url"), fileName = o.getString("file"),
                    bytes = o.optLong("bytes", -1).takeIf { it > 0 }, custom = true,
                )
            }
        } catch (e: org.json.JSONException) {
            emptyList()
        }

        fun encodeRemote(list: List<RemoteEndpoint>): String = JSONArray().apply {
            list.forEach { e ->
                put(JSONObject().put("id", e.id).put("name", e.name).put("baseUrl", e.baseUrl).put("model", e.model).put("needsKey", e.needsKey))
            }
        }.toString()

        fun decodeRemote(s: String): List<RemoteEndpoint> = try {
            val a = JSONArray(s)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                RemoteEndpoint(o.getString("id"), o.getString("name"), o.getString("baseUrl"), o.optString("model"), o.optBoolean("needsKey"))
            }
        } catch (e: org.json.JSONException) {
            ModelCatalog.REMOTE_PRESETS
        }
    }
}
