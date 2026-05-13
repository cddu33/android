package io.homeassistant.companion.android.util.vehicle

import androidx.car.app.CarContext
import androidx.car.app.constraints.ConstraintManager

/**
 * Returns whether the Car App host signals that the user is currently driving and the UI
 * must respect distraction-optimization restrictions.
 *
 * Unlike [android.car.drivingstate.CarUxRestrictionsManager] (which only exists on Android
 * Automotive OS and isn't reliably driven by the AVD's VHAL simulator), the Car App
 * library's [ConstraintManager] is updated by the host on every UX restriction change and
 * works consistently on Automotive OS, Android Auto and the AVD simulator.
 *
 * Per the Car App library spec, [ConstraintManager.CONTENT_LIMIT_TYPE_GRID] returns at
 * least [PARKED_GRID_LIMIT] items while parked and a lower value while driving, so we use
 * that as a portable proxy for the driving state.
 */
internal fun CarContext.isDrivingDistracted(): Boolean {
    val manager = getCarService(ConstraintManager::class.java)
    return manager.getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID) < PARKED_GRID_LIMIT
}

private const val PARKED_GRID_LIMIT = 8
