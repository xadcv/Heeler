package dev.bybee.heeler.herdr

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.util.Base64

/**
 * A parsed Pairing Code: the versioned payload the pairing plugin renders as a
 * QR image (ADR 0007). Wire format:
 *
 *     HERDR-PAIR:<version>:<base64url(JSON, no padding)>
 *
 * The schema and error taxonomy live in `plugin/README.md`; the shared vectors
 * in `plugin/test-vectors/pairing-code-v1.json` are the single source of truth
 * for this decoder, the Swift decoder, and the plugin's encoder. Unknown
 * payload fields are ignored (additive v1 metadata).
 */
public data class PairingCode(
    /** Candidate addresses in the order the app should try them. IPv6 literals carry no brackets. */
    val addresses: List<String>,
    /** SSH port, 1..65535. */
    val port: Int,
    val username: String,
    /** The Host's SSH host key fingerprint, pinned instead of a TOFU prompt. */
    val hostKeyFingerprint: HostKeyFingerprint,
    /** The Bootstrap Key material, absent on a config-only Pairing Code. */
    val bootstrap: Bootstrap?,
) {
    /**
     * The single-use Enrollment credential carried inside a Pairing Code. Lives
     * in memory only; never enters persistent storage (ADR 0007).
     */
    public class Bootstrap(seed: ByteArray, public val expiresAtEpochSeconds: Long) {
        /** Raw 32-byte Ed25519 seed of the Bootstrap Key. */
        public val seed: ByteArray = seed.copyOf()
            get() = field.copyOf()

        private val seedForEquality = seed.copyOf()

        override fun equals(other: Any?): Boolean =
            other is Bootstrap && seedForEquality.contentEquals(other.seedForEquality) &&
                expiresAtEpochSeconds == other.expiresAtEpochSeconds

        override fun hashCode(): Int = 31 * seedForEquality.contentHashCode() + expiresAtEpochSeconds.hashCode()

        override fun toString(): String = "Bootstrap(seed=<32 bytes>, expiresAt=$expiresAtEpochSeconds)"
    }

    public companion object {
        public const val PREFIX: String = "HERDR-PAIR"
        public const val VERSION: Int = 1
        private const val BOOTSTRAP_SEED_BYTES = 32

        /** Decodes and validates a scanned Pairing Code string. */
        @Throws(PairingCodeException::class)
        public fun decode(scanned: String): PairingCode {
            if (!scanned.startsWith("$PREFIX:")) throw PairingCodeException.BadPrefix
            val rest = scanned.substring(PREFIX.length + 1)
            val separator = rest.indexOf(':')
            if (separator < 0) throw PairingCodeException.BadPrefix
            val foundVersion = rest.substring(0, separator)
            if (foundVersion != VERSION.toString()) throw PairingCodeException.UnsupportedVersion(foundVersion)

            val body = decodeBase64Url(rest.substring(separator + 1)) ?: throw PairingCodeException.BadEncoding
            val wire = try {
                HerdrJson.parseToJsonElement(body.decodeToString())
            } catch (_: SerializationException) {
                throw PairingCodeException.BadEncoding
            } catch (_: IllegalArgumentException) {
                throw PairingCodeException.BadEncoding
            }
            val payload = wire as? JsonObject
                ?: throw PairingCodeException.BadPayload("payload must be a JSON object")
            return validated(payload)
        }

        private fun validated(wire: JsonObject): PairingCode {
            val addresses = (wire["addrs"] as? JsonArray)
                ?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            if (addresses.isNullOrEmpty() || addresses.any { it == null }) {
                throw PairingCodeException.BadPayload("addresses must be a non-empty array of strings")
            }
            val validAddresses = addresses.filterNotNull()
            validAddresses.firstOrNull { it.isEmpty() || containsWhitespace(it) }?.let {
                throw PairingCodeException.BadPayload("invalid address: $it")
            }
            val port = wire["port"].asExactInt()
            if (port == null || port !in 1..65535) {
                throw PairingCodeException.BadPayload("port must be an integer in 1..65535")
            }
            val username = wire["user"].asString()
            if (username.isNullOrEmpty() || containsWhitespace(username)) {
                throw PairingCodeException.BadPayload("username must be a non-empty string without whitespace")
            }
            val fingerprint = wire["fp"].asString()?.let(HostKeyFingerprint::parse)
                ?: throw PairingCodeException.BadPayload("fp must be an OpenSSH SHA256 fingerprint")

            val seedElement = wire["seed"].takeUnless { it is JsonNull }
            val expElement = wire["exp"].takeUnless { it is JsonNull }
            val bootstrap = when {
                seedElement == null && expElement == null -> null
                seedElement != null && expElement != null -> {
                    val seed = seedElement.asString()?.let(::decodeBase64Url)
                    if (seed == null || seed.size != BOOTSTRAP_SEED_BYTES) {
                        throw PairingCodeException.BadPayload("seed must be $BOOTSTRAP_SEED_BYTES bytes of base64url")
                    }
                    val expiry = expElement.asExactLong()
                    if (expiry == null || expiry <= 0) {
                        throw PairingCodeException.BadPayload("exp must be a positive unix-seconds integer")
                    }
                    Bootstrap(seed, expiry)
                }
                else -> throw PairingCodeException.BadPayload("seed and exp must be present together")
            }

            return PairingCode(validAddresses, port, username, fingerprint, bootstrap)
        }

        private fun JsonElement?.asString(): String? =
            (this as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonElement?.asExactLong(): Long? {
            val primitive = this as? JsonPrimitive ?: return null
            if (primitive.isString) return null
            val value = primitive.doubleOrNull ?: return null
            if (value != Math.floor(value) || value.isInfinite()) return null
            return value.toLong()
        }

        private fun JsonElement?.asExactInt(): Int? = asExactLong()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

        private fun containsWhitespace(text: String): Boolean = text.any { Character.isWhitespace(it) || it == '\u00A0' }

        /** Unpadded base64url; padded input is also accepted, anything else is null. */
        private fun decodeBase64Url(text: String): ByteArray? {
            if (text.isEmpty()) return null
            if (!text.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' || it == '=' }) return null
            return try {
                Base64.getUrlDecoder().decode(text)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}

/**
 * Why a scanned string is not a Pairing Code. These map 1:1 to the error
 * identifiers in the shared test vectors ([wireCode]).
 */
public sealed class PairingCodeException(message: String) : Exception(message) {
    /** Not a Pairing Code at all; likely someone else's QR. */
    public data object BadPrefix : PairingCodeException("not a Pairing Code")

    /** A Pairing Code from a plugin speaking another envelope version. */
    public data class UnsupportedVersion(val found: String) : PairingCodeException("unsupported Pairing Code version $found")

    /** The framing is right but the body is not base64url-encoded JSON. */
    public data object BadEncoding : PairingCodeException("Pairing Code body is not base64url JSON")

    /** Well-formed JSON that violates the payload schema. */
    public data class BadPayload(val reason: String) : PairingCodeException("invalid Pairing Code payload: $reason")

    /** The cross-implementation identifier used by the shared test vectors. */
    public val wireCode: String
        get() = when (this) {
            BadPrefix -> "bad_prefix"
            is UnsupportedVersion -> "unsupported_version"
            BadEncoding -> "bad_encoding"
            is BadPayload -> "bad_payload"
        }
}
