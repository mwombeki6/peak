@ApplicationModule(
        id = "realtime",
        displayName ="Real Time Event Streaming Module",
        allowedDependencies = {"shared::context", "audit::api"}
)
package com.mwombeki.peak.realtime;

import org.springframework.modulith.ApplicationModule;