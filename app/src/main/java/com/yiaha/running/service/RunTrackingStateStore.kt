package com.yiaha.running.service

import com.yiaha.running.core.model.RunSessionSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object RunTrackingStateStore {
    private val mutableSnapshot = MutableStateFlow(RunSessionSnapshot())
    val snapshot: StateFlow<RunSessionSnapshot> = mutableSnapshot.asStateFlow()

    fun update(snapshot: RunSessionSnapshot) {
        mutableSnapshot.value = snapshot
    }
}

