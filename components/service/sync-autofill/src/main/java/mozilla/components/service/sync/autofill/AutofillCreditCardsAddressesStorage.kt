/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.service.sync.autofill

import android.content.Context
import androidx.annotation.GuardedBy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import mozilla.components.concept.base.crash.CrashReporting
import mozilla.components.concept.storage.Address
import mozilla.components.concept.storage.CreditCard
import mozilla.components.concept.storage.CreditCardNumber
import mozilla.components.concept.storage.CreditCardsAddressesStorage
import mozilla.components.concept.storage.NewCreditCardFields
import mozilla.components.concept.storage.UpdatableAddressFields
import mozilla.components.concept.storage.UpdatableCreditCardFields
import mozilla.components.concept.sync.SyncableStore
import mozilla.components.lib.dataprotect.KeyGenerationReason
import mozilla.components.lib.dataprotect.SecureAbove22Preferences
import mozilla.components.support.base.log.logger.Logger
import java.io.Closeable
import mozilla.appservices.autofill.Store as RustAutofillStorage

const val AUTOFILL_DB_NAME = "autofill.sqlite"

/**
 * An implementation of [CreditCardsAddressesStorage] back by the application-services' `autofill`
 * library.
 */
class AutofillCreditCardsAddressesStorage(
    context: Context,
    securePrefs: Lazy<SecureAbove22Preferences>,
    crashReporter: CrashReporting? = null
) : CreditCardsAddressesStorage, SyncableStore, AutoCloseable {
    private val logger = Logger("AutofillCCAddressesStorage")

    private val coroutineContext by lazy { Dispatchers.IO }

    val autofillCrypto by lazy { AutofillCrypto(context, securePrefs.value, crashReporter) {
        newKeyHandler(it)
    } }

    private val conn by lazy {
        AutofillStorageConnection.init(dbPath = context.getDatabasePath(AUTOFILL_DB_NAME).absolutePath)
        AutofillStorageConnection
    }

    private fun newKeyHandler(reason: KeyGenerationReason) {
        when (reason) {
            KeyGenerationReason.New -> logger.info("CC key generated")
            // At this point, we need A-S API to recover: https://github.com/mozilla/application-services/issues/4015
            KeyGenerationReason.Lost -> logger.warn("CC key lost, new one generated")
            KeyGenerationReason.Corrupt -> logger.warn("CC key was corrupted, new one generated")
            KeyGenerationReason.AbnormalState -> logger.warn("CC key lost due to storage malfunction, new one generated")
        }
    }

    override suspend fun addCreditCard(
        creditCardFields: NewCreditCardFields
    ): CreditCard = withContext(coroutineContext) {
        // If we need to recover from key failure, do so now.
        // We'll add the credit card with a new key below.
        val key = autofillCrypto.key()
        if (key.recoveryNeeded()) {
            newKeyHandler(key.wasGenerated!!)
        }

        val updatableCreditCardFields = UpdatableCreditCardFields(
            billingName = creditCardFields.billingName,
            cardNumber = CreditCardNumber.Encrypted(
                autofillCrypto.encrypt(key, creditCardFields.plaintextCardNumber.number)
            ),
            cardNumberLast4 = creditCardFields.cardNumberLast4,
            expiryMonth = creditCardFields.expiryMonth,
            expiryYear = creditCardFields.expiryYear,
            cardType = creditCardFields.cardType
        )

        conn.getStorage().addCreditCard(updatableCreditCardFields.into()).into()
    }

    override suspend fun updateCreditCard(
        guid: String,
        creditCardFields: UpdatableCreditCardFields
    ) = withContext(coroutineContext) {
        // Need to encrypt a new credit card number
        val updatableCreditCardFields = if (creditCardFields.cardNumber is CreditCardNumber.Plaintext) {
            // If we need to recover from key failure, do so now.
            // Since we're changing the credit card number anyway, it doesn't matter that we'll
            // loose the old number for this record.
            val key = autofillCrypto.key()
            if (key.recoveryNeeded()) {
                newKeyHandler(key.wasGenerated!!)
            }
            UpdatableCreditCardFields(
                billingName = creditCardFields.billingName,
                cardNumber = CreditCardNumber.Encrypted(
                    autofillCrypto.encrypt(key, creditCardFields.cardNumber.number)
                ),
                cardNumberLast4 = creditCardFields.cardNumberLast4,
                expiryMonth = creditCardFields.expiryMonth,
                expiryYear = creditCardFields.expiryYear,
                cardType = creditCardFields.cardType
            )
        } else {
            UpdatableCreditCardFields(
                billingName = creditCardFields.billingName,
                cardNumber = creditCardFields.cardNumber,
                cardNumberLast4 = creditCardFields.cardNumberLast4,
                expiryMonth = creditCardFields.expiryMonth,
                expiryYear = creditCardFields.expiryYear,
                cardType = creditCardFields.cardType
            )
        }
        conn.getStorage().updateCreditCard(guid, updatableCreditCardFields.into())
    }

    override suspend fun getCreditCard(guid: String): CreditCard = withContext(coroutineContext) {
        conn.getStorage().getCreditCard(guid).into()
    }

    override suspend fun getAllCreditCards(): List<CreditCard> = withContext(coroutineContext) {
        conn.getStorage().getAllCreditCards().map { it.into() }
    }

    override suspend fun deleteCreditCard(guid: String): Boolean = withContext(coroutineContext) {
        conn.getStorage().deleteCreditCard(guid)
    }

    override suspend fun touchCreditCard(guid: String) = withContext(coroutineContext) {
        conn.getStorage().touchCreditCard(guid)
    }

    override suspend fun addAddress(addressFields: UpdatableAddressFields): Address =
        withContext(coroutineContext) {
            conn.getStorage().addAddress(addressFields.into()).into()
        }

    override suspend fun getAddress(guid: String): Address = withContext(coroutineContext) {
        conn.getStorage().getAddress(guid).into()
    }

    override suspend fun getAllAddresses(): List<Address> = withContext(coroutineContext) {
        conn.getStorage().getAllAddresses().map { it.into() }
    }

    override suspend fun updateAddress(guid: String, address: UpdatableAddressFields) =
        withContext(coroutineContext) {
            conn.getStorage().updateAddress(guid, address.into())
        }

    override suspend fun deleteAddress(guid: String): Boolean = withContext(coroutineContext) {
        conn.getStorage().deleteAddress(guid)
    }

    override suspend fun touchAddress(guid: String) = withContext(coroutineContext) {
        conn.getStorage().touchAddress(guid)
    }

    override fun registerWithSyncManager() {
        conn.getStorage().registerWithSyncManager()
    }

    override fun getHandle(): Long {
        throw NotImplementedError("Use registerWithSyncManager instead")
    }

    override fun close() {
        coroutineContext.cancel()
        conn.close()
    }
}

/**
 * A singleton wrapping a [RustAutofillStorage] connection.
 */
internal object AutofillStorageConnection : Closeable {
    @GuardedBy("this")
    private var storage: RustAutofillStorage? = null

    internal fun init(dbPath: String = AUTOFILL_DB_NAME) = synchronized(this) {
        if (storage == null) {
            storage = RustAutofillStorage(dbPath)
        }
    }

    internal fun getStorage(): RustAutofillStorage = synchronized(this) {
        check(storage != null) { "must call init first" }
        return storage!!
    }

    override fun close() = synchronized(this) {
        check(storage != null) { "must call init first" }
        storage!!.destroy()
        storage = null
    }
}
