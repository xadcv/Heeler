package dev.bybee.heeler.herdr

import dev.bybee.heeler.herdr.generated.AgentRenameParams
import dev.bybee.heeler.herdr.generated.OkResponse
import dev.bybee.heeler.herdr.generated.PaneReadParams
import dev.bybee.heeler.herdr.generated.PaneReadResponse
import dev.bybee.heeler.herdr.generated.PongResponse
import dev.bybee.heeler.herdr.generated.ReadSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HerdrWireTest {
    @Test
    fun `parameterless request carries an empty params object and a newline`() {
        val line = HerdrWire.requestLine("r1", "ping")
        assertTrue(line.endsWith("\n"))
        val parsed = Json.parseToJsonElement(line.trimEnd()).jsonObject
        assertEquals("r1", parsed.getValue("id").jsonPrimitive.content)
        assertEquals("ping", parsed.getValue("method").jsonPrimitive.content)
        assertEquals(0, parsed.getValue("params").jsonObject.size)
    }

    @Test
    fun `typed params use wire names and omit absent optionals`() {
        val line = HerdrWire.requestLine("r2", "pane.read", PaneReadParams(paneID = "w1:pA", source = ReadSource.recent))
        val params = Json.parseToJsonElement(line.trimEnd()).jsonObject.getValue("params").jsonObject
        assertEquals(setOf("pane_id", "source"), params.keys)
        assertEquals("recent", params.getValue("source").jsonPrimitive.content)

        // A null name on agent.rename is omitted; herdr treats null and absent identically.
        val rename = HerdrWire.requestLine("r3", "agent.rename", AgentRenameParams(target = "w1:pA", name = null))
        assertEquals(setOf("target"), Json.parseToJsonElement(rename.trimEnd()).jsonObject.getValue("params").jsonObject.keys)
    }

    @Test
    fun `subscribe request spells kinds dotted and scopes pane entries`() {
        val line = HerdrWire.subscribeRequestLine(
            "s1",
            listOf(
                EventSubscription.Global(GlobalEventKind.PANE_CREATED),
                EventSubscription.Pane(PaneEventKind.AGENT_STATUS_CHANGED, "w1:pA"),
            ),
        )
        assertEquals(
            """{"id":"s1","method":"events.subscribe","params":{"subscriptions":[{"type":"pane.created"},{"type":"pane.agent_status_changed","pane_id":"w1:pA"}]}}""",
            line.trimEnd(),
        )
    }

    @Test
    fun `success result requires an exact id match`() {
        val pong = HerdrWire.decodeResult<PongResponse>(
            """{"id":"r1","result":{"type":"pong","version":"0.8.2","protocol":20,"capabilities":{"live_handoff":true},"future":1}}""" + "\n",
            requestID = "r1",
        )
        assertEquals(20L, pong.protocolVersion)
        assertEquals("0.8.2", pong.version)

        val mismatch = assertFailsWith<TransportError.MalformedResponse> {
            HerdrWire.decodeResult<PongResponse>("""{"id":"other","result":{"version":"x","protocol":20}}""", "r1")
        }
        assertTrue("other" in mismatch.detail)
    }

    @Test
    fun `errors are attributed to the sole in-flight request regardless of id`() {
        // herdr 0.8.0 answers malformed requests with id "" (#177).
        val empty = assertFailsWith<TransportError.ApiRejected> {
            HerdrWire.decodeResult<OkResponse>("""{"id":"","error":{"code":"invalid_request","message":"bad"}}""", "r1")
        }
        assertEquals("invalid_request", empty.code)
        assertEquals("bad", empty.apiMessage)

        // events.subscribe probe failures use a derived id.
        val probe = assertFailsWith<TransportError.ApiRejected> {
            HerdrWire.decodeResult<OkResponse>(
                """{"id":"r1:sub:0:probe","error":{"code":"pane_not_found","message":"no pane"}}""",
                "r1",
            )
        }
        assertEquals("pane_not_found", probe.code)

        // Integer codes are stringified rather than rejected.
        val numeric = assertFailsWith<TransportError.ApiRejected> {
            HerdrWire.decodeResult<OkResponse>("""{"id":"r1","error":{"code":-32601,"message":"no method"}}""", "r1")
        }
        assertEquals("-32601", numeric.code)
    }

    @Test
    fun `undecodable lines and result shape mismatches are malformed`() {
        assertFailsWith<TransportError.MalformedResponse> {
            HerdrWire.decodeResult<OkResponse>("not json", "r1")
        }
        assertFailsWith<TransportError.MalformedResponse> {
            HerdrWire.decodeResult<OkResponse>("""{"id":"r1"}""", "r1")
        }
        // pane.read's text lives at result.read.text; a flat result must not decode.
        assertFailsWith<TransportError.MalformedResponse> {
            HerdrWire.decodeResult<PaneReadResponse>("""{"id":"r1","result":{"text":"hi"}}""", "r1")
        }
    }

    @Test
    fun `event lines map snake_case onto canonical dotted kinds`() {
        val created = assertNotNull(HerdrWire.decodeEvent("""{"event":"pane_created","data":{"pane_id":"w1:pA"}}"""))
        assertEquals(GlobalEventKind.PANE_CREATED.kind, created.kind)
        assertEquals("w1:pA", created.data.jsonObject.getValue("pane_id").jsonPrimitive.content)

        val dotted = assertNotNull(HerdrWire.decodeEvent("""{"event":"pane.agent_status_changed","data":{}}"""))
        assertEquals(PaneEventKind.AGENT_STATUS_CHANGED.kind, dotted.kind)

        val unknown = assertNotNull(HerdrWire.decodeEvent("""{"event":"future_thing"}"""))
        assertEquals(HerdrEventKind("future_thing"), unknown.kind)
        assertIs<JsonNull>(unknown.data)

        assertNull(HerdrWire.decodeEvent("""{"id":"r1","result":{}}"""))
        assertNull(HerdrWire.decodeEvent("garbage"))
        assertNull(HerdrWire.decodeEvent("""{"event":42}"""))
    }
}
