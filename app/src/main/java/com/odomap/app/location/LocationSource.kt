package com.odomap.app.location

import com.odomap.app.model.DriveStatus
import com.odomap.app.model.Fix
import com.odomap.app.model.SourceEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map

/** Location fixes (~1 Hz). The flow is cold: collecting starts the source, cancelling stops it. */
interface LocationSource {
    val name: String

    fun fixes(): Flow<Fix>
}

/** Gear / driving state. Cold flow like [LocationSource]. */
interface DriveStateSource {
    val name: String

    fun states(): Flow<DriveStatus>
}

/** Locations and drive states in one ordered stream — what the recorder consumes. */
interface EventSource {
    val name: String

    fun events(): Flow<SourceEvent>
}

fun EventSource.asLocationSource(): LocationSource =
    object : LocationSource {
        override val name = this@asLocationSource.name

        override fun fixes(): Flow<Fix> = events().filterIsInstance<SourceEvent.Loc>().map { it.fix }
    }

fun EventSource.asDriveStateSource(): DriveStateSource =
    object : DriveStateSource {
        override val name = this@asDriveStateSource.name

        override fun states(): Flow<DriveStatus> = events().filterIsInstance<SourceEvent.State>().map { it.status }
    }
