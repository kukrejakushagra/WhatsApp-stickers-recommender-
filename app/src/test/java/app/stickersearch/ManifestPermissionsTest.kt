package app.stickersearch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Google Play rejects "All files access" for an app like this; the folder picker does the job. Keep it out. */
class ManifestPermissionsTest {
    private val manifest = listOf("src/main", "app/src/main").map { File(it, "AndroidManifest.xml") }.first { it.exists() }.readText()

    @Test fun noAllFilesAccess() = assertFalse(manifest.contains("MANAGE_EXTERNAL_STORAGE"))

    @Test fun storagePermissionOnlyForOldAndroid() =
        assertTrue(Regex("""READ_EXTERNAL_STORAGE"\s+android:maxSdkVersion="32"""").containsMatchIn(manifest))

    @Test fun downloadActionsReceiverIsPrivate() =
        assertTrue(Regex("""DownloadActionReceiver"\s+android:exported="false"""").containsMatchIn(manifest))
}
