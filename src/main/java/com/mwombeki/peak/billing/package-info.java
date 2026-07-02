@ApplicationModule(
        id = "billing",
        displayName = "Billing",
        allowedDependencies = {
                "shared::context",
                "shared::exception",
                "audit::api",
                "reliability::api"
        }
)
package com.mwombeki.peak.billing;

import org.springframework.modulith.ApplicationModule;
