package com.asterlink.app

import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE)
class AppUpdateSigningTest {
    @Suppress("DEPRECATION")
    private fun pkg(build: Int, signers: List<String>, history: List<String>? = null) = PackageInfo().apply {
        packageName = "com.asterlink.app"
        versionCode = build
        versionName = if (build == 120) "1.2.0" else "1.1.0"
        signingInfo = SigningInfo().also {
            shadowOf(it).setSignatures(signers.map(::Signature).toTypedArray())
            if (history != null) shadowOf(it).setPastSigningCertificates(history.map(::Signature).toTypedArray())
        }
    }

    private fun validate(next: PackageInfo, installed: PackageInfo) =
        AppUpdatePackage.validate(next, installed, "1.2.0", 120)

    private fun rejected(next: PackageInfo, installed: PackageInfo) {
        try { validate(next, installed); fail("Unrelated signing key accepted") }
        catch (error: IllegalArgumentException) { assertTrue(error.message.orEmpty().contains("签名")) }
    }

    @Test fun acceptsUnchangedKey() { validate(pkg(120,listOf("abcd")),pkg(110,listOf("abcd"))) }
    @Test fun rejectsAnotherAppsKey() { rejected(pkg(120,listOf("1234")),pkg(110,listOf("abcd"))) }
    @Test fun acceptsAuthorizedRotationFromInstalledKey() {
        validate(pkg(120,listOf("1234"),listOf("abcd","1234")),pkg(110,listOf("abcd")))
    }
    @Test fun refusesRotationThatDoesNotIncludeTheInstalledCurrentKey() {
        rejected(pkg(120,listOf("5678"),listOf("abcd","5678")),pkg(110,listOf("1234"),listOf("abcd","1234")))
    }
    @Test fun multipleSignersMustAllMatch() {
        validate(pkg(120,listOf("1234","abcd")),pkg(110,listOf("abcd","1234")))
        rejected(pkg(120,listOf("abcd","5678")),pkg(110,listOf("abcd","1234")))
    }
}
