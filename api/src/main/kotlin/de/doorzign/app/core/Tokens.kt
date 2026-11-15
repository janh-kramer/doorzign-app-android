package de.doorzign.app.core

import kotlinx.serialization.Serializable

@Serializable
data class Tokens(val accessToken: String, val refreshToken: String, val expiresAt: String? = null)

/** Ablage der Tokens. Android-Implementierung verschlüsselt mit einem Schlüssel aus dem Android Keystore. */
interface TokenStore {
    fun load(): Tokens?
    fun save(tokens: Tokens)
    fun clear()
}

/** Für Tests und als Zwischenspeicher. */
class InMemoryTokenStore(initial: Tokens? = null) : TokenStore {
    @Volatile private var tokens: Tokens? = initial
    override fun load() = tokens
    override fun save(tokens: Tokens) { this.tokens = tokens }
    override fun clear() { tokens = null }
}
