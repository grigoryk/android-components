/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.concept.storage

data class HistoryMetadata(
    val url: String,
    val title: String?,
    val firstViewTime: Long,
    val lastViewTime: Long,
    val totalViewTime: Int,
    val searchTerm: String?,
    val isMedia: Boolean,
    val parentDomain: String?
)

interface HistoryMetadataStorage {
    suspend fun getLatestHistoryMetadataForUrl(url: String): HistoryMetadata?
    suspend fun getHistoryMetadataViewedSince(timestamp: Long): List<HistoryMetadata>
    suspend fun getHistoryMetadataViewedBetween(start: Long, end: Long): List<HistoryMetadata>
    suspend fun queryHistoryMetadata(query: String, limit: Int): List<HistoryMetadata>

    suspend fun addHistoryMetadata(metadata: HistoryMetadata)
}
