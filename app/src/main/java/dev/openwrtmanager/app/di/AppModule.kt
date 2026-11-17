package dev.openwrtmanager.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.openwrtmanager.app.data.AppDatabase
import dev.openwrtmanager.app.data.KeystoreSecretStore
import dev.openwrtmanager.app.data.ProfileRepository
import dev.openwrtmanager.app.data.RoomProfileRepository
import dev.openwrtmanager.app.data.SecretStore
import javax.inject.Qualifier
import kotlinx.coroutines.flow.first
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SettingsStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SecretsStore

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "app.db").build()

    @Provides
    @Singleton
    @SettingsStore
    fun settingsStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }

    /** File name must stay "secrets": the backup rules exclude datastore/secrets.preferences_pb. */
    @Provides
    @Singleton
    @SecretsStore
    fun secretsStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("secrets") }

    @Provides
    @Singleton
    fun secretStore(@SecretsStore store: DataStore<Preferences>): SecretStore = KeystoreSecretStore(store)

    @Provides
    @Singleton
    fun profileRepository(
        database: AppDatabase,
        secrets: SecretStore,
        @SettingsStore settings: DataStore<Preferences>,
    ): ProfileRepository = RoomProfileRepository(database.profileDao(), secrets, settings)
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object ConnectionModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    @Provides
    @Singleton
    fun executorFactory(): dev.openwrtmanager.app.connection.SshExecutorFactory =
        dev.openwrtmanager.app.connection.SshExecutorFactory { target -> dev.openwrtmanager.app.ssh.SshjExecutor(target) }

    @Provides
    fun profileSessions(
        profiles: ProfileRepository,
        secrets: SecretStore,
        factory: dev.openwrtmanager.app.connection.SshExecutorFactory,
    ): dev.openwrtmanager.app.connection.ProfileSessions =
        dev.openwrtmanager.app.connection.StoredProfileSessions(profiles, secrets, factory)

    @Provides
    @Singleton
    fun connectionManager(
        profiles: ProfileRepository,
        secrets: SecretStore,
        factory: dev.openwrtmanager.app.connection.SshExecutorFactory,
        @ApplicationScope scope: kotlinx.coroutines.CoroutineScope,
    ): dev.openwrtmanager.app.connection.ConnectionManager =
        dev.openwrtmanager.app.connection.ConnectionManager(profiles, secrets, factory, scope)
}

@Module
@InstallIn(SingletonComponent::class)
object DeviceSetupModule {

    @Provides
    fun fingerprintProbe(): dev.openwrtmanager.app.ui.devices.FingerprintProbe =
        dev.openwrtmanager.app.ui.devices.FingerprintProbe { host, port ->
            dev.openwrtmanager.app.ssh.SshjExecutor.probeFingerprint(host, port)
        }

    @Provides
    fun keySource(): dev.openwrtmanager.app.ui.devices.KeySource =
        dev.openwrtmanager.app.ui.devices.KeySource { dev.openwrtmanager.app.ssh.KeyGenerator.generateEd25519() }
}

@Module
@InstallIn(SingletonComponent::class)
object TimeModule {
    @Provides
    fun timeSource(): dev.openwrtmanager.app.domain.TimeSource =
        dev.openwrtmanager.app.domain.TimeSource { android.os.SystemClock.elapsedRealtime() }
}

@Module
@InstallIn(SingletonComponent::class)
object ApplyModule {
    /**
     * Each apply runs on its own SSH connection to the current device, so it is not affected by the
     * polling connection being torn down and rebuilt while the router reloads.
     */
    @Provides
    fun applySessionFactory(
        profiles: ProfileRepository,
        sessions: dev.openwrtmanager.app.connection.ProfileSessions,
        time: dev.openwrtmanager.app.domain.TimeSource,
    ): dev.openwrtmanager.app.ui.apply.ApplySessionFactory = dev.openwrtmanager.app.ui.apply.ApplySessionFactory {
        val id = profiles.observeActiveId().first() ?: return@ApplySessionFactory null
        val executor = sessions.open(id) ?: return@ApplySessionFactory null
        dev.openwrtmanager.app.ui.apply.ApplySession(
            dev.openwrtmanager.app.apply.SafeApply(executor, time),
            health = { names -> dev.openwrtmanager.app.apply.HealthCheck(executor).check(names) },
        ) { executor.close() }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object WifiModule {
    @Provides
    fun wifiGatewayProvider(
        connection: dev.openwrtmanager.app.connection.ConnectionManager,
    ): dev.openwrtmanager.app.wifi.WifiGatewayProvider = dev.openwrtmanager.app.wifi.WifiGatewayProvider {
        (connection.state.value as? dev.openwrtmanager.app.connection.ConnectionState.Online)
            ?.let { dev.openwrtmanager.app.wifi.SshWifiGateway(it.ssh) }
    }

    @Provides
    fun clientsGatewayProvider(
        connection: dev.openwrtmanager.app.connection.ConnectionManager,
    ): dev.openwrtmanager.app.clients.ClientsGatewayProvider = dev.openwrtmanager.app.clients.ClientsGatewayProvider {
        (connection.state.value as? dev.openwrtmanager.app.connection.ConnectionState.Online)
            ?.let { dev.openwrtmanager.app.clients.SshClientsGateway(it.ssh) }
    }

    @Provides
    fun passwordSource(): dev.openwrtmanager.app.ui.wifi.PasswordSource {
        val random = java.security.SecureRandom()
        return dev.openwrtmanager.app.ui.wifi.PasswordSource { dev.openwrtmanager.app.ui.wifi.generateWifiPassword(random) }
    }
}
