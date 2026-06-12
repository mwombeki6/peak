package com.mwombeki.peak.usermanagement.api

interface TenantUserInvitationPort {
    fun inviteTenantUser(command: InviteTenantUserCommand): TenantUserInvitationReceipt

    fun acceptTenantUserInvitation(
        command: AcceptTenantUserInvitationCommand,
    ): TenantUserInvitationAcceptanceReceipt
}
