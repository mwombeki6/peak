package com.mwombeki.peak.shared.security

import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt

class JwtAudienceValidator(
    private val audience: String,
) : OAuth2TokenValidator<Jwt> {
    override fun validate(token: Jwt): OAuth2TokenValidatorResult {
        // Spring Security 7.1 annotates Jwt.getAudience() as nullable, so a token
        // carrying no `aud` claim reaches this line as null rather than an empty
        // list. Such a token is rejected. identity-and-access.md reuses the
        // `peak-api` audience across the peak-platform and peak-hospitality realms
        // and calls that reuse safe only because each runtime pins its exact issuer
        // *and* checks this claim; treating a null audience as a pass would let a
        // token opt out of the second half of that pair by omitting the claim.
        return if (token.audience?.contains(audience) == true) {
            OAuth2TokenValidatorResult.success()
        } else {
            OAuth2TokenValidatorResult.failure(
                OAuth2Error(
                    "invalid_token",
                    "JWT audience does not contain required audience",
                    null,
                ),
            )
        }
    }
}
