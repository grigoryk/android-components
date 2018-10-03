package mozilla.components.concept.storage.history

interface Page {
    val url: String

    /**
     * Visits for this page, ordered by time in descending order (most recent first).
     */
    val visits: List<Visit>
    val frecency: Long

    val latestMeta: Meta
    get() {
        return visits.first().meta
    }
}