/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.prompts

import android.app.Activity
import android.content.Intent
import androidx.annotation.VisibleForTesting
import androidx.annotation.VisibleForTesting.PRIVATE
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.selector.findTabOrCustomTab
import mozilla.components.browser.state.selector.findTabOrCustomTabOrSelectedTab
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.prompt.Choice
import mozilla.components.concept.engine.prompt.WebPromptRequest
import mozilla.components.concept.engine.prompt.WebPromptRequest.Alert
import mozilla.components.concept.engine.prompt.WebPromptRequest.Authentication
import mozilla.components.concept.engine.prompt.WebPromptRequest.BeforeUnload
import mozilla.components.concept.engine.prompt.WebPromptRequest.Color
import mozilla.components.concept.engine.prompt.WebPromptRequest.Confirm
import mozilla.components.concept.engine.prompt.DismissiblePrompt
import mozilla.components.concept.engine.prompt.WebPromptRequest.File
import mozilla.components.concept.engine.prompt.WebPromptRequest.MenuChoice
import mozilla.components.concept.engine.prompt.WebPromptRequest.MultipleChoice
import mozilla.components.concept.engine.prompt.WebPromptRequest.Popup
import mozilla.components.concept.engine.prompt.WebPromptRequest.Repost
import mozilla.components.concept.engine.prompt.WebPromptRequest.Share
import mozilla.components.concept.engine.prompt.WebPromptRequest.SingleChoice
import mozilla.components.concept.engine.prompt.WebPromptRequest.TextPrompt
import mozilla.components.concept.engine.prompt.WebPromptRequest.TimeSelection
import mozilla.components.concept.storage.Login
import mozilla.components.concept.storage.LoginValidationDelegate
import mozilla.components.feature.prompts.dialog.AlertDialogFragment
import mozilla.components.feature.prompts.dialog.AuthenticationDialogFragment
import mozilla.components.feature.prompts.dialog.ChoiceDialogFragment
import mozilla.components.feature.prompts.dialog.ChoiceDialogFragment.Companion.MENU_CHOICE_DIALOG_TYPE
import mozilla.components.feature.prompts.dialog.ChoiceDialogFragment.Companion.MULTIPLE_CHOICE_DIALOG_TYPE
import mozilla.components.feature.prompts.dialog.ChoiceDialogFragment.Companion.SINGLE_CHOICE_DIALOG_TYPE
import mozilla.components.feature.prompts.dialog.ColorPickerDialogFragment
import mozilla.components.feature.prompts.dialog.ConfirmDialogFragment
import mozilla.components.feature.prompts.dialog.LoginPrompter
import mozilla.components.feature.prompts.dialog.MultiButtonDialogFragment
import mozilla.components.feature.prompts.dialog.PromptAbuserDetector
import mozilla.components.feature.prompts.dialog.PromptDialogFragment
import mozilla.components.feature.prompts.dialog.Prompter
import mozilla.components.feature.prompts.dialog.SaveLoginDialogFragment
import mozilla.components.feature.prompts.dialog.TextPromptDialogFragment
import mozilla.components.feature.prompts.dialog.TimePickerDialogFragment
import mozilla.components.feature.prompts.file.FilePicker
import mozilla.components.feature.prompts.login.LoginExceptions
import mozilla.components.feature.prompts.login.LoginPicker
import mozilla.components.feature.prompts.login.LoginPickerView
import mozilla.components.feature.prompts.share.DefaultShareDelegate
import mozilla.components.feature.prompts.share.ShareDelegate
import mozilla.components.lib.state.ext.flowScoped
import mozilla.components.support.base.feature.ActivityResultHandler
import mozilla.components.support.base.feature.LifecycleAwareFeature
import mozilla.components.support.base.feature.OnNeedToRequestPermissions
import mozilla.components.support.base.feature.PermissionsFeature
import mozilla.components.support.base.feature.UserInteractionHandler
import mozilla.components.support.ktx.kotlinx.coroutines.flow.ifAnyChanged
import java.lang.ref.WeakReference
import java.security.InvalidParameterException
import java.util.Date

