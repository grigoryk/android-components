/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.samples.fxa

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.support.customtabs.CustomTabsIntent
import android.view.View
import android.content.Intent
import android.content.pm.PackageManager
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.widget.CheckBox
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import mozilla.components.concept.sync.Profile
import mozilla.components.feature.qr.QrFeature
import mozilla.components.service.fxa.FirefoxAccount
import mozilla.components.service.fxa.FxaException
import mozilla.components.service.fxa.Config
import mozilla.components.support.base.log.Log
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.base.log.sink.AndroidLogSink
import mozilla.components.support.ktx.android.content.isPermissionGranted
import kotlin.coroutines.CoroutineContext

@Suppress("TooManyFunctions")
class MainActivity : AppCompatActivity(), ActivityCompat.OnRequestPermissionsResultCallback, LoginFragment.OnLoginCompleteListener, CoroutineScope{
    private lateinit var account: FirefoxAccount
    private var scopesWithoutKeys: Array<String> = arrayOf("profile")
    private var scopesWithKeys: Array<String> = arrayOf("profile", "https://identity.mozilla.com/apps/oldsync")
    private var scopes: Array<String> = scopesWithoutKeys
    private var wantsKeys: Boolean = false

    private val logger = Logger("SamplesFirefoxAccounts")

    private lateinit var qrFeature: QrFeature

