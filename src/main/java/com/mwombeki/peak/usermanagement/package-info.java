@ApplicationModule(
        id = "usermanagement",
        displayName = "User Management",
        allowedDependencies = {
                "shared::context",
                "shared::security",
                "shared::secrets",
                "audit::api",
                "reliability::api"
        }
)
package com.mwombeki.peak.usermanagement;

import org.springframework.modulith.ApplicationModule;
