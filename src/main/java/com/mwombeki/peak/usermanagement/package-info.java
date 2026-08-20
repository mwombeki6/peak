@ApplicationModule(
        id = "usermanagement",
        displayName = "User Management",
        allowedDependencies = {
                "shared::context",
                "shared::exception",
                "shared::security",
                "shared::secrets",
                "shared::outbound",
                "audit::api",
                "reliability::api",
                "verification::api"
        }
)
package com.mwombeki.peak.usermanagement;

import org.springframework.modulith.ApplicationModule;
