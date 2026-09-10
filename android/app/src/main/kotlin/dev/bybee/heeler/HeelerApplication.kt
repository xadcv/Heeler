package dev.bybee.heeler

import android.app.Application
import dev.bybee.heeler.data.DeviceKeyStore
import dev.bybee.heeler.data.HostStore
import dev.bybee.heeler.data.PreferencesKnownHostsStore
import dev.bybee.heeler.ssh.JschTransport

/** Process-wide singletons. Small enough that a DI framework would be more code than it saves. */
class AppContainer(application: Application) {
    val hosts = HostStore(application)
    val deviceKey = DeviceKeyStore(application)
    val knownHosts = PreferencesKnownHostsStore(application)
}

class HeelerApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Android ships no JCA Ed25519/XDH provider; JSch must use its BouncyCastle paths.
        JschTransport.configureForAndroid()
        container = AppContainer(this)
    }
}
