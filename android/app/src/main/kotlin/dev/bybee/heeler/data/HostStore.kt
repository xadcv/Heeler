package dev.bybee.heeler.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.bybee.heeler.herdr.HerdrJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer

private val Context.hostsDataStore: DataStore<Preferences> by preferencesDataStore(name = "hosts")

/**
 * The saved Hosts, as one JSON list in Preferences DataStore. Small, ordered,
 * and read as a whole on every screen, so a document beats a table here.
 */
class HostStore(private val context: Context) {
    private val key = stringPreferencesKey("hosts.v1")
    private val serializer = ListSerializer(HostRecord.serializer())

    val hosts: Flow<List<HostRecord>> = context.hostsDataStore.data.map { preferences ->
        preferences[key]?.let(::decode) ?: emptyList()
    }

    suspend fun save(host: HostRecord) {
        context.hostsDataStore.edit { preferences ->
            val current = preferences[key]?.let(::decode) ?: emptyList()
            val updated = if (current.any { it.id == host.id }) current.map { if (it.id == host.id) host else it } else current + host
            preferences[key] = HerdrJson.encodeToString(serializer, updated)
        }
    }

    suspend fun delete(id: String) {
        context.hostsDataStore.edit { preferences ->
            val current = preferences[key]?.let(::decode) ?: emptyList()
            preferences[key] = HerdrJson.encodeToString(serializer, current.filterNot { it.id == id })
        }
    }

    private fun decode(json: String): List<HostRecord> = try {
        HerdrJson.decodeFromString(serializer, json)
    } catch (_: SerializationException) {
        // A corrupt document loses the list rather than wedging every screen.
        emptyList()
    }
}
