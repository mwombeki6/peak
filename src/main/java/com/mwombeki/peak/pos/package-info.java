@ApplicationModule(
        id = "pos",
        displayName = "Point of Sale",
        allowedDependencies = {
                "shared::context",
                "shared::exception",
                "audit::api",
                "reliability::api",
                "billing::api",
                "payment::api"
        }
)
package com.mwombeki.peak.pos;

import org.springframework.modulith.ApplicationModule;
