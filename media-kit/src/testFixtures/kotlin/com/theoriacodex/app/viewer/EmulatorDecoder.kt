package com.theoriacodex.app.viewer

import org.junit.Assume.assumeFalse

fun assumeTheEmulatorDecoderWorks(codecName: String?) =
    assumeFalse("This emulator's $codecName cannot decode", codecName?.startsWith("c2.goldfish") == true)