@VisibleForTesting(otherwise = PRIVATE)
internal const val FRAGMENT_TAG = "mozac_feature_prompt_dialog"


/**
 * Feature for displaying native dialogs for html elements like: input type
 * date, file, time, color, option, menu, authentication, confirmation and alerts.
 *
 * There are some requests that are handled with intents instead of dialogs,
 * like file choosers and others. For this reason, you have to keep the feature
 * aware of the flow of requesting data from other apps, overriding
 * onActivityResult in your [Activity] or [Fragment] and forward its calls
 * to [onActivityResult].
 *
 * This feature will subscribe to the currently selected session and display
 * a suitable native dialog based on [Session.Observer.onPromptRequested] events.
 * Once the dialog is closed or the user selects an item from the dialog
 * the related [WebPromptRequest] will be consumed.
 *
 * @property container The [Activity] or [Fragment] which hosts this feature.
 * @property store The [BrowserStore] this feature should subscribe to.
 * @property customTabId Optional id of a custom tab. Instead of showing context
 * menus for the currently selected tab this feature will show only context menus
 * for this custom tab if an id is provided.
 * @property fragmentManager The [FragmentManager] to be used when displaying
 * a dialog (fragment).
 * @property shareDelegate Delegate used to display share sheet.
 * @property onNeedToRequestPermissions A callback invoked when permissions
 * need to be requested before a prompt (e.g. a file picker) can be displayed.
 * Once the request is completed, [onPermissionsResult] needs to be invoked.
 */
