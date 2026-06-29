package io.github.dreamlike.hotspot.codecache;

/**
 * 一条 best-effort 的 CodeCache 快照记录。
 *
 * <p>扫描器不会获取 {@code CodeCache_lock}，所以这里的所有字段都是对当前 VM 状态的观察结果。
 * 只对 {@code nmethod} 有意义的字段，在其他 CodeBlob 类型上会使用 {@code null}、{@code 0}、
 * {@code false} 或 {@link Integer#MIN_VALUE} 表示不可用。{@code name} 不是枚举，它来自
 * {@code CodeBlob::_name} 这个 {@code const char*}。
 *
 * @param blobAddress CodeCache 中的 CodeBlob/nmethod 对象地址。
 * @param kind {@code CodeBlob::_kind}，对应 HotSpot 的 {@code CodeBlobKind} 枚举。
 * @param name {@code CodeBlob::_name}；nmethod 通常是 {@code nmethod} 或 {@code native nmethod}。
 * @param codeBegin {@code CodeBlob::code_begin()} 的起始地址。
 * @param codeEnd {@code CodeBlob::code_end()} 的结束地址，左闭右开。
 * @param codeLength {@code [codeBegin, codeEnd)} 的字节长度；对 nmethod 来说包含指令区和 stub 代码区。
 * @param compileId {@code nmethod::_compile_id}；非 nmethod 时为 {@link Integer#MIN_VALUE}。
 * @param compileLevel {@code nmethod::_comp_level}，对应 HotSpot 的 {@code CompLevel} 枚举；非 nmethod 时为 {@code null}。
 * @param methodSignature 来源 Java 方法，格式为 {@code holder::name(descriptor)}；非 nmethod 时为 {@code null}。
 * @param entryBci {@code nmethod::_entry_bci}；{@code InvocationEntryBci} 表示普通入口，其他值表示 OSR bci。
 * @param osr 这个 nmethod 是否是 on-stack replacement 入口。
 * @param state {@code nmethod::_state}，对应 HotSpot nmethod 内部的匿名状态枚举；非 nmethod 时为 {@code null}。
 * @param stateValue 原始 signed {@code nmethod::_state} 值；非 nmethod 时为 {@link Integer#MIN_VALUE}。
 * @param exceptionStubAddress {@code nmethod::exception_begin()} 地址；没有 exception stub 时为 {@code 0}。
 * @param exceptionType best-effort 的异常类型。V1 不能只从 {@code _exception_offset} 反推出 Java catch 类型，所以存在 stub 时为 {@code unknown}。
 * @param handlerTableLength nmethod immutable data 中 exception handler table 区域的字节长度。
 * @param implicitExceptionTableLength implicit exception table 区域的字节长度，通常用于 null-check continuation。
 */
public record CodeCacheEntry(
        long blobAddress,
        CodeBlobKind kind,
        String name,
        long codeBegin,
        long codeEnd,
        int codeLength,
        int compileId,
        CompilationLevel compileLevel,
        String methodSignature,
        int entryBci,
        boolean osr,
        NMethodState state,
        int stateValue,
        long exceptionStubAddress,
        String exceptionType,
        int handlerTableLength,
        int implicitExceptionTableLength) {
}
