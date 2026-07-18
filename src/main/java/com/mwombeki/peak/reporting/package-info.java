@ApplicationModule(
        id = "reports",
        displayName = "Reporting",
        allowedDependencies = {
                "shared::context",
                "shared::exception",
                "shared::outbound",
                "audit::api",
                "reliability::api",
                "nightaudit::api",
                "communications::api"
        }
)
package com.mwombeki.peak.reporting;

import org.springframework.modulith.ApplicationModule;
