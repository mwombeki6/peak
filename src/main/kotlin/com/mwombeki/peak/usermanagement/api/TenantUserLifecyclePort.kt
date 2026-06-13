package com.mwombeki.peak.usermanagement.api

interface TenantUserLifecyclePort {
    fun changeTenantUserLifecycle(
        command: TenantUserLifecycleCommand,
    ): TenantUserLifecycleReceipt

    fun revokeTenantUserIdentityLink(
        command: RevokeTenantUserIdentityLinkCommand,
    ): TenantUserIdentityLinkRevocationReceipt
}
