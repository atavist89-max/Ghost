package com.ghost.app.inference

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Monitors device thermal state and manages NPU to GPU fallback.
 * When device gets warm, switches from Hexagon NPU to GPU delegate.
 */
class ThermalMonitor(context: Context) {

    companion object {
        private const val TAG = "GhostThermal"

        // Thermal throttling thresholds
        private const val THERMAL_THRESHOLD_MODERATE = 3  // THERMAL_STATUS_MODERATE
        private const val THERMAL_THRESHOLD_SEVERE = 4    // THERMAL_STATUS_SEVERE

        // Consecutive high temperature reads before fallback
        private const val THERMAL_READ_THRESHOLD = 2
    }

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val _thermalState = MutableStateFlow(ThermalState.NORMAL)
    val thermalState: StateFlow<ThermalState> = _thermalState.asStateFlow()

    private val _shouldUseGpuFallback = MutableStateFlow(false)
    val shouldUseGpuFallback: StateFlow<Boolean> = _shouldUseGpuFallback.asStateFlow()

    private var consecutiveHighTempReads = 0
    private var lastThermalStatus = PowerManager.THERMAL_STATUS_NONE

    /**
     * Thermal state categories.
     */
    enum class ThermalState {
        NORMAL,      // Optimal for NPU
        ELEVATED,    // Slight warming, continue monitoring
        THROTTLING   // Should fallback to GPU
    }

    /**
     * Check current thermal status and update state.
     * Should be called periodically during inference.
     */
    fun checkThermalStatus() {
        val currentStatus = powerManager.currentThermalStatus

        val state = when (currentStatus) {
            PowerManager.THERMAL_STATUS_NONE,
            PowerManager.THERMAL_STATUS_LIGHT -> {
                consecutiveHighTempReads = 0
                ThermalState.NORMAL
            }
            PowerManager.THERMAL_STATUS_MODERATE -> {
                consecutiveHighTempReads++
                if (consecutiveHighTempReads >= THERMAL_READ_THRESHOLD) {
                    ThermalState.ELEVATED
                } else {
                    ThermalState.NORMAL
                }
            }
            PowerManager.THERMAL_STATUS_SEVERE,
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN -> {
                consecutiveHighTempReads = THERMAL_READ_THRESHOLD
                ThermalState.THROTTLING
            }
            else -> ThermalState.NORMAL
        }

        // Update fallback flag
        val shouldFallback = state == ThermalState.THROTTLING ||
                (state == ThermalState.ELEVATED && consecutiveHighTempReads >= THERMAL_READ_THRESHOLD * 2)

        if (shouldFallback != _shouldUseGpuFallback.value) {
            Log.i(TAG, "Thermal fallback changed: NPU -> GPU = $shouldFallback")
            _shouldUseGpuFallback.value = shouldFallback
        }

        if (state != _thermalState.value) {
            Log.i(TAG, "Thermal state: ${currentStatus} -> $state")
            _thermalState.value = state
        }

        lastThermalStatus = currentStatus
    }

    /**
     * Get current thermal status as human-readable string.
     */
    fun getThermalStatusString(): String {
        return when (powerManager.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "None"
            PowerManager.THERMAL_STATUS_LIGHT -> "Light"
            PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
            else -> "Unknown"
        }
    }

    /**
     * Reset thermal monitoring state.
     * Call when starting a new inference session.
     */
    fun reset() {
        consecutiveHighTempReads = 0
        _thermalState.value = ThermalState.NORMAL
        _shouldUseGpuFallback.value = false
        Log.d(TAG, "Thermal monitor reset")
    }

    /**
     * Check if device is currently thermally throttling.
     */
    fun isThrottling(): Boolean {
        return _thermalState.value == ThermalState.THROTTLING
    }

    /**
     * Get recommended backend based on thermal state.
     * @return true for GPU, false for NPU
     */
    fun shouldUseGpu(): Boolean {
        return _shouldUseGpuFallback.value
    }
}
