package com.martin.ruby.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A place the user saved, like Home or Gym. */
data class Place(val name: String, val latitude: Double, val longitude: Double)

/** Saves the user's places on the phone, as a small JSON list inside DataStore. */
class PlaceStore(private val context: Context) {

    private val key = stringPreferencesKey("places")

    val places: Flow<List<Place>> = context.dataStore.data.map { prefs -> decode(prefs[key]) }

    /** Adds a place, replacing any existing place with the same name. */
    suspend fun add(place: Place) {
        context.dataStore.edit { prefs ->
            val others = decode(prefs[key]).filterNot { it.name.equals(place.name, ignoreCase = true) }
            prefs[key] = encode(others + place)
        }
    }

    suspend fun remove(place: Place) {
        context.dataStore.edit { prefs ->
            prefs[key] = encode(decode(prefs[key]).filterNot { it.name == place.name })
        }
    }

    private fun encode(places: List<Place>): String {
        val array = JSONArray()
        places.forEach {
            array.put(
                JSONObject()
                    .put("name", it.name)
                    .put("lat", it.latitude)
                    .put("lon", it.longitude)
            )
        }
        return array.toString()
    }

    private fun decode(json: String?): List<Place> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val array = JSONArray(json)
            List(array.length()) { i ->
                val item = array.getJSONObject(i)
                Place(item.getString("name"), item.getDouble("lat"), item.getDouble("lon"))
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }
}
