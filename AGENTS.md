# AGENTS.md

1. 保持项目最小化，不引入非必要依赖。
2. Java 代码使用 JDK 25，优先用标准库和当前 HotSpot 导出的 VMStructs。
3. 不把 `-Xcomp`、`-Xbatch` 或 warmup loop 作为方案前提。
4. 符号解析按平台维护：macOS 是 Mach-O，Linux 是 ELF64，Windows 是 PE/COFF 但当前只保留未实现占位；不要把 mangled symbol 从平台实现泄漏到业务调用点。
5. 文件格式解析统一用 `MemorySegment.ofArray` 和显式 `ByteOrder`，不要用 `ByteBuffer`。
6. 修改后至少在 macOS 运行 `./run.sh`；涉及 ELF 或跨平台符号解析时，还要在 Linux 运行 macos和linux都是基于sdkman进行管理的 如果你在linux上则不要运行macos 在macos上要找orbstack进行执行
7. 解释或修改 JVM 内部结构时，先让用户提供本地 JDK 源码根目录，或使用当前环境已有的 JDK 源码根目录；再按这些相对路径核对：`src/hotspot/share/runtime/vmStructs.hpp`、`src/hotspot/share/runtime/vmStructs.cpp`、`src/hotspot/share/oops/method.hpp`、`src/hotspot/share/oops/method.cpp`、`src/hotspot/share/oops/methodFlags.hpp`、`src/hotspot/share/prims/jni.cpp`、`src/hotspot/share/prims/whitebox.hpp`、`src/hotspot/share/prims/whitebox.cpp`、`src/hotspot/share/ci/ciEnv.cpp`、`src/hotspot/share/ci/ciMethod.cpp`、`src/hotspot/share/opto/bytecodeInfo.cpp`、`src/hotspot/share/c1/c1_GraphBuilder.cpp`。

## JVM 内部结构速查

- `VMStructEntry`：HotSpot 导给外部观察者的字段元数据表，用来查 `Method::_code` 等对象内偏移；看 `src/hotspot/share/runtime/vmStructs.hpp` 和 `src/hotspot/share/runtime/vmStructs.cpp`。
- `Method`：Java 方法在 HotSpot 里的运行时元数据，本项目读写 `_code`、`_from_compiled_entry`、`_from_interpreted_entry`、`_i2i_entry`；看 `src/hotspot/share/oops/method.hpp` 和 `src/hotspot/share/oops/method.cpp`。
- `MethodFlags`：`Method` 的运行期状态位，本项目设置 not-compilable 和 `dont_inline`；看 `src/hotspot/share/oops/methodFlags.hpp`。
- `jmethodID` / `Method*`：通过 JNI `FromReflectedMethod` 拿 `jmethodID`，再用 `Method::checked_resolve_jmethod_id` 得到 `Method*`；看 `src/hotspot/share/prims/jni.cpp` 和 `src/hotspot/share/oops/method.cpp`。
- `nmethod`：JIT 生成的 native code 对象，`Method::_code` 指向它；安装和失效行为主要看 `src/hotspot/share/oops/method.cpp`、`src/hotspot/share/ci/ciEnv.cpp`。
- `WhiteBox::compile_method`：本项目绕过 Java WhiteBox API，直接调用内部 C++ 入口强制编译 carrier；看 `src/hotspot/share/prims/whitebox.hpp` 和 `src/hotspot/share/prims/whitebox.cpp`。
- C1/C2 inline 判断：确认 `dont_inline` 是否生效时看 `src/hotspot/share/c1/c1_GraphBuilder.cpp` 和 `src/hotspot/share/opto/bytecodeInfo.cpp`。
