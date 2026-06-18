@ApplicationModule(
        id = "platformgovernance",
        displayName = "Platform Governance",
        allowedDependencies = {
                "shared",
                "shared :: context",
                "audit",
                "reliability",
                "integrations",
                "tenantmanagement",
                "usermanagement"
        }
)
package com.mwombeki.peak.platformgovernance;

import org.springframework.modulith.ApplicationModule;
