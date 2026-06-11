package com.mwombeki.peak.shared.context

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager

@Component
class DatabaseSessionContext(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun bind(identity: RequestIdentity) {
        require(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Database session context must be bound inside an active transaction"
        }

        clear()

        when (identity) {
            is RequestIdentity.Tenant -> {
                setLocal(CURRENT_TENANT_ID, identity.tenantId.toString())
                setLocal(CURRENT_TENANT_USER_ID, identity.tenantUserId.toString())
            }

            is RequestIdentity.Platform -> {
                setLocal(CURRENT_PLATFORM_USER_ID, identity.platformUserId.toString())
            }

            is RequestIdentity.Support -> {
                setLocal(CURRENT_PLATFORM_USER_ID, identity.platformUserId.toString())
            }

            is RequestIdentity.Public -> Unit
        }

        jdbcTemplate.execute("select assert_no_mixed_context()")
    }

    fun clear() {
        setLocal(CURRENT_TENANT_ID, "")
        setLocal(CURRENT_TENANT_USER_ID, "")
        setLocal(CURRENT_PLATFORM_USER_ID, "")
    }

    private fun setLocal(name: String, value: String) {
        jdbcTemplate.queryForObject(
            "select set_config(?, ?, true)",
            String::class.java,
            name,
            value,
        )
    }

    private companion object {
        const val CURRENT_TENANT_ID = "app.current_tenant_id"
        const val CURRENT_TENANT_USER_ID = "app.current_tenant_user_id"
        const val CURRENT_PLATFORM_USER_ID = "app.current_platform_user_id"
    }
}
