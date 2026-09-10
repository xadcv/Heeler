package dev.bybee.heeler.herdr

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * Canonical event kind: the dotted name (`pane.created`) as herdr's
 * subscription API spells it. Event lines arrive snake_case (`pane_created`);
 * [fromWireName] maps either spelling here so consumers see one naming.
 */
public data class HerdrEventKind(val name: String) {
    public companion object {
        /**
         * `pane.output_matched` is subscribable only with a full output-match
         * query; the app does not subscribe to it, but its events still decode
         * canonically.
         */
        public val paneOutputMatched: HerdrEventKind = HerdrEventKind("pane.output_matched")

        /**
         * Synthetic, local-only kind: stands in for updates a bounded event
         * buffer shed under overflow. Never sent by herdr and not subscribable
         * (absent from [known]). Consumers treat it like a membership event:
         * re-snapshot, because deltas may be missing.
         */
        public val eventsDropped: HerdrEventKind = HerdrEventKind("local.events_dropped")

        /** All kinds herdr's schema declares subscribable. */
        public val known: List<HerdrEventKind> =
            GlobalEventKind.entries.map { it.kind } + PaneEventKind.entries.map { it.kind } + paneOutputMatched

        private val byWireName: Map<String, HerdrEventKind> = buildMap {
            for (kind in known) {
                put(kind.name, kind)
                put(kind.name.replace('.', '_'), kind)
            }
        }

        /**
         * Maps a wire spelling (snake_case event line, or dotted) onto the
         * canonical kind. Unknown names pass through untouched: herdr's API has
         * no stability guarantee, and an unknown kind must not be
         * guess-mangled or kill the stream.
         */
        public fun fromWireName(wireName: String): HerdrEventKind =
            byWireName[wireName] ?: HerdrEventKind(wireName)
    }
}

/** Host-wide subscription kinds, subscribed with no params. */
public enum class GlobalEventKind(public val wireName: String) {
    WORKSPACE_CREATED("workspace.created"),
    WORKSPACE_UPDATED("workspace.updated"),
    WORKSPACE_METADATA_UPDATED("workspace.metadata_updated"),
    WORKSPACE_RENAMED("workspace.renamed"),
    WORKSPACE_MOVED("workspace.moved"),

    /** Added in protocol 19 (#140); subscribe only once the Host reports 19+. */
    WORKSPACE_REORDERED("workspace.reordered"),
    WORKSPACE_CLOSED("workspace.closed"),
    WORKSPACE_FOCUSED("workspace.focused"),
    WORKTREE_CREATED("worktree.created"),
    WORKTREE_OPENED("worktree.opened"),
    WORKTREE_REMOVED("worktree.removed"),
    TAB_CREATED("tab.created"),
    TAB_CLOSED("tab.closed"),
    TAB_FOCUSED("tab.focused"),
    TAB_RENAMED("tab.renamed"),
    TAB_MOVED("tab.moved"),
    PANE_CREATED("pane.created"),
    PANE_CLOSED("pane.closed"),
    PANE_UPDATED("pane.updated"),
    PANE_FOCUSED("pane.focused"),
    PANE_MOVED("pane.moved"),
    PANE_EXITED("pane.exited"),
    PANE_AGENT_DETECTED("pane.agent_detected"),
    LAYOUT_UPDATED("layout.updated"),
    ;

    /** The canonical kind, for matching against incoming events. */
    public val kind: HerdrEventKind get() = HerdrEventKind(wireName)

    public companion object {
        /**
         * The membership kinds the Console re-snapshots on. Deliberately
         * excludes `pane.updated`, which fires on every terminal-title change
         * (34 events in 6 s measured live) and is not a resync trigger.
         */
        public val membership: Set<GlobalEventKind> = setOf(
            WORKSPACE_CREATED, WORKSPACE_RENAMED, WORKSPACE_MOVED, WORKSPACE_REORDERED,
            WORKSPACE_CLOSED, WORKTREE_CREATED, WORKTREE_OPENED, WORKTREE_REMOVED,
            TAB_CREATED, TAB_CLOSED, TAB_RENAMED, TAB_MOVED,
            PANE_CREATED, PANE_CLOSED, PANE_MOVED, PANE_EXITED, PANE_AGENT_DETECTED,
        )
    }
}

/** Pane-scoped subscription kinds: their subscriptions carry a `pane_id`. */
public enum class PaneEventKind(public val wireName: String) {
    AGENT_STATUS_CHANGED("pane.agent_status_changed"),
    SCROLL_CHANGED("pane.scroll_changed"),
    ;

    public val kind: HerdrEventKind get() = HerdrEventKind(wireName)
}

/**
 * One entry in an `events.subscribe` request. The request is all-or-nothing
 * on the server: one [Pane] entry naming a dead pane fails the whole request
 * with `pane_not_found`, so pane-scoped entries must be snapshot-derived and
 * never outlive the connection they were taken on.
 */
public sealed interface EventSubscription {
    public data class Global(val kind: GlobalEventKind) : EventSubscription
    public data class Pane(val kind: PaneEventKind, val paneID: String) : EventSubscription
}

/** One event from the Host's events channel, in canonical naming. */
public data class HerdrEvent(
    val kind: HerdrEventKind,
    /**
     * Raw payload: only 3 of 26 kinds are typed in herdr's schema, so the
     * payload stays schema-free and consumers pick the fields they know.
     */
    val data: JsonElement,
) {
    public companion object {
        /** The drop marker yielded in place of updates a bounded buffer shed. */
        public val eventsDropped: HerdrEvent = HerdrEvent(HerdrEventKind.eventsDropped, JsonNull)
    }
}

/**
 * A live `events.subscribe` stream over its Host's dedicated forwarding
 * channel. [events] completes normally after [end], and fails if the channel
 * dies remotely. Ending is explicit: abandoning the stream without [end]
 * leaves the channel live until the SSH connection closes.
 */
public interface HerdrEventStream {
    /**
     * Buffer bound for the delivery path, sized for stall absorption, not
     * history. Overflow sheds the oldest events and yields
     * [HerdrEvent.eventsDropped] so the consumer knows to re-snapshot.
     */
    public val events: Flow<HerdrEvent>

    /** Closes the events channel and waits for its teardown. Idempotent. */
    public suspend fun end()

    public companion object {
        public const val BUFFER_LIMIT: Int = 256
    }
}
