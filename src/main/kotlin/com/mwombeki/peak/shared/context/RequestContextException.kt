package com.mwombeki.peak.shared.context

import org.springframework.modulith.NamedInterface

@NamedInterface("context")
class RequestContextException(message: String) : RuntimeException(message)
