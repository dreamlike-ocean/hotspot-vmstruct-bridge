package io.github.dreamlike.hotspot.codecache;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotSpotCodeCacheTest {
    @Test
    void snapshotReadsCurrentCodeCache() {
        CodeCacheEntry[] entries = HotSpotCodeCache.snapshot();

        assertTrue(entries.length > 0);
        assertTrue(Arrays.stream(entries).allMatch(entry -> entry.codeLength() >= 0));
        assertTrue(Arrays.stream(entries).anyMatch(entry -> entry.kind() != null));
        assertNotNull(entries[0]);
    }

    @Test
    void nmethodsOnlyContainsEntriesWithMethodMetadata() {
        CodeCacheEntry[] entries = HotSpotCodeCache.nmethods();

        assertTrue(Arrays.stream(entries).allMatch(entry -> entry.kind() == CodeBlobKind.NMETHOD));
        assertTrue(Arrays.stream(entries).allMatch(entry -> entry.compileLevel() != null));
        assertTrue(Arrays.stream(entries).allMatch(entry -> entry.state() != null));
        assertTrue(Arrays.stream(entries).anyMatch(entry -> entry.methodSignature() != null));
    }
}
