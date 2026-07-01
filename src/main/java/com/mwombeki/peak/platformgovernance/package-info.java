@ApplicationModule(
        id = "platformgovernance",
        displayName = "Platform Governance",
        allowedDependencies = {
                "shared::context",
                "audit::api",
                "reliability::api"
        }
)
package com.mwombeki.peak.platformgovernance;

import org.springframework.modulith.ApplicationModule;
