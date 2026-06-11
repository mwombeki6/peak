package com.mwombeki.peak.usermanagement.internal.web

import com.mwombeki.peak.usermanagement.api.GuardMode
import com.mwombeki.peak.usermanagement.api.RouteScope

data class RouteAccessRule(
    val moduleId: String,
    val httpMethod: String,
    val apiPattern: String,
    val permissionCode: String?,
    val routeScope: RouteScope,
    val guardMode: GuardMode,
)
