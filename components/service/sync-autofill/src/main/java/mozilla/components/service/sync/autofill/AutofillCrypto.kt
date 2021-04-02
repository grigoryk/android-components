/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.service.sync.autofill

import android.content.Context
import android.content.SharedPreferences
import mozilla.appservices.autofill.createKey
import mozilla.appservices.autofill.decryptString
import mozilla.appservices.autofill.encryptString
import mozilla.components.concept.base.crash.CrashReporting
import mozilla.components.lib.dataprotect.KeyGenerationReason
import mozilla.components.lib.dataprotect.KeyProvider
import mozilla.components.lib.dataprotect.ManagedKey
import mozilla.components.lib.dataprotect.SecureAbove22Preferences

internal sealed class AutofillCryptoException(cause: Exception? = null): Exception(cause) {
    class MissingStoredKey : AutofillCryptoException()
    class AbnormalKeyStorage : AutofillCryptoException()
    class CorruptKey : AutofillCryptoException()
    class IllegalState : AutofillCryptoException()
}

/**
 * A class that knows how to encrypt & decrypt strings, backed by application-services' autofill lib.
 * Used for protecting credit card numbers at rest.
 *
 * This class manages creation and storage of the encryption key.
 * It also keeps track of abnormal events, such as managed key going missing or getting corrupted.
 *
 * @param context [Context] used for obtaining [SharedPreferences] for managing internal prefs.
 * @param crashReporter A [CrashReporting] instance used for recording abnormal events.
 * @param securePrefs A [SecureAbove22Preferences] instance used for storing the managed key.
 */
class AutofillCrypto(
    private val context: Context,
    private val securePrefs: SecureAbove22Preferences,
    private val crashReporter: CrashReporting? = null,
    private val newKeyHandler: (reason: KeyGenerationReason) -> Unit
): KeyProvider {
    private val plaintextPrefs by lazy { context.getSharedPreferences(AUTOFILL_PREFS, Context.MODE_PRIVATE) }

    /**
     * @param cleartext A string to encrypt.
     * @return An encrypted [cleartext].
     */
    fun encrypt(key: ManagedKey, cleartext: String) = encryptString(key.key, cleartext)
    fun decrypt(key: ManagedKey, ciphertext: String) = decryptString(key.key, ciphertext)

    override fun key(): ManagedKey = synchronized(this) {
        val managedKey = getManagedKey()

        // Record abnormal events if any were detected.
        when (managedKey.wasGenerated) {
            KeyGenerationReason.Lost -> {
                crashReporter?.submitCaughtException(AutofillCryptoException.MissingStoredKey())
            }
            KeyGenerationReason.Corrupt -> {
                crashReporter?.submitCaughtException(AutofillCryptoException.CorruptKey())
            }
            KeyGenerationReason.AbnormalState -> {
                crashReporter?.submitCaughtException(AutofillCryptoException.AbnormalKeyStorage())
            }
            null, KeyGenerationReason.New -> {
                // All good! Got either a brand new key or read a valid key.
            }
        }
        managedKey.wasGenerated?.let { newKeyHandler(it) }
        return managedKey
    }

    private fun getManagedKey(): ManagedKey = synchronized(this) {
        val encryptedCanaryPhrase = plaintextPrefs.getString(CANARY_PHRASE_CIPHERTEXT_KEY, null)
        val storedKey = securePrefs.getString(AUTOFILL_KEY)

        return@synchronized when {
            // We expected the key to be present, and it is.
            storedKey != null && encryptedCanaryPhrase != null -> {
                // Make sure that the key is valid.
                if (CANARY_PHRASE_PLAINTEXT == decryptString(storedKey, encryptedCanaryPhrase)) {
                    ManagedKey(storedKey)
                } else {
                    ManagedKey(generateAndStoreKey(), KeyGenerationReason.Corrupt)
                }
            }

            // The key is present, but we didn't expect it to be there.
            storedKey != null && encryptedCanaryPhrase == null -> {
                // This isn't expected to happen. We can't check this key's validity.
                ManagedKey(generateAndStoreKey(), KeyGenerationReason.AbnormalState)
            }

            // We expected the key to be present, but it's gone missing on us.
            storedKey == null && encryptedCanaryPhrase != null -> {
                // At this point, we're forced to generate a new key to recover and move forward.
                // However, that means that any data that was previously encrypted is now unreadable.
                ManagedKey(generateAndStoreKey(), KeyGenerationReason.Lost)
            }

            // We didn't expect the key to be present, and it's not.
            storedKey == null && encryptedCanaryPhrase == null -> {
                // Normal case when interacting with this class for the first time.
                ManagedKey(generateAndStoreKey(), KeyGenerationReason.New)
            }

            else -> throw AutofillCryptoException.IllegalState()
        }
    }

    private fun generateAndStoreKey(): String {
        return createKey().also { newKey ->
            // TODO should this be a non-destructive operation, just in case?
            // e.g. if we thought we lost the key, but actually did not, that would let us recover data later on.
            // otherwise, if we mess up and override a perfectly good key, the data is gone for good.
            securePrefs.putString(AUTOFILL_KEY, newKey)
            // To detect key corruption or absence, use the newly generated key to encrypt a known string.
            // See isKeyValid below.
            plaintextPrefs
                .edit()
                .putString(CANARY_PHRASE_CIPHERTEXT_KEY, encryptString(newKey, CANARY_PHRASE_PLAINTEXT))
                .apply()
        }
    }

    companion object {
        const val AUTOFILL_PREFS = "autofillCrypto"
        const val AUTOFILL_KEY = "autofillKey"
        const val CANARY_PHRASE_CIPHERTEXT_KEY = "canaryPhrase"
        const val CANARY_PHRASE_PLAINTEXT = "a string for checking validity of the key"
    }
}
