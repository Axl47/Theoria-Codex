package com.theoriacodex.app.viewer

import androidx.test.platform.app.InstrumentationRegistry

internal val deviceTestInfrastructure by lazy { VideoPlaybackInfrastructure(InstrumentationRegistry.getInstrumentation().targetContext) }
