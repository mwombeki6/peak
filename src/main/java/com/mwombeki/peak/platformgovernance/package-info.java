@ApplicationModule(
        id = "platformgovernance",
        displayName = "Platform Governance",
        allowedDependencies = {
                "shared::context",
                "shared::exception",
                "audit::api",
                "reliability::api",
                "tenantmanagement::api",
                "usermanagement::api"
        }
)
package com.mwombeki.peak.platformgovernance;

import org.springframework.modulith.ApplicationModule;
