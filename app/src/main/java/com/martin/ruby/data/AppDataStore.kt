package com.martin.ruby.data

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

// One DataStore file for the whole app. It must be declared once, at the top level,
// and every settings class reads and writes through it.
internal val Context.dataStore by preferencesDataStore(name = "settings")
