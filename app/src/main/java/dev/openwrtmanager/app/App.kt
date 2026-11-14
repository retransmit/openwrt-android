package dev.openwrtmanager.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.openwrtmanager.app.ssh.SshSecurity

@HiltAndroidApp
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        SshSecurity.install()
    }
}
