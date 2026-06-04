package io.github.lene.optix.smoke;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class SmokeTest {
    @Test
    void nativeLibraryLoads() {
        assertDoesNotThrow(() -> System.loadLibrary("optix_jni"));
    }
}
