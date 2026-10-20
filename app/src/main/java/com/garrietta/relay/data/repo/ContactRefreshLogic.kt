package com.garrietta.relay.data.repo

/**
 * Resume-time contact refresh decision (Garrett 2026-10-20): "Yes —
 * refresh the name/button when I return from Contacts."
 *
 * The chat header's "Add contact?" button (Build #179) fires the
 * system Contacts app's new-contact page; Garrett saves the contact
 * there and returns to Relay. Two caches stood in the way of the open
 * chat noticing: the repository's ContactLookup memoizes the
 * UNRESOLVED result (number-as-name, no photo) for the process
 * lifetime, and participant resolution runs at load — nothing
 * re-read the address book when Relay came back to the foreground.
 *
 * The fix's shape: when Relay returns from the background,
 * [UnifiedInboxRepository.invalidateContactCache] drops the lookup's
 * cached entries and bumps a change tick; open chats collect the tick
 * and re-run ONLY their contact-derived resolution (participants,
 * header theme/name overlays) — no history reload, no re-sync. This
 * object is the one pure decision in that chain: whether a resume is
 * worth broadcasting at all. When the lookup holds nothing, there is
 * no stale resolution anywhere, so the resume is a true no-op — no
 * tick, no re-resolution pass in any open chat. Pure JVM logic;
 * unit-tested (ContactRefreshLogicTest).
 */
object ContactRefreshLogic {

    /**
     * Whether clearing the contact lookup would drop anything: only
     * a lookup that has actually memoized resolutions (names or
     * photos, resolved or unresolved — the unresolved entries are
     * precisely the stale ones) can be holding stale state. An empty
     * lookup (cold process, nothing read yet, lookups never ran)
     * means every future read resolves fresh anyway.
     */
    fun shouldInvalidate(cachedEntryCount: Int): Boolean = cachedEntryCount > 0
}
