package io.github.dreamlike.hotspot.codecache;

import java.util.Arrays;

public final class HotSpotCodeCache {
    private HotSpotCodeCache() {
    }

    public static CodeCacheEntry[] snapshot() {
        return CodeCacheScanner.load().snapshot();
    }

    public static CodeCacheEntry[] nmethods() {
        return Arrays.stream(snapshot())
                .filter(entry -> entry.kind() == CodeBlobKind.NMETHOD)
                .toArray(CodeCacheEntry[]::new);
    }
}
