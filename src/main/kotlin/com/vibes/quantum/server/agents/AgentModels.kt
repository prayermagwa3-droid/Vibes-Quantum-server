package com.vibes.quantum.server.agents

import kotlinx.serialization.Serializable

@Serializable
data class AgentTaskRequest(
    val agent: String,
    val task: String,
    val context: List<String> = emptyList(),
    val action: String = "assist",
    val language: String = "python",
    val code: String? = null,
    val evidence: List<String> = emptyList(),
    val maxCostUsd: Double = 0.03,
    val onlineBoost: Boolean = false
)

@Serializable
data class AgentDefinition(
    val id: String,
    val name: String,
    val scope: String,
    val allowedTools: List<String>,
    val timeBudgetMillis: Long,
    val tokenBudget: Int,
    val enabled: Boolean = true
)

@Serializable
data class AgentTaskResult(
    val completionStatus: String,
    val output: String? = null,
    val provenance: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val providerId: String? = null,
    val toolsUsed: List<String> = emptyList(),
    val modelId: String? = null
)

@Serializable
data class AgentAuditRecord(
    val agentVersion: String,
    val providerId: String? = null,
    val modelId: String? = null,
    val toolsUsed: List<String> = emptyList(),
    val outcome: String,
    val createdAt: String,
    val completedAt: String? = null,
    val approvalRequired: Boolean = false,
    val approvedAt: String? = null,
    val warningCodes: List<String> = emptyList()
)

@Serializable
data class AgentTaskRecord(
    val id: String,
    val agent: String,
    val action: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    val progress: List<String>,
    val result: AgentTaskResult? = null,
    val audit: AgentAuditRecord,
    val cancellationRequested: Boolean = false
)

@Serializable
data class AgentTaskListResponse(val tasks: List<AgentTaskRecord>, val count: Int)

@Serializable
data class AgentCapabilityResponse(
    val agents: List<AgentDefinition>,
    val futureCapabilities: List<FutureAgentCapability>,
    val approvalRequiredActions: List<String>,
    val note: String
)

@Serializable
data class FutureAgentCapability(
    val id: String,
    val name: String,
    val status: String,
    val requirements: String
)

@Serializable
data class AgentApiError(val error: String, val hint: String? = null)
