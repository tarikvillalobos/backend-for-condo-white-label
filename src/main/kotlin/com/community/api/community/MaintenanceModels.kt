package com.community.api.community

import com.community.api.core.*
import kotlinx.serialization.Serializable

@Serializable
data class StaffProfile(val userId: String, val responsibility: String, val active: Boolean = true)
@Serializable
data class ContractorInput(val name: String, val service: String, val contact: String, val approved: Boolean = true)
@Serializable
data class WorkOrderInput(val title: String, val description: String, val assignedTo: String,
    val scheduledAt: String, val contractorId: String? = null, val equipmentId: String? = null)
@Serializable
data class WorkOrder(val content: WorkOrderInput, val status: String = "scheduled", val completionNotes: String? = null, val evidence: List<String> = emptyList())
@Serializable
data class WorkOrderUpdate(val status: String, val notes: String, val evidence: List<String> = emptyList())
@Serializable
data class WorkOrderHistory(val orderId: String, val status: String, val notes: String, val evidence: List<String>)
@Serializable
data class EquipmentInput(val name: String, val description: String, val serialNumber: String? = null, val nextInspectionAt: String? = null)

internal fun validateWorkOrderTransition(previous: String, next: String) {
    val allowed = mapOf("scheduled" to setOf("in_progress", "cancelled"), "in_progress" to setOf("completed", "cancelled"),
        "completed" to setOf("scheduled"), "cancelled" to emptySet())
    if (next !in allowed.getOrDefault(previous, emptySet())) conflict("Invalid work order status transition")
}
