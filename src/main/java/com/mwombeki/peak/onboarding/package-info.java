@ApplicationModule(
        id = "onboarding",
        displayName = "Enterprise Onboarding",
        allowedDependencies = {
                "shared::context",
                "shared::exception",
                "shared::outbound",
                "shared::secrets",
                "reliability::api",
                "audit::api",
                "verification::api",
                "tenantmanagement::api",
                "usermanagement::api"
        }
)
package com.mwombeki.peak.onboarding;

import org.springframework.modulith.ApplicationModule;
