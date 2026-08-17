@ApplicationModule(
        id = "reservations",
        displayName = "Reservations",
        allowedDependencies = {
                "shared::context",
                "shared::exception",
                "audit::api",
                "reliability::api",
                "billing::api",
                "communications::api"
        }
)
package com.mwombeki.peak.reservations;

import org.springframework.modulith.ApplicationModule;