@Suppress("TooManyFunctions", "LargeClass", "LongParameterList")
class WebPromptFeature private constructor(
    private val container: PromptContainer,
    private val store: BrowserStore,
    private var customTabId: String?,
    private val fragmentManager: FragmentManager,
    private val loginPromptHandler: AppPromptFeature?,
    private val shareDelegate: ShareDelegate,
    onNeedToRequestPermissions: OnNeedToRequestPermissions
) : LifecycleAwareFeature, PermissionsFeature, Prompter, ActivityResultHandler, UserInteractionHandler {
    // These three scopes have identical lifetimes. We do not yet have a way of combining scopes
    private var handlePromptScope: CoroutineScope? = null
    private var dismissPromptScope: CoroutineScope? = null
    @VisibleForTesting
    var activePromptRequest: WebPromptRequest? = null

    internal val promptAbuserDetector = PromptAbuserDetector()

    @VisibleForTesting(otherwise = PRIVATE)
    internal var activePrompt: WeakReference<PromptDialogFragment>? = null

    constructor(
        activity: Activity,
        store: BrowserStore,
        customTabId: String? = null,
        fragmentManager: FragmentManager,
        shareDelegate: ShareDelegate = DefaultShareDelegate(),
        loginPromptHandler: AppPromptFeature? = null,
        onNeedToRequestPermissions: OnNeedToRequestPermissions
    ) : this(
        container = PromptContainer.Activity(activity),
        store = store,
        customTabId = customTabId,
        fragmentManager = fragmentManager,
        shareDelegate = shareDelegate,
        loginPromptHandler = loginPromptHandler,
        onNeedToRequestPermissions = onNeedToRequestPermissions
    )

    constructor(
        fragment: Fragment,
        store: BrowserStore,
        customTabId: String? = null,
        fragmentManager: FragmentManager,
        shareDelegate: ShareDelegate = DefaultShareDelegate(),
        loginPromptHandler: AppPromptFeature? = null,
        onNeedToRequestPermissions: OnNeedToRequestPermissions
    ) : this(
        container = PromptContainer.Fragment(fragment),
        store = store,
        customTabId = customTabId,
        fragmentManager = fragmentManager,
        shareDelegate = shareDelegate,
        loginPromptHandler = loginPromptHandler,
        onNeedToRequestPermissions = onNeedToRequestPermissions
    )

    private val filePicker = FilePicker(container, store, customTabId, onNeedToRequestPermissions)

    override val onNeedToRequestPermissions
        get() = filePicker.onNeedToRequestPermissions

    /**
     * Starts observing the selected session to listen for prompt requests
     * and displays a dialog when needed.
     */
    @Suppress("ComplexMethod")
    override fun start() {
        promptAbuserDetector.resetJSAlertAbuseState()

        handlePromptScope = store.flowScoped { flow ->
            flow.map { state -> state.findTabOrCustomTabOrSelectedTab(customTabId) }
                .ifAnyChanged {
                    arrayOf(it?.content?.promptRequest, it?.content?.loading)
                }
                .collect { state ->
                    state?.content?.let {
                        if (it.promptRequest != activePromptRequest) {
                            (activePromptRequest as SelectLoginPrompt).let { prompt ->
                                loginPromptHandler?.dismissSelectLoginPrompt(prompt)
                            }
                            onPromptRequested(state)
                        } else if (!it.loading) {
                            promptAbuserDetector.resetJSAlertAbuseState()
                        } else if (it.loading) {
                            (it.promptRequest as SelectLoginPrompt).let { prompt ->
                                loginPromptHandler?.dismissSelectLoginPrompt(prompt)
                            }
                        }
                        activePromptRequest = it.promptRequest
                    }
                }
        }

        // Dismiss all prompts when page URL or session id changes. See Fenix#5326
        dismissPromptScope = store.flowScoped { flow ->
            flow.ifAnyChanged { state ->
                arrayOf(
                    state.selectedTabId,
                    state.findTabOrCustomTabOrSelectedTab(customTabId)?.content?.url
                )
            }.collect {
                (activePromptRequest as SelectLoginPrompt).let { prompt ->
                    loginPromptHandler?.dismissSelectLoginPrompt(prompt)
                }

                val prompt = activePrompt?.get()
                if (prompt?.shouldDismissOnLoad() == true) {
                    prompt.dismiss()
                }
                activePrompt?.clear()
            }
        }

        fragmentManager.findFragmentByTag(FRAGMENT_TAG)?.let { fragment ->
            // There's still a [PromptDialogFragment] visible from the last time. Re-attach this feature so that the
            // fragment can invoke the callback on this feature once the user makes a selection. This can happen when
            // the app was in the background and on resume the activity and fragments get recreated.
            reattachFragment(fragment as PromptDialogFragment)
        }
    }

    override fun stop() {
        // Stops observing the selected session for incoming prompt requests.
        handlePromptScope?.cancel()
        dismissPromptScope?.cancel()

        // Dismisses the logins prompt so that it can appear on another tab
        (activePromptRequest as SelectLoginPrompt).let { prompt ->
            loginPromptHandler?.dismissSelectLoginPrompt(prompt)
        }
    }

    override fun onBackPressed(): Boolean {
        // TODO handle other types of dialogs
        return (activePromptRequest as SelectLoginPrompt).let { prompt ->
            loginPromptHandler?.dismissSelectLoginPrompt(prompt)
        } ?: false
    }

    /**
     * Notifies the feature of intent results for prompt requests handled by
     * other apps like file chooser requests.
     *
     * @param requestCode The code of the app that requested the intent.
     * @param data The result of the request.
     */
    override fun onActivityResult(requestCode: Int, data: Intent?, resultCode: Int): Boolean {
        return filePicker.onActivityResult(requestCode, resultCode, data)
    }

    /**
     * Notifies the feature that the permissions request was completed. It will then
     * either process or dismiss the prompt request.
     *
     * @param permissions List of permission requested.
     * @param grantResults The grant results for the corresponding permissions
     * @see [onNeedToRequestPermissions].
     */
    override fun onPermissionsResult(permissions: Array<String>, grantResults: IntArray) {
        filePicker.onPermissionsResult(permissions, grantResults)
    }

    /**
     * Invoked when a native dialog needs to be shown.
     *
     * @param session The session which requested the dialog.
     */
    @VisibleForTesting(otherwise = PRIVATE)
    internal fun onPromptRequested(session: SessionState) {
        // Some requests are handle with intents
        session.content.promptRequest?.let { promptRequest ->
            when (promptRequest) {
                is File -> filePicker.handleFileRequest(promptRequest)
                is Share -> handleShareRequest(promptRequest, session)
                is SelectLoginPrompt -> {
                    if (promptRequest.logins.isNotEmpty()) {
                        loginPromptHandler?.handleSelectLoginRequest(promptRequest)
                    }
                }
                else -> handleDialogsRequest(promptRequest, session)
            }
        }
    }

    /**
     * Invoked when a dialog is dismissed. This consumes the [WebPromptFeature]
     * value from the session indicated by [sessionId].
     *
     * @param sessionId this is the id of the session which requested the prompt.
     */
    override fun onCancel(sessionId: String) {
        store.consumePromptFrom(sessionId, activePrompt) {
            when (it) {
                is BeforeUnload -> it.onStay()
                is Dismissible -> it.onDismiss()
                is Popup -> it.onDeny()
            }
        }
    }

    /**
     * Invoked when the user confirms the action on the dialog. This consumes
     * the [WebPromptFeature] value from the [SessionState] indicated by [sessionId].
     *
     * @param sessionId that requested to show the dialog.
     * @param value an optional value provided by the dialog as a result of confirming the action.
     */
    @Suppress("UNCHECKED_CAST", "ComplexMethod")
    override fun onConfirm(sessionId: String, value: Any?) {
        store.consumePromptFrom(sessionId, activePrompt) {
            try {
                when (it) {
                    is TimeSelection -> it.onConfirm(value as Date)
                    is Color -> it.onConfirm(value as String)
                    is Alert -> {
                        val shouldNotShowMoreDialogs = value as Boolean
                        promptAbuserDetector.userWantsMoreDialogs(!shouldNotShowMoreDialogs)
                        it.onConfirm(!shouldNotShowMoreDialogs)
                    }
                    is SingleChoice -> it.onConfirm(value as Choice)
                    is MenuChoice -> it.onConfirm(value as Choice)
                    is BeforeUnload -> it.onLeave()
                    is Popup -> it.onAllow()
                    is MultipleChoice -> it.onConfirm(value as Array<Choice>)

                    is Authentication -> {
                        val (user, password) = value as Pair<String, String>
                        it.onConfirm(user, password)
                    }

                    is TextPrompt -> {
                        val (shouldNotShowMoreDialogs, text) = value as Pair<Boolean, String>

                        promptAbuserDetector.userWantsMoreDialogs(!shouldNotShowMoreDialogs)
                        it.onConfirm(!shouldNotShowMoreDialogs, text)
                    }

                    is Share -> it.onSuccess()

                    is SaveLoginPrompt -> it.onConfirm(value as Login)

                    is Confirm -> {
                        val (isCheckBoxChecked, buttonType) =
                            value as Pair<Boolean, MultiButtonDialogFragment.ButtonType>
                        promptAbuserDetector.userWantsMoreDialogs(!isCheckBoxChecked)
                        when (buttonType) {
                            MultiButtonDialogFragment.ButtonType.POSITIVE ->
                                it.onConfirmPositiveButton(!isCheckBoxChecked)
                            MultiButtonDialogFragment.ButtonType.NEGATIVE ->
                                it.onConfirmNegativeButton(!isCheckBoxChecked)
                            MultiButtonDialogFragment.ButtonType.NEUTRAL ->
                                it.onConfirmNeutralButton(!isCheckBoxChecked)
                        }
                    }

                    is Repost -> it.onConfirm()
                }
            } catch (e: ClassCastException) {
                throw IllegalArgumentException(
                    "PromptFeature onConsume cast failed with ${it.javaClass}",
                    e
                )
            }
        }
    }

    /**
     * Invoked when the user is requesting to clear the selected value from the dialog.
     * This consumes the [WebPromptFeature] value from the [SessionState] indicated by [sessionId].
     *
     * @param sessionId that requested to show the dialog.
     */
    override fun onClear(sessionId: String) {
        store.consumePromptFrom(sessionId, activePrompt) {
            when (it) {
                is TimeSelection -> it.onClear()
            }
        }
    }

    /**
     * Re-attaches a fragment that is still visible but not linked to this feature anymore.
     */
    private fun reattachFragment(fragment: PromptDialogFragment) {
        val session = store.state.findTabOrCustomTab(fragment.sessionId)
        if (session?.content?.promptRequest == null) {
            fragmentManager.beginTransaction()
                .remove(fragment)
                .commitAllowingStateLoss()
            return
        }
        // Re-assign the feature instance so that the fragment can invoke us once the user makes a selection or cancels
        // the dialog.
        fragment.feature = this
    }

    private fun handleShareRequest(promptRequest: Share, session: SessionState) {
        shareDelegate.showShareSheet(
            context = container.context,
            shareData = promptRequest.data,
            onDismiss = { onCancel(session.id) },
            onSuccess = { onConfirm(session.id, null) }
        )
    }

    @Suppress("ComplexMethod", "LongMethod")
    @VisibleForTesting(otherwise = PRIVATE)
    internal fun handleDialogsRequest(
        promptRequest: WebPromptRequest,
        session: SessionState
    ) {
        // Requests that are handled with dialogs
        val dialog = when (promptRequest) {

            is SaveLoginPrompt -> {
                loginPromptHandler?.handleRequest(session, promptRequest)
            }

            is SingleChoice -> ChoiceDialogFragment.newInstance(
                promptRequest.choices,
                session.id, SINGLE_CHOICE_DIALOG_TYPE
            )

            is MultipleChoice -> ChoiceDialogFragment.newInstance(
                promptRequest.choices, session.id, MULTIPLE_CHOICE_DIALOG_TYPE
            )

            is MenuChoice -> ChoiceDialogFragment.newInstance(
                promptRequest.choices, session.id, MENU_CHOICE_DIALOG_TYPE
            )

            is Alert -> {
                with(promptRequest) {
                    AlertDialogFragment.newInstance(
                        session.id,
                        title,
                        message,
                        promptAbuserDetector.areDialogsBeingAbused()
                    )
                }
            }

            is TimeSelection -> {

                val selectionType = when (promptRequest.type) {
                    TimeSelection.Type.DATE -> TimePickerDialogFragment.SELECTION_TYPE_DATE
                    TimeSelection.Type.DATE_AND_TIME -> TimePickerDialogFragment.SELECTION_TYPE_DATE_AND_TIME
                    TimeSelection.Type.TIME -> TimePickerDialogFragment.SELECTION_TYPE_TIME
                    TimeSelection.Type.MONTH -> TimePickerDialogFragment.SELECTION_TYPE_MONTH
                }

                with(promptRequest) {
                    TimePickerDialogFragment.newInstance(
                        session.id,
                        initialDate,
                        minimumDate,
                        maximumDate,
                        selectionType
                    )
                }
            }

            is TextPrompt -> {
                with(promptRequest) {
                    TextPromptDialogFragment.newInstance(
                        session.id,
                        title,
                        inputLabel,
                        inputValue,
                        promptAbuserDetector.areDialogsBeingAbused()
                    )
                }
            }

            is Authentication -> {
                with(promptRequest) {
                    AuthenticationDialogFragment.newInstance(
                        session.id,
                        title,
                        message,
                        userName,
                        password,
                        onlyShowPassword,
                        session.content.url
                    )
                }
            }

            is Color -> ColorPickerDialogFragment.newInstance(
                session.id,
                promptRequest.defaultColor
            )

            is Popup -> {

                val title = container.getString(R.string.mozac_feature_prompts_popup_dialog_title)
                val positiveLabel = container.getString(R.string.mozac_feature_prompts_allow)
                val negativeLabel = container.getString(R.string.mozac_feature_prompts_deny)

                ConfirmDialogFragment.newInstance(
                    sessionId = session.id,
                    title = title,
                    message = promptRequest.targetUri,
                    positiveButtonText = positiveLabel,
                    negativeButtonText = negativeLabel
                )
            }
            is BeforeUnload -> {

                val title =
                    container.getString(R.string.mozac_feature_prompt_before_unload_dialog_title)
                val body =
                    container.getString(R.string.mozac_feature_prompt_before_unload_dialog_body)
                val leaveLabel =
                    container.getString(R.string.mozac_feature_prompts_before_unload_leave)
                val stayLabel =
                    container.getString(R.string.mozac_feature_prompts_before_unload_stay)

                ConfirmDialogFragment.newInstance(
                    sessionId = session.id,
                    title = title,
                    message = body,
                    positiveButtonText = leaveLabel,
                    negativeButtonText = stayLabel
                )
            }

            is Confirm -> {
                with(promptRequest) {
                    val positiveButton = if (positiveButtonTitle.isEmpty()) {
                        container.getString(R.string.mozac_feature_prompts_ok)
                    } else {
                        positiveButtonTitle
                    }
                    val negativeButton = if (positiveButtonTitle.isEmpty()) {
                        container.getString(R.string.mozac_feature_prompts_cancel)
                    } else {
                        positiveButtonTitle
                    }

                    MultiButtonDialogFragment.newInstance(
                        session.id,
                        title,
                        message,
                        promptAbuserDetector.areDialogsBeingAbused(),
                        false,
                        positiveButton,
                        negativeButton,
                        neutralButtonTitle
                    )
                }
            }

            is Repost -> {
                val title = container.context.getString(R.string.mozac_feature_prompt_repost_title)
                val message = container.context.getString(R.string.mozac_feature_prompt_repost_message)
                val positiveAction =
                    container.context.getString(R.string.mozac_feature_prompt_repost_positive_button_text)
                val negativeAction =
                    container.context.getString(R.string.mozac_feature_prompt_repost_negative_button_text)

                ConfirmDialogFragment.newInstance(
                    sessionId = session.id,
                    title = title,
                    message = message,
                    positiveButtonText = positiveAction,
                    negativeButtonText = negativeAction
                )
            }

            else -> throw InvalidParameterException("Not valid prompt request type")
        } ?: return

        dialog.feature = this

        if (canShowThisPrompt(promptRequest)) {
            dialog.show(fragmentManager, FRAGMENT_TAG)
            activePrompt = WeakReference(dialog)
        } else {
            (promptRequest as DismissiblePrompt).onDismiss()
            store.dispatch(ContentAction.ConsumePromptRequestAction(session.id))
        }
        promptAbuserDetector.updateJSDialogAbusedState()
    }

    private fun canShowThisPrompt(promptRequest: WebPromptRequest): Boolean {
        return when (promptRequest) {
            is SingleChoice,
            is MultipleChoice,
            is MenuChoice,
            is TimeSelection,
            is File,
            is Color,
            is Authentication,
            is BeforeUnload,
            is Popup,
            is Share -> true
            is Alert, is TextPrompt, is Confirm, is Repost -> promptAbuserDetector.shouldShowMoreDialogs
        }
    }
}

internal fun BrowserStore.consumePromptFrom(
    sessionId: String?,
    activePrompt: WeakReference<PromptDialogFragment>? = null,
    consume: (WebPromptRequest) -> Unit
) {
    if (sessionId == null) {
        state.selectedTab
    } else {
        state.findTabOrCustomTabOrSelectedTab(sessionId)
    }?.let { tab ->
        activePrompt?.clear()
        tab.content.promptRequest?.let {
            consume(it)
            dispatch(ContentAction.ConsumePromptRequestAction(tab.id))
        }
    }
}
