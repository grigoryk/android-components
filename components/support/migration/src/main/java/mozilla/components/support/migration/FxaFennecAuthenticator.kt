package mozilla.components.support.migration

import android.app.Service
import android.content.Intent
import android.os.IBinder

abstract class FxAccountAuthenticatorService : Service() {
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
