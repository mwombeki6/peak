package com.mwombeki.peak.usermanagement.api

interface TenantUserInvitationPort {
    fun inviteTenantUser(command: InviteTenantUserCommand): TenantUserInvitationReceipt
}
