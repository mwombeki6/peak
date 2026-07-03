@ApplicationModule(
        id = "nightaudit",
        displayName = "Night Audit",
        allowedDependencies = {
                "shared::context",
                "shared::exception",
                "audit::api",
                "reliability::api",
                "property::api",
                "reservations::api",
                "billing::api",
                "payment::api",
                "fiscal::api",
                "pos::api"
        }
)
package com.mwombeki.peak.nightaudit;

import org.springframework.modulith.ApplicationModule;
