@ApplicationModule(
        id = "payment",
        displayName = "Payment",
        allowedDependencies = {
                "shared::context",
                "shared::exception",
                "shared::outbound",
                "shared::secrets",
                "audit::api",
                "reliability::api",
                "billing::api",
                "realtime::api",
                "communications::api"
        }
)
package com.mwombeki.peak.payment;

import org.springframework.modulith.ApplicationModule;
