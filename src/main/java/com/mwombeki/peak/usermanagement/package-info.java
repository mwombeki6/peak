@ApplicationModule(
        id = "usermanagement",
        displayName = "User Management",
        allowedDependencies = {"shared", "audit", "reliability", "tenantmanagement"}
)
package com.mwombeki.peak.usermanagement;

import org.springframework.modulith.ApplicationModule;
