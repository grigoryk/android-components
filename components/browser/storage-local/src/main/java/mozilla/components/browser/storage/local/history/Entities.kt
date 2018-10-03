/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.browser.storage.local.history

import android.arch.persistence.room.*

const val VISIT_TYPE_DEFAULT: Int = 0

@Entity(
        tableName = "pages",
        indices = [Index(name = "page_url_unique_index", unique = true, value = ["url"])]
)
data class Page (
        @PrimaryKey(autoGenerate = true) val id: Long,
        val url: String,
        val frecency: Long = 0
)

@Entity(
        tableName = "visits",
        foreignKeys = [
            ForeignKey(entity = Page::class, parentColumns = ["id"], childColumns = ["page_id"],
                    onDelete = ForeignKey.CASCADE)
        ]
)
data class Visit (
    @PrimaryKey(autoGenerate = true) val id: Long,
    val page_id: Long,
    val timestamp: Long,
    val type: Int = VISIT_TYPE_DEFAULT
)

@Entity(
        tableName = "meta",
        foreignKeys = [
            ForeignKey(entity = Visit::class, parentColumns = ["id"], childColumns = ["visit_id"],
                    onDelete = ForeignKey.CASCADE)
        ])
data class Meta (
    @PrimaryKey(autoGenerate = true) val id: Long,
    val visit_id: Long,
    val title: String
)
