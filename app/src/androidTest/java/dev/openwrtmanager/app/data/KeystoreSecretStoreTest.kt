package dev.openwrtmanager.app.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Runs the shared contract against the real Android Keystore. Needs a device or emulator. */
@RunWith(AndroidJUnit4::class)
class KeystoreSecretStoreTest : SecretStoreContract() {
    override fun createStore(): SecretStore {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "secrets-test-${UUID.randomUUID()}.preferences_pb")
        return KeystoreSecretStore(PreferenceDataStoreFactory.create { file })
    }
}
