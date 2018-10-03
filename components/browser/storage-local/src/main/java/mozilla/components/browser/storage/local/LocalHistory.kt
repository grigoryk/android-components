/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.browser.storage.local

import android.arch.lifecycle.LiveData
import android.arch.persistence.room.Dao
import android.arch.persistence.room.Insert
import android.arch.persistence.room.Query
import android.arch.persistence.room.Transaction
import mozilla.components.concept.storage.History
import mozilla.components.concept.storage.history.Meta
import mozilla.components.concept.storage.history.Page
import mozilla.components.concept.storage.history.Visit
import mozilla.components.support.base.observer.Observable
import mozilla.components.support.base.observer.ObserverRegistry

/**
 * This class provides access to a centralized registry of all active sessions.
 */
class LocalHistory : History {
    override fun observePageVisit(url: String): Visit {
        TODO("not implemented")
    }

    override fun observeMetaForVisit(visit: Visit, meta: Meta) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun recentHistory(): LiveData<List<Page>> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun topHistory(): LiveData<List<Page>> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun search(keyword: String): List<Page> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}
