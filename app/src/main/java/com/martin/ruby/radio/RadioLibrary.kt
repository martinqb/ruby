package com.martin.ruby.radio

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.martin.ruby.data.dataStore
import com.martin.ruby.log.SensingLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The user's saved stations, on the phone (see [Presets]). New users start with none. */
class PresetStore(context: Context) {

    private val app = context.applicationContext
    private val key = stringPreferencesKey("radio_presets")

    val presets: Flow<Presets> = app.dataStore.data.map { Presets.fromJson(it[key]) }

    suspend fun current(): Presets = presets.first()

    suspend fun update(change: (Presets) -> Presets) {
        app.dataStore.edit { it[key] = change(Presets.fromJson(it[key])).toJson() }
    }

    /** Whether anything has ever been saved (for setting up a profile only on a fresh phone). */
    suspend fun everSaved(): Boolean = app.dataStore.data.first()[key] != null
}

/** Finding stations: the ones Ruby knows, then the free Radio Browser directory. */
object RadioSearch {

    /** Stations matching [text]: curated ones first, then Radio Browser's most popular. Throws [IOException] if the directory can't be reached and nothing curated matched. */
    suspend fun search(by: RadioBrowser.By, text: String): List<Station> = withContext(Dispatchers.IO) {
        val curated = Stations.curatedMatching(text)
        var problem: IOException? = null
        for (server in RadioBrowser.SERVERS) {
            try {
                val found = RadioBrowser.parse(fetch(RadioBrowser.url(server, by, text)))
                // A curated station replaces a directory copy of itself (WEBN's iHeart stream, say).
                return@withContext curated + found.filterNot { f -> curated.any { Stations.squash(it.name) == Stations.squash(f.name) } }
            } catch (e: IOException) {
                problem = e
            }
        }
        if (curated.isNotEmpty()) curated else throw problem ?: IOException("Radio Browser couldn't be reached")
    }

    private fun fetch(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8_000
            c.readTimeout = 10_000
            // Radio Browser asks apps to say who they are; no personal details.
            c.setRequestProperty("User-Agent", "Ruby-Android/1.0")
            if (c.responseCode != 200) throw IOException("Radio Browser returned ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

/** Opening a station in another radio app, for ones that can't play inside Ruby. */
object OtherRadioApps {

    /**
     * Asks the phone's radio and music apps (TuneIn, iHeartRadio and the like) to play
     * [station], through Android's standard "play from search". Returns what Ruby says.
     */
    fun open(context: Context, station: Station): String {
        val search = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(SearchManager.QUERY, "${station.name} radio")
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        return try {
            context.applicationContext.startActivity(
                Intent.createChooser(search, "Play ${station.name} with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            SensingLog.add("Radio: asked another app to play ${station.name}.")
            "Choose an app to play ${station.name}."
        } catch (e: ActivityNotFoundException) {
            "No radio app on this phone can play ${station.name}."
        }
    }
}
