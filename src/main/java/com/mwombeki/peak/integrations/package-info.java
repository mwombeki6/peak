@ApplicationModule(
        id = "integrations",
        displayName = "Integrations",
        allowedDependencies = {
                "shared::context",
                "shared::outbound",
                "shared::secrets",
                "reliability::api",
                "reservations::api",
                "payment::api",
                "fiscal::api",
                "reports::api"
        }
)
package com.mwombeki.peak.integrations;

import org.springframework.modulith.ApplicationModule;
