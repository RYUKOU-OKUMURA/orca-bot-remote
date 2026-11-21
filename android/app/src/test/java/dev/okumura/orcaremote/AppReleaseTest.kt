package dev.okumura.orcaremote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppReleaseTest {
    private val release = AppRelease(42, "0.2.42", "ab".repeat(32), 100)

    @Test
    fun validatesMetadataLimits() {
        assertTrue(validRelease(release))
        for (invalid in listOf(
            release.copy(versionCode = 0),
            release.copy(versionCode = 9_007_199_254_740_992L),
            release.copy(versionName = ""),
            release.copy(versionName = "X".repeat(65)),
            release.copy(versionName = "v1\n"),
            release.copy(sha256 = release.sha256.uppercase()),
            release.copy(sha256 = "0".repeat(63)),
            release.copy(size = 0),
            release.copy(size = 200L * 1024 * 1024 + 1),
        )) assertFalse(validRelease(invalid))
    }

    @Test
    fun offersOnlyNewerVersionCodes() {
        assertTrue(isNewerRelease(release, 41))
        assertFalse(isNewerRelease(release, 42))
        assertFalse(isNewerRelease(release, 43))
    }

    @Test
    fun sizeAndChecksumMustBothMatch() {
        assertTrue(matchesDownload(release, 100, release.sha256))
        assertFalse(matchesDownload(release, 99, release.sha256))
        assertFalse(matchesDownload(release, 100, "f".repeat(64)))
    }

    @Test
    fun archiveMustMatchPackageAndVersion() {
        val pkg = "dev.okumura.orcaremote"
        assertTrue(matchesAppArchive(release, pkg, pkg, 42))
        assertFalse(matchesAppArchive(release, pkg, "other.package", 42))
        assertFalse(matchesAppArchive(release, pkg, pkg, 43))
        assertFalse(matchesAppArchive(release, pkg, null, null))
    }
}
