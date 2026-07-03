@ApplicationModule(
        id = "inventory",
        displayName = "Inventory",
        allowedDependencies = {
                "shared::context",
                "shared::exception",
                "audit::api",
                "reliability::api",
                "property::api"
        }
)
package com.mwombeki.peak.inventory;

import org.springframework.modulith.ApplicationModule;
