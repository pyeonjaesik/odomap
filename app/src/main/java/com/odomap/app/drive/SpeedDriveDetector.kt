package com.odomap.app.drive

import com.odomap.app.model.DriveStatus
import com.odomap.app.model.Fix
import com.odomap.app.model.Gear
import com.odomap.core.geo.haversineM

/**
 * Fallback drive detection from location only (no gear signal): moving faster than [movingKmh] for [startS] → driving (the drive starts at the
 * first moving fix of that stretch); slower for [parkS] → parked (the drive ends at the first still fix).
 * Pure — fed fix by fix, returns a status only when it changes.
 */
class SpeedDriveDetector(
    private val movingKmh: Double = 5.0,
    private val startS: Double = 10.0,
    private val parkS: Double = 180.0,
) {
    var driving = false
        private set
    private var movingSince: Long? = null
    private var stillSince: Long? = null
    private var prev: Fix? = null

    fun update(fix: Fix): DriveStatus? {
        val speedKmh = speedOf(fix) * 3.6
        prev = fix
        val moving = speedKmh > movingKmh
        if (!driving) {
            if (moving) {
                val since = movingSince ?: fix.t.also { movingSince = it }
                if ((fix.t - since) / 1000.0 >= startS) {
                    driving = true
                    stillSince = null
                    return DriveStatus(since, Gear.UNKNOWN, true, SOURCE)
                }
            } else {
                movingSince = null
            }
        } else {
            if (!moving) {
                val since = stillSince ?: fix.t.also { stillSince = it }
                if ((fix.t - since) / 1000.0 >= parkS) {
                    driving = false
                    movingSince = null
                    return DriveStatus(since, Gear.UNKNOWN, false, SOURCE)
                }
            } else {
                stillSince = null
            }
        }
        return null
    }

    private fun speedOf(fix: Fix): Double {
        fix.speed?.let { if (it.isFinite() && it >= 0) return it.toDouble() }
        val p = prev ?: return 0.0
        val dt = (fix.t - p.t) / 1000.0
        if (dt <= 0) return 0.0
        return haversineM(p.lat, p.lng, fix.lat, fix.lng) / dt
    }

    companion object {
        const val SOURCE = "speed"
    }
}
