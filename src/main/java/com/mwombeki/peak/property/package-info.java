@ApplicationModule(
        id = "property",
        displayName = "Property Management Module",
        allowedDependencies = {
                "shared::context",
                "shared::exception",
                "audit::api",
                "reliability::api"
        }
)
package com.mwombeki.peak.property;

import org.springframework.modulith.ApplicationModule;
