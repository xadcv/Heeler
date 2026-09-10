package dev.bybee.heeler.herdr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs the shared Pairing Code vectors (`plugin/test-vectors/pairing-code-v1.json`),
 * the same file the Node plugin and Swift suites consume, so the three
 * implementations cannot drift.
 */
class PairingCodeVectorsTest {
    private val vectors: JsonObject by lazy {
        val repoRoot = System.getProperty("heeler.repoRoot")
            ?: error("heeler.repoRoot system property is not set; run through Gradle")
        val file = File(repoRoot, "plugin/test-vectors/pairing-code-v1.json")
        assertTrue(file.isFile, "missing shared vectors at $file")
        Json.parseToJsonElement(file.readText()).jsonObject
    }

    @TestFactory
    fun `valid vectors decode to their payload`(): List<DynamicTest> =
        vectors.getValue("valid").jsonArray.map { entry ->
            val vector = entry.jsonObject
            DynamicTest.dynamicTest(vector.getValue("name").jsonPrimitive.content) {
                val payload = vector.getValue("payload").jsonObject
                val code = PairingCode.decode(vector.getValue("code").jsonPrimitive.content)

                assertEquals(payload.getValue("addresses").jsonArray.map { it.jsonPrimitive.content }, code.addresses)
                assertEquals(payload.getValue("port").jsonPrimitive.content.toInt(), code.port)
                assertEquals(payload.getValue("username").jsonPrimitive.content, code.username)
                assertEquals(
                    payload.getValue("hostKeyFingerprint").jsonPrimitive.content,
                    code.hostKeyFingerprint.displayString,
                )
                val seed = payload["bootstrapSeed"]?.jsonPrimitive?.content
                if (seed == null) {
                    assertNull(code.bootstrap)
                } else {
                    val bootstrap = assertNotNull(code.bootstrap)
                    assertTrue(Base64.getUrlDecoder().decode(seed).contentEquals(bootstrap.seed))
                    assertEquals(payload.getValue("expiresAt").jsonPrimitive.content.toLong(), bootstrap.expiresAtEpochSeconds)
                }
            }
        }

    @TestFactory
    fun `invalid vectors fail with the shared error code`(): List<DynamicTest> =
        vectors.getValue("invalid").jsonArray.map { entry ->
            val vector = entry.jsonObject
            DynamicTest.dynamicTest(vector.getValue("name").jsonPrimitive.content) {
                val failure = assertFailsWith<PairingCodeException> {
                    PairingCode.decode(vector.getValue("code").jsonPrimitive.content)
                }
                assertEquals(vector.getValue("error").jsonPrimitive.content, failure.wireCode)
            }
        }
}
