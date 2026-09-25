package com.mrsep.musicrecognizer.core.domain.maintenance

import kotlinx.coroutines.flow.StateFlow

sealed interface DataMaintenanceState {
    data object Idle : DataMaintenanceState
    data class Exclusive(val operation: DataMaintenanceOperation) : DataMaintenanceState
}

enum class DataMaintenanceOperation { Backup, Restore }

interface DataMaintenance {
    val state: StateFlow<DataMaintenanceState>
}

val DataMaintenance.isExclusive: Boolean
    get() = state.value is DataMaintenanceState.Exclusive
