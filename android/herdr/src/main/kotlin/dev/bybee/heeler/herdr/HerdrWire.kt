package dev.bybee.heeler.herdr

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/**
 * herdr's NDJSON wire format: request `{"id","method","params"}` + "\n",
 * success `{"id","result"}`, failure `{"id","error":{"code","message"}}`.
 * One request per connection; decoding is lenient (unknown fields ignored)
 * because herdr's API has no stability guarantee.
 */
public object HerdrWire {
    /** Encodes a parameterless request line; herdr requires `params`, and `{}` satisfies it. */
    public fun requestLine(id: String, method: String): String =
        requestLine(id, method, JsonObject(emptyMap()))

    /** Encodes a request line with a typed params payload, trailing newline included. */
    public fun <P> requestLine(id: String, method: String, params: P, serializer: SerializationStrategy<P>): String =
        requestLine(id, method, HerdrJson.encodeToJsonElement(serializer, params))

    public inline fun <reified P> requestLine(id: String, method: String, params: P): String =
        requestLine(id, method, params, serializer<P>())

    /** Encodes a request line from an already-built params element. */
    public fun requestLine(id: String, method: String, params: JsonElement): String {
        val request = buildJsonObject {
            put("id", id)
            put("method", method)
            put("params", params)
        }
        return HerdrJson.encodeToString(JsonElement.serializer(), request) + "\n"
    }

    /**
     * Encodes an `events.subscribe` request line. Kinds go out in their
     * canonical dotted spelling; pane-scoped entries carry the pane id.
     */
    public fun subscribeRequestLine(id: String, subscriptions: List<EventSubscription>): String {
        val entries = subscriptions.map { subscription ->
            buildJsonObject {
                when (subscription) {
                    is EventSubscription.Global -> put("type", subscription.kind.wireName)
                    is EventSubscription.Pane -> {
                        put("type", subscription.kind.wireName)
                        put("pane_id", subscription.paneID)
                    }
                }
            }
        }
        return requestLine(id, "events.subscribe", buildJsonObject { put("subscriptions", JsonArray(entries)) })
    }

    /**
     * Decodes one events-channel line. Returns null for anything that is not
     * an event line: junk on the stream is dropped, never fatal.
     */
    public fun decodeEvent(line: String): HerdrEvent? {
        val element = try {
            HerdrJson.parseToJsonElement(line)
        } catch (_: SerializationException) {
            return null
        }
        val obj = element as? JsonObject ?: return null
        val event = (obj["event"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return HerdrEvent(HerdrEventKind.fromWireName(event), obj["data"] ?: JsonNull)
    }

    /**
     * Decodes one response line: unwraps the envelope, checks id correlation,
     * and either returns the result or throws the server's error as
     * [TransportError.ApiRejected].
     *
     * Each herdr API connection serves one request, so the response on a
     * connection is always for that connection's sole in-flight request.
     * Success results still require an exact id match. Errors do not: herdr
     * 0.8.0 answers unparseable requests with `id: ""`, and `events.subscribe`
     * probe failures use a derived id (`<id>:sub:<i>:probe`) that does not
     * echo the request id. Without this fallback those requests would pend
     * until the deadline instead of failing with the server's error (#177).
     */
    public fun <R> decodeResult(deserializer: DeserializationStrategy<R>, responseLine: String, requestID: String): R {
        val line = responseLine.substringBefore('\n')
        val envelope = try {
            HerdrJson.parseToJsonElement(line).jsonObject
        } catch (e: SerializationException) {
            throw TransportError.MalformedResponse("undecodable response line: ${line.take(200)}")
        } catch (e: IllegalArgumentException) {
            throw TransportError.MalformedResponse("undecodable response line: ${line.take(200)}")
        }
        envelope["error"]?.let { error ->
            if (error !is JsonNull) throw decodeError(error)
        }
        val id = (envelope["id"] as? JsonPrimitive)?.contentOrNull
        if (id != requestID) {
            throw TransportError.MalformedResponse(
                "response id ${id ?: "<none>"} does not match request id $requestID",
            )
        }
        val result = envelope["result"]?.takeUnless { it is JsonNull }
            ?: throw TransportError.MalformedResponse("response has neither result nor error")
        return try {
            HerdrJson.decodeFromJsonElement(deserializer, result)
        } catch (e: SerializationException) {
            throw TransportError.MalformedResponse("result does not match ${deserializer.descriptor.serialName}: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw TransportError.MalformedResponse("result does not match ${deserializer.descriptor.serialName}: ${e.message}")
        }
    }

    public inline fun <reified R> decodeResult(responseLine: String, requestID: String): R =
        decodeResult(serializer<R>(), responseLine, requestID)

    /** herdr error envelopes carry `code` as a string or, on some paths, an integer. */
    private fun decodeError(error: JsonElement): TransportError {
        val obj = error as? JsonObject
            ?: return TransportError.MalformedResponse("error envelope is not an object")
        val code = (obj["code"] as? JsonPrimitive)?.content
            ?: return TransportError.MalformedResponse("error envelope has no code")
        val message = (obj["message"] as? JsonPrimitive)?.contentOrNull ?: ""
        return TransportError.ApiRejected(code, message)
    }
}
