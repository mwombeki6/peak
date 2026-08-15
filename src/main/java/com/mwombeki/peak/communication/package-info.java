@ApplicationModule(
        id = "communications",
        displayName = "Communications & Outbox Module",
        allowedDependencies = {
                "audit::api",
                "reliability::api",
                "shared::context",
                "shared::exception",
                "shared::secrets",
                "shared::outbound"
        }
)
package com.mwombeki.peak.communication;

import org.springframework.modulith.ApplicationModule;
