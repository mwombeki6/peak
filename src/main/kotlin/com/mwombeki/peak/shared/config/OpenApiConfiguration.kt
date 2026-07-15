package com.mwombeki.peak.shared.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import java.util.Locale
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class OpenApiConfiguration {

    @Bean
    fun peakOpenApi(): OpenAPI {
        return OpenAPI()
            .info(
                Info()
                    .title("Peak V1 API")
                    .version("1.0")
                    .description("Backward-compatible hospitality operations API")
                    .license(License().name("Proprietary")),
            )
            .servers(listOf(Server().url("/").description("Current deployment")))
            .components(
                Components()
                    .addSecuritySchemes(
                        BEARER_AUTH,
                        SecurityScheme()
                            .type(SecurityScheme.Type.HTTP)
                            .scheme("bearer")
                            .bearerFormat("JWT"),
                    )
                    .addSchemas(
                        API_PROBLEM,
                        ObjectSchema()
                            .description("RFC 9457 problem details with request correlation metadata")
                            .addProperty("type", StringSchema().format("uri"))
                            .addProperty("title", StringSchema())
                            .addProperty("status", IntegerSchema().format("int32"))
                            .addProperty("detail", StringSchema())
                            .addProperty("instance", StringSchema().format("uri"))
                            .addProperty("traceId", StringSchema())
                            .addProperty("path", StringSchema()),
                    ),
            )
            .addSecurityItem(SecurityRequirement().addList(BEARER_AUTH))
    }

    @Bean
    fun peakOperationContractCustomizer(): OpenApiCustomizer {
        return OpenApiCustomizer { openApi ->
            if (openApi.servers.isNullOrEmpty()) {
                openApi.addServersItem(Server().url("/").description("Current deployment"))
            }
            openApi.components.addSchemas(
                API_PROBLEM,
                ObjectSchema()
                    .description("RFC 9457 problem details with request correlation metadata")
                    .addProperty("type", StringSchema().format("uri"))
                    .addProperty("title", StringSchema())
                    .addProperty("status", IntegerSchema().format("int32"))
                    .addProperty("detail", StringSchema())
                    .addProperty("instance", StringSchema().format("uri"))
                    .addProperty("traceId", StringSchema())
                    .addProperty("path", StringSchema()),
            )
            openApi.paths.orEmpty().forEach { (path, pathItem) ->
                pathItem.readOperationsMap().forEach { (method, operation) ->
                    val summary = operation.operationId
                        ?.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
                        ?.replace('_', ' ')
                        ?.replaceFirstChar { character -> character.uppercase() }
                        ?: "${method.name.lowercase(Locale.ROOT)} $path"
                    operation.summary = operation.summary ?: summary
                    operation.description = operation.description ?: summary
                    operation.responses = (operation.responses ?: ApiResponses()).apply {
                        if (this["400"] == null) {
                            addApiResponse("400", problemResponse("Invalid request"))
                        }
                        if (this["401"] == null) {
                            addApiResponse("401", problemResponse("Authentication required"))
                        }
                        if (this["403"] == null) {
                            addApiResponse("403", problemResponse("Insufficient permission"))
                        }
                        if (this["404"] == null) {
                            addApiResponse("404", problemResponse("Resource not found"))
                        }
                    }

                    if (method.name.equals(HttpMethod.POST.name(), ignoreCase = true) &&
                        path == CLICKPESA_WEBHOOK_PATH
                    ) {
                        operation.security = emptyList()
                    }

                    if (method.name.uppercase(Locale.ROOT) in UNSAFE_METHODS) {
                        operation.addParametersItem(
                            io.swagger.v3.oas.models.parameters.HeaderParameter()
                                .name("Idempotency-Key")
                                .required(false)
                                .description(
                                    "Required by replay-protected commands; reuse returns the stored response.",
                                )
                                .schema(io.swagger.v3.oas.models.media.StringSchema().maxLength(200)),
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val BEARER_AUTH = "bearerAuth"
        const val API_PROBLEM = "ApiProblem"
        const val CLICKPESA_WEBHOOK_PATH =
            "/api/v1/payments/webhooks/clickpesa/{providerAccountId}"
        val UNSAFE_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")

        fun problemResponse(description: String): ApiResponse {
            return ApiResponse()
                .description(description)
                .content(
                    io.swagger.v3.oas.models.media.Content().addMediaType(
                        "application/problem+json",
                        io.swagger.v3.oas.models.media.MediaType().schema(
                            io.swagger.v3.oas.models.media.Schema<Any>()
                                .`$ref`("#/components/schemas/$API_PROBLEM"),
                        ),
                    ),
                )
        }
    }
}
