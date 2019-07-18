/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.service.fxa.migration

import android.annotation.TargetApi
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import mozilla.components.support.ktx.kotlin.toHexString
import mozilla.components.support.ktx.kotlin.toSha256Digest

/**
 * Data structure describing an FxA account within another package that may be used to sign-in.
 */
data class MigratableAccount(
    val email: String,
    val sourcePackage: String,
    val authInfo: MigratableAuthInfo
)

/**
 * Data structure describing FxA and Sync credentials necessary to migrate an FxA account.
 */
data class MigratableAuthInfo(
    val sessionToken: String,
    val kSync: String,
    val kXCS: String
)

/**
 * Allows querying trusted FxA Auth provider packages on the device for instances of [MigratableAccount].
 * Once an instance of [MigratableAccount] is obtained, it may be used with
 * [FxaAccountManager.migrateAccountAsync] directly,
 * or with [FirefoxAccount.migrateFromSessionTokenAsync] via [MigratableAccount.authInfo].
 */
object AccountMigration {
    private const val KEY_EMAIL = "email"
    private const val KEY_SESSION_TOKEN = "sessionToken"
    private const val KEY_KSYNC = "kSync"
    private const val KEY_KXSCS = "kXSCS"

    @Suppress("MaxLineLength")
    // NB: This defines order in which MigratableAccounts are returned in [queryMigratableAccounts].
    // We verify that each package queried comes from a trusted source, by comparing against its
    // SHA256 certificate fingerprint.
    // You can obtain that certificate fingerpring using `keytool`. After unzipping the APK, run:
    // keytool -printcert -file META-INF/SIGNATURE.RSA
    // To also obtain the same formatting, use this:
    // keytool -printcert -file META-INF/SIGNATURE.RSA | grep -i sha256: | cut -d ' ' -f 3 | sed 's/://g' | tr '[:upper:]' '[:lower:]'
    private val ecosystemProviderPackages: List<Pair<String, String>> = listOf(
        // Fennec Release
        "org.mozilla.firefox" to "a78b62a5165b4494b2fead9e76a280d22d937fee6251aece599446b2ea319b04",
        // Fennec Beta
        "org.mozilla.firefox_beta" to "a78b62a5165b4494b2fead9e76a280d22d937fee6251aece599446b2ea319b04",
        // Fennec Nightly
        "org.mozilla.fennec_aurora" to "bc0488838d06f4ca6bf32386daab0dd8ebcf3e7730787459f62fb3cd14a1baaa"
    )

    /**
     * Queries trusted FxA Auth providers present on the device, returning a list of accounts that
     * can be used for migration.
     *
     * @return A list of [MigratableAccount] that are present on the device, in an order of
     * suggested preference.
     */
    fun queryMigratableAccounts(context: Context): List<MigratableAccount> {
        val packageManager = context.packageManager
        return ecosystemProviderPackages.filter {
            // Leave only packages that are installed and match our expected signature.
            packageExistsWithSignature(packageManager, it.first, it.second)
        }.mapNotNull {
            queryForAccount(context, it.first)
        }
    }

    @Suppress("LongMethod")
    private fun queryForAccount(context: Context, packageName: String): MigratableAccount? {
        // assuming a certain formatting for all authorities from all sources
        val authority = "$packageName.fxa.auth"
        val client = context.contentResolver.acquireContentProviderClient(authority) ?: return null
        val authAuthorityUri = Uri.parse("content://$authority")
        val authStateUri = Uri.withAppendedPath(authAuthorityUri, "state")

        return client.use {
            it.query(
                authStateUri,
                arrayOf(KEY_EMAIL, KEY_SESSION_TOKEN, KEY_KSYNC, KEY_KXSCS),
                null, null, null
            )?.use { cursor ->
                cursor.moveToFirst()

                val email = cursor.getString(cursor.getColumnIndex(KEY_EMAIL))
                val sessionToken = cursor.getBlob(cursor.getColumnIndex(KEY_SESSION_TOKEN))?.toHexString()
                val kSync = cursor.getBlob(cursor.getColumnIndex(KEY_KSYNC))?.toHexString()
                val kXSCS = cursor.getString(cursor.getColumnIndex(KEY_KXSCS))

                if (email == null) {
                    null
                } else if (sessionToken != null && kSync != null && kXSCS != null) {
                    MigratableAccount(
                        email = email,
                        sourcePackage = packageName,
                        authInfo = MigratableAuthInfo(sessionToken, kSync, kXSCS)
                    )
                } else {
                    null
                }
            }
        }
    }
}

/**
 * Checks if package exists, and that its signature matches provided value.
 *
 * @param packageManager [PackageManager] used for running checks against [suspectPackage].
 * @param suspectPackage Package name to check.
 * @param expectedSignature Expected signature of the [suspectPackage].
 * @return Boolean flag, true if package is present and its signature matches [expectedSignature].
 */
fun packageExistsWithSignature(
    packageManager: PackageManager,
    suspectPackage: String,
    expectedSignature: String
): Boolean {
    val suspectSignature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        getSignaturePostAPI28(packageManager, suspectPackage)
    } else {
        getSignaturePreAPI28(packageManager, suspectPackage)
    }

    return expectedSignature == suspectSignature
}

/**
 * Obtains package signature on devices running API>=28. Takes into consideration multiple signers
 * and certificate rotation.
 * @return A certificate SHA256 fingerprint, if one could be reliably obtained.
 */
@Suppress("ReturnCount")
@TargetApi(Build.VERSION_CODES.P)
fun getSignaturePostAPI28(packageManager: PackageManager, packageName: String): String? {
    // For API28+, we can perform some extra checks.
    val packageInfo = try {
        packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    } catch (e: PackageManager.NameNotFoundException) {
        return null
    }
    // We don't expect our callers to have multiple signers, so we don't service such requests.
    if (packageInfo.signingInfo.hasMultipleSigners()) {
        return null
    }
    // We currently don't support servicing requests from callers that performed certificate rotation.
    if (packageInfo.signingInfo.hasPastSigningCertificates()) {
        return null
    }

    val signature = packageInfo.signingInfo.signingCertificateHistory[0]
    return signature?.toByteArray()?.toSha256Digest()?.toHexString()
}

/**
 * Obtains package signature on devices running API<28. Takes into consideration multiple signers,
 * but not signature rotation.
 * @return A certificate SHA256 fingerprint, if one could be reliably obtained.
 */
fun getSignaturePreAPI28(packageManager: PackageManager, packageName: String): String? {
    // For older APIs, we use the deprecated `signatures` field, which isn't aware of certificate rotation.
    val packageInfo = try {
        @Suppress("deprecation")
        packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
    } catch (e: PackageManager.NameNotFoundException) {
        return null
    }

    // We don't expect our callers to have multiple signers, so we don't service such requests.
    @Suppress("deprecation")
    val signature = if (packageInfo.signatures.size != 1) {
        null
    } else {
        // For each signer (just one, in our case), if signature rotation occurred, the oldest used
        // certificate will be reported. These APIs, for backwards comparability reasons, pretend
        // that the signature rotation never took place.
        packageInfo.signatures[0]
    }

    return signature?.toByteArray()?.toSha256Digest()?.toHexString()
}
