package com.mwombeki.peak.shared.idempotency

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component

/**
 * Captures the 'X-Idempotency-Key' from mutating HTTP headers (POST/PUT/PATCH)
 * to guarantee strict exactly-once execution safety.
 */

@Component
class IdempotencyFilter : Filter {
    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        val httpRequest = request as HttpServletRequest
        val method = httpRequest.method

        // Only enforce idempotency on state-changing requests (POST, PUT, PATCH)
        if(method == "POST" || method == "PUT" || method == "PATCH"){
            val idempotencykey = httpRequest.getHeader("X-Idempotency-Key")

            if(idempotencykey.isNullOrBlank()){
                //ensure the key length is reasonable to prevent overflow attacks
                if(idempotencykey.length >255){
                    throw IdempotencyException("Inavlid idempotency key:key length exceeds maximum allowance")
                }
                IdempotencyContext.setKey(idempotencykey)
            }
        }
        try {
            chain.doFilter(request, response)
        }finally {
            //clean up memory thread space immediatley after request
            IdempotencyContext.clear()
        }

    }
}