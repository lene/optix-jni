package io.github.lene.optix.smoke

import kotlin.test.Test

class SmokeTest {
    @Test
    fun nativeLibraryLoads() {
        System.loadLibrary("optix_jni")
    }
}
