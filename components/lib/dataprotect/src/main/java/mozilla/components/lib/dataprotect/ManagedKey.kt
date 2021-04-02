/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.lib.dataprotect

/**
 * Knows how to provide a [ManagedKey].
 */
interface KeyProvider {
    fun key(): ManagedKey
}

/**
 * An encryption key, with an optional [wasGenerated] field used to indicate if it was freshly
 * generated. In that case, a [KeyGenerationReason] is supplied, allowing consumers to detect
 * potential key loss or corruption.
 * If [wasGenerated] is `null`, that means an existing key was successfully read from the key storage.
 */
data class ManagedKey(
    val key: String,
    val wasGenerated: KeyGenerationReason? = null
) {
    fun recoveryNeeded(): Boolean = wasGenerated != null || wasGenerated != KeyGenerationReason.New
}

/**
 * Describes why a key was generated.
 */
enum class KeyGenerationReason {
    // A new key, not previously present in the store
    New,

    // Previously stored key was lost, and a new key was generated as its replacement.
    Lost,

    // Previously stored key was corrupted, and a new key was generated as its replacement.
    Corrupt,

    // Storage layer encountered an abnormal state, which lead to key loss. A new key was generated.
    AbnormalState
}
