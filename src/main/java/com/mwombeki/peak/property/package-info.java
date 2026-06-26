@ApplicationModule(
        id = "property",
        displayName = "Property Management Module",
        allowedDependencies = {"shared::context", "audit::api"}
)
package com.mwombeki.peak.property;

import org.springframework.modulith.ApplicationModule;
