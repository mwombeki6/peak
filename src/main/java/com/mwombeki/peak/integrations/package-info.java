@ApplicationModule(
        id = "integrations",
        displayName = "Integrations",
        allowedDependencies = {"shared::context", "reliability::api"}
)
package com.mwombeki.peak.integrations;

import org.springframework.modulith.ApplicationModule;
