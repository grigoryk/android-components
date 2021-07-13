/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.browser.state.state

import mozilla.components.support.utils.EXTRA_ACTIVITY_REFERRER_CATEGORY
import mozilla.components.support.utils.EXTRA_ACTIVITY_REFERRER_PACKAGE
import mozilla.components.support.utils.SafeIntent

/**
 * Interface for states that contain a [ContentState] and can be accessed via an [id].
 *
 * @property id the unique id of the session.
 * @property content the [ContentState] of this session.
 * @property trackingProtection the [TrackingProtectionState] of this session.
 * @property engineState the [EngineState] of this session.
 * @property extensionState a map of extension id and web extension states
 * specific to this [SessionState].
 * @property mediaSessionState the [MediaSessionState] of this session.
 * @property contextId the session context ID of the session. The session context ID specifies the
 * contextual identity to use for the session's cookie store.
 * https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/Work_with_contextual_identities
 * @property source the [Source] of this session to describe how and why it was created.
 */
interface SessionState {
    val id: String
    val content: ContentState
    val trackingProtection: TrackingProtectionState
    val engineState: EngineState
    val extensionState: Map<String, WebExtensionState>
    val mediaSessionState: MediaSessionState?
    val contextId: String?
    val source: Source

    /**
     * Copy the class and override some parameters.
     */
    @Suppress("LongParameterList")
    fun createCopy(
        id: String = this.id,
        content: ContentState = this.content,
        trackingProtection: TrackingProtectionState = this.trackingProtection,
        engineState: EngineState = this.engineState,
        extensionState: Map<String, WebExtensionState> = this.extensionState,
        mediaSessionState: MediaSessionState? = this.mediaSessionState,
        contextId: String? = this.contextId
    ): SessionState
}

/**
 * Describes a category of an external package.
 */
@Suppress("MagicNumber")
enum class PackageCategory(val id: Int) {
    UNKNOWN(-1),
    GAME(0),
    AUDIO(1),
    VIDEO(2),
    IMAGE(3),
    SOCIAL(4),
    NEWS(5),
    MAPS(6),
    PRODUCTIVITY(7);

    companion object {
        /**
         * Maps an int category (as it can be obtained from a package manager) to our internal representation.
         */
        fun fromInt(id: Int?): PackageCategory = when (id) {
            0 -> GAME
            1 -> AUDIO
            2 -> VIDEO
            3 -> IMAGE
            4 -> SOCIAL
            5 -> NEWS
            6 -> MAPS
            7 -> PRODUCTIVITY
            -1 -> UNKNOWN
            null -> UNKNOWN
            else -> UNKNOWN
        }
    }
}

/**
 * Describes an external package.
 * @param packageId An Android package id.
 * @param category A [PackageCategory] as defined by the application.
 */
data class ExternalPackage(val packageId: String, val category: PackageCategory)

/**
 * Represents the origin of a session to describe how and why it was created.
 * @param id A unique identifier, exists for serialization purposes.
 */
@Suppress("UNUSED_PARAMETER", "MagicNumber")
sealed class Source(val id: Int) {
    companion object {
        fun restore(sourceId: Int?, packageId: String?, packageCategory: Int?): Source {
            val caller = if (packageId != null) {
                ExternalPackage(packageId, PackageCategory.fromInt(packageCategory))
            } else {
                null
            }
            return when (sourceId) {
                1 -> External.ActionSend(caller)
                2 -> External.ActionView(caller)
                3 -> External.ActionSearch(caller)
                4 -> External.CustomTab(caller)
                // We only care about restoring 'external' types, so collapse other source types.
                // This also silently handles abnormalities (like unknown or null sourceId).
                else -> Internal.Restored
            }
        }
    }

    /**
     * Describes sessions of external origins, i.e. from outside of the application.
     */
    sealed class External(id: Int, open val caller: ExternalPackage?) : Source(id) {
        /**
         * Created to handle an ACTION_SEND (share) intent.
         */
        data class ActionSend(override val caller: ExternalPackage?) : External(1, caller)

        /**
         * Created to handle an ACTION_VIEW intent.
         */
        data class ActionView(override val caller: ExternalPackage?) : External(2, caller)

        /**
         * Created to handle an ACTION_SEARCH and ACTION_WEB_SEARCH intent.
         */
        data class ActionSearch(override val caller: ExternalPackage?) : External(3, caller)

        /**
         * Created to handle a CustomTabs intent of external origin.
         */
        data class CustomTab(override val caller: ExternalPackage?) : External(4, caller)
    }

    /**
     * Describes sessions of internal origin, i.e. from within of the application.
     */
    sealed class Internal(id: Int) : Source(id) {
        /**
         * User interacted with the home screen.
         */
        object HomeScreen : Internal(5)

        /**
         * User interacted with a menu.
         */
        object Menu : Internal(6)

        /**
         * User opened a new tab.
         */
        object NewTab : Internal(7)

        /**
         * Default value and for testing purposes.
         */
        object None : Internal(8)

        /**
         * Default value and for testing purposes.
         */
        object TextSelection : Internal(9)

        /**
         * User entered a URL or search term.
         */
        object UserEntered : Internal(10)

        /**
         * This session was restored.
         */
        object Restored : Internal(11)

        /**
         * Created to handle a CustomTabs intent of internal origin.
         */
        object CustomTab : Internal(12)
    }
}

/**
 * Produces an [ExternalPackage] based on extras present in this intent.
 */
fun SafeIntent.externalPackage(): ExternalPackage? {
    val referrerPackage = this.getStringExtra(EXTRA_ACTIVITY_REFERRER_PACKAGE)
    val referrerCategory = this.getIntExtra(EXTRA_ACTIVITY_REFERRER_CATEGORY, -1)
    return if (referrerPackage != null) {
        ExternalPackage(referrerPackage, PackageCategory.fromInt(referrerCategory))
    } else {
        null
    }
}
