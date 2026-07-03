@ApplicationModule(
        id = "housekeeping",
        displayName = "Housekeeping",
        allowedDependencies = {
                "shared::context",
                "shared::exception",
                "audit::api",
                "reliability::api",
                "property::api",
                "frontdesk::api"
        }
)
package com.mwombeki.peak.housekeeping;

import org.springframework.modulith.ApplicationModule;
