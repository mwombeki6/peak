@ApplicationModule(
        id = "frontdesk",
        displayName = "Front Desk",
        allowedDependencies = {
                "shared::context",
                "shared::exception",
                "audit::api",
                "reliability::api",
                "reservations::api",
                "billing::api",
                "fiscal::api",
                "property::api"
        }
)
package com.mwombeki.peak.frontdesk;

import org.springframework.modulith.ApplicationModule;
