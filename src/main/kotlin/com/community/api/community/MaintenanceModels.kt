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
