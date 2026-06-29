package io.github.dreamlike.hotspot.codecache;

public enum CodeBlobKind {
    NMETHOD,
    BUFFER,
    ADAPTER,
    VTABLE,
    METHOD_HANDLES_ADAPTER,
    RUNTIME_STUB,
    DEOPTIMIZATION,
    SAFEPOINT,
    EXCEPTION,
    UNCOMMON_TRAP,
    UPCALL,
    UNKNOWN
}
