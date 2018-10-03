package mozilla.components.browser.storage.local

import mozilla.components.concept.engine.history.HistoryTrackingDelegate

class LocalHistoryTrackingDelegate : HistoryTrackingDelegate {
    override fun onVisited(uri: String, isReload: Boolean?) {
        TODO("not implemented")
    }

    override fun onTitleChanged(uri: String, title: String) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun getVisited(uri: List<String>, callback: (List<Boolean>) -> Unit) {
        TODO("not implemented")
    }

    override fun getVisited(callback: (List<String>) -> Unit) {
        TODO("not implemented")
    }
}