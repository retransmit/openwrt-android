package dev.openwrtmanager.app.data

import dev.openwrtmanager.app.testing.InMemorySecretStore

class SecretStoreContractTest : SecretStoreContract() {
    override fun createStore(): SecretStore = InMemorySecretStore()
}
