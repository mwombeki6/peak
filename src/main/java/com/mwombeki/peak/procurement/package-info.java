@ApplicationModule(
        id = "procurement",
        displayName = "Procurement",
        allowedDependencies = {
                "shared::context",
                "shared::exception",
                "audit::api",
                "reliability::api",
                "inventory::api",
                "property::api"
        }
)
package com.mwombeki.peak.procurement;

import org.springframework.modulith.ApplicationModule;
