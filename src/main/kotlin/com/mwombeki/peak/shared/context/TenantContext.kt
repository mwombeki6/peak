package com.mwombeki.peak.shared.context
import java.util.UUID

object TenantContext {
    private val currentTenantId = ThreadLocal<UUID>()
    private val currentTenantUserId = ThreadLocal<UUID>()

    //save the tenantId when the user logs in
    fun setTenantId(tenantId: UUID){
        currentTenantId.set(tenantId)
    }

    //get tenantId whenever a repository needs to query a database
    fun getTenantId(): UUID{
        return currentTenantId.get()
    }

    fun setTenantUserId(userId: UUID){
        currentTenantUserId.set(userId)
    }

    fun getUserId(): UUID{
        return currentTenantUserId.get()
    }
    fun getTenantUserId(): UUID? {
        return currentTenantUserId.get()
    }

    //cleaning up the locker when the request finishes to avoid memory leaks
    fun clear(){
        currentTenantId.remove()
        currentTenantUserId.remove()
    }

    // Throws an error if a developer tries to run a query without setting a tenant first
    fun requireTenantId(): UUID {
        return getTenantId() ?: throw IllegalStateException("Security Violation: Tenant context is missing for this operation!")
    }

}