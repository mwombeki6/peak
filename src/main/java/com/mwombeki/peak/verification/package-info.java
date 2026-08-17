@ApplicationModule(
        id = "verification",
        displayName = "Verification",
        allowedDependencies = {
                "shared::context",
                "shared::exception",
                "shared::secrets",
                "shared::ephemeral",
                "reliability::api"
        }
)
package com.mwombeki.peak.verification;

import org.springframework.modulith.ApplicationModule;
