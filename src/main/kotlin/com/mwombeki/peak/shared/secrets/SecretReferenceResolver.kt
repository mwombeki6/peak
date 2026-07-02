package com.mwombeki.peak.shared.secrets

import org.springframework.core.env.Environment
import org.springframework.modulith.NamedInterface
import org.springframework.stereotype.Component

@NamedInterface("secrets")
@Component
class SecretReferenceResolver(
    private val environment: Environment,
) {
    fun resolve(reference: String?): String {
        val normalized = reference?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw SecretReferenceException("Secret reference is not configured")

        return when {
            normalized.startsWith(ENV_PREFIX) -> {
                val name = normalized.removePrefix(ENV_PREFIX)
                require(ENV_NAME.matches(name)) {
                    "Environment secret reference is invalid"
                }
                environment.getProperty(name)?.takeIf { it.isNotBlank() }
                    ?: throw SecretReferenceException("Configured secret is unavailable")
            }

            normalized.startsWith(LITERAL_PREFIX) &&
                    !environment.activeProfiles.contains(PROD_PROFILE) -> {
                normalized.removePrefix(LITERAL_PREFIX).takeIf { it.isNotBlank() }
                    ?: throw SecretReferenceException("Configured secret is unavailable")
            }

            else -> throw SecretReferenceException(
                "Secret reference must use an environment-backed secret",
            )
        }
    }

    fun validate(reference: String) {
        resolve(reference)
    }

    private companion object {
        const val ENV_PREFIX = "env:"
        const val LITERAL_PREFIX = "literal:"
        const val PROD_PROFILE = "prod"
        val ENV_NAME = Regex("[A-Z][A-Z0-9_]{2,127}")
    }
}

class SecretReferenceException(message: String) : IllegalStateException(message)
