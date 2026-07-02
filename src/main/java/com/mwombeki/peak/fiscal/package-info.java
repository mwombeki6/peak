@ApplicationModule(
        id = "fiscal",
        displayName = "Fiscal",
        allowedDependencies = {
                "shared::context",
                "shared::exception",
                "shared::outbound",
                "shared::secrets",
                "audit::api",
                "reliability::api"
        }
)
package com.mwombeki.peak.fiscal;

import org.springframework.modulith.ApplicationModule;
