@ApplicationModule(
        id = "maintenance",
        displayName = "Maintenance",
        allowedDependencies = {
                "shared::context",
                "shared::exception",
                "audit::api",
                "reliability::api",
                "property::api"
        }
)
package com.mwombeki.peak.maintenance;

import org.springframework.modulith.ApplicationModule;