    private lateinit var job: Job
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    companion object {
        const val CLIENT_ID = "12cc4070a481bc73"
        const val REDIRECT_URL = "fxaclient://android.redirect"
        const val CONFIG_URL = "https://latest.dev.lcip.org"
        const val FXA_STATE_PREFS_KEY = "fxaAppState"
        const val FXA_STATE_KEY = "fxaState"
        private const val FENNEC_AUTH_PERMISSION = "org.mozilla.fennec_grisha_fxaccount.permission.AUTH_READ_PROVIDER"
        private const val REQUEST_CODE_CAMERA_PERMISSIONS = 1
        private const val REQUEST_CODE_FENNEC_AUTH_PERMISSION = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.addSink(AndroidLogSink())

        setContentView(R.layout.activity_main)
        job = Job()
        account = initAccount()

        qrFeature = QrFeature(
            this,
            fragmentManager = supportFragmentManager,
            onNeedToRequestPermissions = { permissions ->
                ActivityCompat.requestPermissions(this, permissions, REQUEST_CODE_CAMERA_PERMISSIONS)
            },
            onScanResult = { pairingUrl ->
                val config = Config(CONFIG_URL, CLIENT_ID, REDIRECT_URL)
                val acct = FirefoxAccount(config)
                launch {
                    try {
                        val url = acct.beginPairingFlow(pairingUrl, scopes).await()
                        openWebView(url)
                    } catch (e: FxaException) {
                        Log.log(Log.Priority.ERROR, "mozac-samples-fxa", e, "Pairing flow failed for $pairingUrl")
                    }
                }
            }
        )

        lifecycle.addObserver(qrFeature)

        findViewById<View>(R.id.buttonCustomTabs).setOnClickListener {
            launch {
                val url = account.beginOAuthFlow(scopes, wantsKeys).await()
                openTab(url)
            }
        }

        findViewById<View>(R.id.buttonWebView).setOnClickListener {
            launch {
                val url = account.beginOAuthFlow(scopes, wantsKeys).await()
                openWebView(url)
            }
        }

        findViewById<View>(R.id.buttonPair).setOnClickListener {
            qrFeature.scan()
        }

        findViewById<View>(R.id.buttonLogout).setOnClickListener {
            getSharedPreferences(FXA_STATE_PREFS_KEY, Context.MODE_PRIVATE).edit().putString(FXA_STATE_KEY, "").apply()
            val txtView: TextView = findViewById(R.id.txtView)
            txtView.text = getString(R.string.logged_out)
        }

        findViewById<View>(R.id.buttonFennec).setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, FENNEC_AUTH_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                logger.debug("Don't have the Fennec Auth Read persmission. Requesting...")
                ActivityCompat.requestPermissions(this, arrayOf(FENNEC_AUTH_PERMISSION), REQUEST_CODE_FENNEC_AUTH_PERMISSION)
            } else {
                readFennecAuthState()
            }

        }

        findViewById<CheckBox>(R.id.checkboxKeys).setOnCheckedChangeListener { _, isChecked ->
            wantsKeys = isChecked
            scopes = if (isChecked) scopesWithKeys else scopesWithoutKeys
        }
    }

    private fun readFennecAuthState() {
        val fennecAuthAuthority = "org.mozilla.fennec_grisha.fxa.auth"
        val fennecAuthAuthorityUri = Uri.parse("content://$fennecAuthAuthority")
        val fennecAuthStateUri = Uri.withAppendedPath(fennecAuthAuthorityUri, "state")
        val fennecAuthContentProvider = applicationContext.contentResolver
                .acquireContentProviderClient(fennecAuthAuthority)

        val txtView: TextView = findViewById(R.id.txtView)

        fennecAuthContentProvider.use { client ->
            logger.debug("Got Fennec auth client: $client")

            if (client == null) {
                txtView.text = getString(R.string.fennec_info, "doesn't support FxA state querying")
                return@use
            }

            client.query(fennecAuthStateUri,
                    arrayOf("email", "sessionToken", "kSync", "kXSCS"),
                    null, null, null, null
            ).use cursorProcessing@{ cursor ->

                if (cursor == null) {
                    txtView.text = getString(R.string.fennec_info, "signed-out")
                    return@cursorProcessing
                }

                cursor.moveToFirst()

                val email = cursor.getString(cursor.getColumnIndex("email"))
                val sessionToken = cursor.getBlob(cursor.getColumnIndex("sessionToken"))
                val kSync = cursor.getBlob(cursor.getColumnIndex("kSync"))
                val kXSCS = cursor.getString(cursor.getColumnIndex("kXSCS"))
                txtView.text = getString(R.string.fennec_info, "$email\n\n$sessionToken\n\n$kSync\n\n$kXSCS")
            }
        }
    }

    private fun initAccount(): FirefoxAccount {
        getAuthenticatedAccount()?.let {
            launch {
                val profile = it.getProfile(true).await()
                displayProfile(profile)
            }
            return it
        }

        val config = Config(CONFIG_URL, CLIENT_ID, REDIRECT_URL)
        return FirefoxAccount(config)
    }

    override fun onDestroy() {
        super.onDestroy()
        account.close()
        job.cancel()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val action = intent.action
        val data = intent.dataString

        if (Intent.ACTION_VIEW == action && data != null) {
            val url = Uri.parse(data)
            val code = url.getQueryParameter("code")!!
            val state = url.getQueryParameter("state")!!
            displayAndPersistProfile(code, state)
        }
    }

    override fun onLoginComplete(code: String, state: String, fragment: LoginFragment) {
        displayAndPersistProfile(code, state)
        supportFragmentManager?.popBackStack()
    }

    private fun getAuthenticatedAccount(): FirefoxAccount? {
        val savedJSON = getSharedPreferences(FXA_STATE_PREFS_KEY, Context.MODE_PRIVATE).getString(FXA_STATE_KEY, "")
        return savedJSON?.let {
            try {
                FirefoxAccount.fromJSONString(it)
            } catch (e: FxaException) {
                null
            }
        }
    }

    private fun openTab(url: String) {
        val customTabsIntent = CustomTabsIntent.Builder()
                .addDefaultShareMenuItem()
                .setShowTitle(true)
                .build()

        customTabsIntent.intent.data = Uri.parse(url)
        customTabsIntent.launchUrl(this@MainActivity, Uri.parse(url))
    }

    private fun openWebView(url: String) {
        supportFragmentManager?.beginTransaction()?.apply {
            replace(R.id.container, LoginFragment.create(url, REDIRECT_URL))
            addToBackStack(null)
            commit()
        }
    }

    private fun displayAndPersistProfile(code: String, state: String) {
        launch {
            account.completeOAuthFlow(code, state).await()
            val profile = account.getProfile().await()
            displayProfile(profile)
            account.toJSONString().let {
                getSharedPreferences(FXA_STATE_PREFS_KEY, Context.MODE_PRIVATE)
                        .edit().putString(FXA_STATE_KEY, it).apply()
            }
        }
    }

    private fun displayProfile(profile: Profile) {
        val txtView: TextView = findViewById(R.id.txtView)
        txtView.text = getString(R.string.signed_in, "${profile.displayName ?: ""} ${profile.email}")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            REQUEST_CODE_CAMERA_PERMISSIONS -> qrFeature.onPermissionsResult(permissions, grantResults)
            REQUEST_CODE_FENNEC_AUTH_PERMISSION -> {
                if (applicationContext.isPermissionGranted(FENNEC_AUTH_PERMISSION)) {
                    readFennecAuthState()
                }
            }
        }
    }

    override fun onBackPressed() {
        if (!qrFeature.onBackPressed()) {
            super.onBackPressed()
        }
    }
}
