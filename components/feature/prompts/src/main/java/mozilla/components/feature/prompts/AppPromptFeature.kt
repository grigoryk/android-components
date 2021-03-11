/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.prompts

import androidx.annotation.VisibleForTesting
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import mozilla.components.browser.state.selector.findTabOrCustomTabOrSelectedTab
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.prompt.AppPromptRequest
import mozilla.components.concept.storage.LoginValidationDelegate
import mozilla.components.feature.prompts.dialog.PromptDialogFragment
import mozilla.components.feature.prompts.dialog.Prompter
import mozilla.components.feature.prompts.dialog.SaveLoginDialogFragment
import mozilla.components.feature.prompts.login.LoginExceptions
import mozilla.components.feature.prompts.login.LoginPicker
import mozilla.components.feature.prompts.login.LoginPickerView
import mozilla.components.lib.state.ext.flowScoped
import mozilla.components.support.base.feature.LifecycleAwareFeature
import mozilla.components.support.ktx.kotlinx.coroutines.flow.ifAnyChanged

class AppPromptFeature(
    private val store: BrowserStore,
    private val loginPrompter: LoginPrompter? = null,
    private val sessionId: String
) : LifecycleAwareFeature, Prompter {
    private var handlePromptScope: CoroutineScope? = null

    @VisibleForTesting
    var activePromptRequest: AppPromptRequest? = null

    override fun start() {
        handlePromptScope = store.flowScoped { flow ->
            flow.map { state -> state.findTabOrCustomTabOrSelectedTab(sessionId) }
            .ifAnyChanged {
                arrayOf(it?.content?.appPromptRequest, it?.content?.loading)
            }
            .collect { state ->
                state?.content?.let {
                    if (it.appPromptRequest != activePromptRequest) {
                        (activePromptRequest as AppPromptRequest.SelectLogin).let { prompt ->
                            loginPrompter?.dismissSelectLoginPrompt(prompt)
                        }
                        onPromptRequested(state)
                    } else if (it.loading) {
                        (it.appPromptRequest as AppPromptRequest.SelectLogin).let { prompt ->
                            loginPrompter?.dismissSelectLoginPrompt(prompt)
                        }
                    }
                    activePromptRequest = it.appPromptRequest
                }
            }
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun onPromptRequested(session: SessionState) {
        session.content.appPromptRequest?.let { promptRequest ->
            when (promptRequest) {
                is AppPromptRequest.SelectLogin -> {
                    loginPrompter?.handleSelectLoginRequest(promptRequest)
                }
                is AppPromptRequest.SaveLogin -> {
                    loginPrompter?.handleRequest(session, promptRequest)
                }
            }
        }
    }

    override fun stop() {
        TODO("Not yet implemented")
    }

    override fun onCancel(sessionId: String) {
        TODO("Not yet implemented")
    }

    override fun onConfirm(sessionId: String, value: Any?) {
        TODO("Not yet implemented")
    }

    override fun onClear(sessionId: String) {
        TODO("Not yet implemented")
    }
}


/**
 * @property validationDelegate Delegate used to access login storage. If null,
 * 'save login'prompts will not be shown.
 * @property exceptionStorage An implementation of [LoginExceptions] that saves and checks origins
 * the user does not want to see a save login dialog for.
 * @property isSaveLoginEnabled A callback invoked when a login prompt is triggered. If false,
 * 'save login'prompts will not be shown.
 * @property pickerView The [LoginPickerView] used for [LoginPicker] to display select login options.
 * @property onManageLogins A callback invoked when a user selects "manage logins" from the
 * select login prompt.
 */
class LoginPrompter(
    store: BrowserStore,
    private val validationDelegate: LoginValidationDelegate,
    private val exceptionStorage: LoginExceptions,
    private val pickerView: LoginPickerView,
    sessionId: String?,
    private val isSaveLoginEnabled: () -> Boolean = { false },
    onManageLogins: () -> Unit = {}
) {
    private val loginPicker = LoginPicker(store, pickerView, onManageLogins, sessionId)

    internal fun handleRequest(session: SessionState, promptRequest: AppPromptRequest.SaveLogin): PromptDialogFragment? {
        if (!isSaveLoginEnabled.invoke()) return null

        return SaveLoginDialogFragment.newInstance(
            loginExceptionStorage = exceptionStorage,
            loginValidationDelegate = validationDelegate,
            sessionId = session.id,
            hint = promptRequest.hint,
            // For v1, we only handle a single login and drop all others on the floor
            login = promptRequest.logins[0],
            icon = session.content.icon
        )
    }

    fun handleSelectLoginRequest(prompt: AppPromptRequest.SelectLogin) {
        if (prompt.logins.isEmpty()) {
            return
        }
        loginPicker.handleSelectLoginRequest(prompt)
    }

    /**
     * Dismisses the select login prompt if it is active and visible.
     * @returns true if dismissCurrentLoginSelect is called otherwise false.
     */
    @VisibleForTesting
    fun dismissSelectLoginPrompt(prompt: AppPromptRequest.SelectLogin): Boolean {
        if (pickerView.asView().isVisible) {
            loginPicker.dismissCurrentLoginSelect(prompt)
            return true
        }
        return false
    }
}
