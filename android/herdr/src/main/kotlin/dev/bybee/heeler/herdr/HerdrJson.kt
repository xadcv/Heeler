package dev.bybee.heeler.herdr

import kotlinx.serialization.json.Json

/**
 * The one `Json` configuration every herdr wire type is encoded and decoded
 * with. Lenient by construction: herdr's API has no stability guarantee, so
 * unknown fields are ignored instead of failing a decode. Absent optionals
 * are omitted on encode, matching the Swift `JSONEncoder` output the herdr
 * server has been verified against (a `null` `name` on `agent.rename`
 * clears the name exactly like an omitted one).
 */
public val HerdrJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
}
