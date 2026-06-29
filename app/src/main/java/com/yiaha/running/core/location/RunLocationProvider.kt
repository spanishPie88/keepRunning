package com.yiaha.running.core.location

import com.yiaha.running.core.model.RunPoint

interface RunLocationProvider {
    fun start(onPoint: (RunPoint) -> Unit, onError: (Throwable) -> Unit)
    fun stop()
}

