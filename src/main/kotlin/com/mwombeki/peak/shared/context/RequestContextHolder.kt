package com.mwombeki.peak.shared.context

import org.springframework.stereotype.Component

@Component
class RequestContextHolder {
    private val currentContext = ThreadLocal<RequestContext>()

    fun set(context: RequestContext) {
        currentContext.set(context)
    }

    fun current(): RequestContext {
        return currentContext.get()
            ?: throw RequestContextException("Request context is not bound")
    }

    fun currentOrNull(): RequestContext? {
        return currentContext.get()
    }

    fun clear() {
        currentContext.remove()
    }
}
