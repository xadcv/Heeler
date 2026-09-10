package dev.bybee.heeler.data

import dev.bybee.heeler.herdr.HerdrSocketLocation
import dev.bybee.heeler.ssh.SshEndpoint
import kotlinx.serialization.Serializable

/** One saved Host: where herdr runs and how to reach it over SSH. */
@Serializable
data class HostRecord(
    val id: String,
    val name: String,
    val hostname: String,
    val port: Int = 22,
    val username: String,
    /** A named herdr session, or null for the default `~/.config/herdr/herdr.sock`. */
    val session: String? = null,
) {
    val endpoint: SshEndpoint get() = SshEndpoint(hostname, port, username)

    val socketLocation: HerdrSocketLocation
        get() = session?.takeIf { it.isNotBlank() }?.let(HerdrSocketLocation::NamedSession)
            ?: HerdrSocketLocation.DefaultSession

    val displayName: String get() = name.ifBlank { "$username@$hostname" }
}
