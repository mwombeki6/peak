@ApplicationModule(
        id = "communications",
        displayName = "Communications & Outbox Module",
        allowedDependencies = {"shared::context", "reliability::api"}
)
package com.mwombeki.peak.communication;

import org.springframework.modulith.ApplicationModule;
