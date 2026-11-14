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
