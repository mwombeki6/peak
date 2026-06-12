package com.mwombeki.peak.shared.context

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class TenantContextFilter: Filter {
    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        val authentication = SecurityContextHolder.getContext().authentication

       //checking if the user is logged in with a valid jwt token
        if(authentication !==null && authentication.principal is Jwt){
            val jwt = authentication.principal as Jwt

            val tenantIdClaim = jwt.getClaimAsString("tenant_id")
            val userIdClaim = jwt.getClaimAsString("user_id")

            if(!tenantIdClaim.isNullOrBlank() ){
                TenantContext.setTenantId(UUID.fromString(tenantIdClaim))
            }
            if(!userIdClaim.isNullOrBlank()){
                TenantContext.setTenantUserId(UUID.fromString(userIdClaim))
            }
        }
        try {
            //let the request go to the services and controllersss
            chain.doFilter(request, response)
        }finally {
            //always wipe the context clean to avoid the leaks btn request
            TenantContext.clear()
        }
    }
}