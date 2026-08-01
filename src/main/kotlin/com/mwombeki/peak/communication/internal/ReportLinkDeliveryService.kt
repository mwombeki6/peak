package com.mwombeki.peak.communication.internal

import com.mwombeki.peak.communication.api.DeliverReportLinkCommand
import com.mwombeki.peak.communication.api.ReportLinkDeliveryPort
import com.mwombeki.peak.communication.api.ReportLinkDeliveryResult
import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestIdentity
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@Service
class ReportLinkDeliveryService(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val databaseSessionContext: DatabaseSessionContext,
    private val providers: List<NotificationDeliveryProvider>,
) : ReportLinkDeliveryPort {
    override fun deliver(
        command: DeliverReportLinkCommand,
    ): ReportLinkDeliveryResult {
        require(command.expiresAt.isAfter(Instant.now())) {
            "Report link is already expired"
        }
        val channel = transactionTemplate.execute {
            databaseSessionContext.bind(
                RequestIdentity.Public(
                    tenantId = command.tenantId,
                    propertyId = command.propertyId,
                    correlationId = "report-link-${command.reportDeliveryId}",
                ),
            )
            jdbcTemplate.query(
                """
                SELECT contact_channel.channel_type,
                       contact_channel.address,
                       mask_contact_channel_address(
                           contact_channel.channel_type,
                           contact_channel.address
                       ) AS destination_masked
                FROM report_subscription_recipients recipient
                JOIN contact_channels contact_channel
                  ON contact_channel.tenant_id = recipient.tenant_id
                 AND contact_channel.contact_id = recipient.contact_id
                 AND contact_channel.id = recipient.contact_channel_id
                WHERE recipient.tenant_id = ?
                  AND recipient.contact_id = ?
                  AND recipient.contact_channel_id = ?
                  AND recipient.is_enabled = true
                  AND contact_channel.is_active = true
                  AND contact_channel.deleted_at IS NULL
                  AND contact_channel.verification_status = 'verified'
                  AND contact_channel.channel_type IN ('email', 'whatsapp')
                  AND contact_channel_can_receive(
                        recipient.tenant_id,
                        recipient.contact_id,
                        recipient.contact_channel_id,
                        'operational_reports'
                      )
                LIMIT 1
                """.trimIndent(),
                { rs, _ ->
                    ResolvedChannel(
                        type = rs.getString("channel_type"),
                        address = rs.getString("address"),
                        masked = rs.getString("destination_masked"),
                    )
                },
                command.tenantId,
                command.contactId,
                command.contactChannelId,
            ).singleOrNull()
        } ?: error(
            "Report recipient channel is not verified or lacks operational_reports consent",
        )
        val provider = providers.firstOrNull { it.supports(channel.type) }
            ?: error("No communication provider supports ${channel.type}")
        val subject = "Peak report: ${command.reportCode}"
        val content = buildString {
            append("Your ")
            append(command.reportCode.replace('_', ' '))
            append(" for ")
            append(command.businessDate)
            append(" is available at ")
            append(command.signedUrl)
            append(". This link expires at ")
            append(command.expiresAt)
            append('.')
        }
        val result = provider.send(
            NotificationDeliveryCommand(
                deliveryRequestId = command.reportDeliveryId,
                outboxEventId = command.reportDeliveryId,
                tenantId = command.tenantId,
                propertyId = command.propertyId,
                channel = channel.type,
                recipient = channel.address,
                subject = if (channel.type == "email") subject else null,
                content = content,
            ),
        )
        return ReportLinkDeliveryResult(
            channelType = channel.type,
            destinationMasked = channel.masked,
            providerCode = provider.providerCode,
            providerMessageId = result.providerMessageId,
        )
    }

    private data class ResolvedChannel(
        val type: String,
        val address: String,
        val masked: String,
    )
}
