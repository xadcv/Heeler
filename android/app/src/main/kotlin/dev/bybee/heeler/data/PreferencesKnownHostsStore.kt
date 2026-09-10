package dev.bybee.heeler.data

import android.content.Context
import androidx.core.content.edit
import dev.bybee.heeler.herdr.HostKeyFingerprint
import dev.bybee.heeler.ssh.KnownHostsStore
import dev.bybee.heeler.ssh.SshEndpoint

/**
 * TOFU-approved host keys, keyed `host:port`, stored as OpenSSH `SHA256:`
 * presentations. SharedPreferences because [KnownHostsStore] is synchronous:
 * JSch asks for the verdict inside its blocking connect.
 */
class PreferencesKnownHostsStore(context: Context) : KnownHostsStore {
    private val preferences = context.getSharedPreferences("known_hosts", Context.MODE_PRIVATE)

    override fun trusted(endpoint: SshEndpoint): HostKeyFingerprint? =
        preferences.getString(key(endpoint), null)?.let(HostKeyFingerprint::parse)

    override fun trust(endpoint: SshEndpoint, fingerprint: HostKeyFingerprint) {
        preferences.edit { putString(key(endpoint), fingerprint.displayString) }
    }

    fun forget(endpoint: SshEndpoint) {
        preferences.edit { remove(key(endpoint)) }
    }

    private fun key(endpoint: SshEndpoint) = "${endpoint.host}:${endpoint.port}"
}
