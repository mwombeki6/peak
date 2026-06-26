@ApplicationModule(
        id = "tenantmanagement",
        displayName = "Tenant Management",
        allowedDependencies = {
                "audit::api",
                "reliability::api",
                "shared::context"
        }
)
package com.mwombeki.peak.tenantmanagement;

import org.springframework.modulith.ApplicationModule;
