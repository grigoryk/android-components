/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.concept.storage

import android.arch.lifecycle.LiveData
import mozilla.components.concept.storage.history.Meta
import mozilla.components.concept.storage.history.Page
import mozilla.components.concept.storage.history.Visit

/**
 * A lightweight, CQRS-like interface for history.
 */
interface History {
    // Write
    fun observePageVisit(url: String): Visit
    fun observeMetaForVisit(visit: Visit, meta: Meta)

    // Read
    fun recentHistory(): LiveData<List<Page>>
    fun topHistory(): LiveData<List<Page>>
    fun search(keyword: String): List<Page>
}
