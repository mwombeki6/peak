@ApplicationModule(
        id = "integrations",
        displayName = "Integrations",
        allowedDependencies = {"shared::context", "reliability::api", "reservations::api"}
)
package com.mwombeki.peak.integrations;

import org.springframework.modulith.ApplicationModule;
