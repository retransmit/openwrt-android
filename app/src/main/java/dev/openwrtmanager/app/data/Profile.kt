package dev.openwrtmanager.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class AuthType { PASSWORD, KEY }

@Entity(tableName = "profiles")
data class Profile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String = "root",
    val authType: AuthType,
    val hostKeyFingerprint: String? = null,
    val lastUsedAt: Long = 0,
)
