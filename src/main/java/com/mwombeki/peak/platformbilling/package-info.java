/**
 * Peak's own subscription revenue: tenants buying Peak from inside Peak.
 *
 * <p>This module owns the commercial plane — what is sold, at what price, and what was
 * paid. It does not own the entitlement or technical planes. It never writes
 * {@code tenant_subscriptions}, {@code tenant_modules} or {@code property_modules};
 * those belong to tenantmanagement and property, and are reached through their
 * projection ports so that ownership stays where the enforcing code lives.
 *
 * <p>It depends on {@code payment::api} for the provider SPI only, using Peak's own
 * merchant credentials from configuration rather than a tenant's provider account.
 */
@ApplicationModule(
        id = "platformbilling",
        displayName = "Platform Billing",
        allowedDependencies = {
                "shared::context",
                "shared::exception",
                "shared::outbound",
                "shared::secrets",
                "audit::api",
                "reliability::api",
                "payment::api",
                "tenantmanagement::api",
                "property::api"
        }
)
package com.mwombeki.peak.platformbilling;

import org.springframework.modulith.ApplicationModule;
