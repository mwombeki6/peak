package com.mwombeki.peak.shared.security

/**
 * PSecurity Authorities.
 * Maps directly to your database platform permissions and RBAC matrix contracts.
 */
enum class SecurityAuthority {
    PLATFORM_ADMIN,   // Management over the entire software system
    TENANT_OWNER,     // High-level access to a specific hotel tenant profile
    HOTEL_STAFF,      // Base operational access for front desk, housekeeping, etc.
    PUBLIC_CLIENT     // Anonymous access restricted strictly to public booking engine pages
}