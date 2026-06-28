package io.github.dreamlike.hotspot.methodbridge;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HotSpotMethodBridgeTest {
    private static long targetCount;
    private static long carrierCount;
    private static Method target;
    private static Method compiledCaller;
    private static HotSpotMethodBridge.MethodState initialTarget;
    private static HotSpotMethodBridge.MethodState carrierState;
    private static HotSpotMethodBridge.MethodState linkedTarget;
    private static HotSpotMethodBridge.Link link;

    public static void target() {
        targetCount++;
    }

    public static void carrier() {
        carrierCount++;
    }

    public static void interpretedCaller() {
        target();
    }

    public static void compiledCaller() {
        target();
    }

    @BeforeAll
    static void linkTargetToCarrier() throws Exception {
        target = HotSpotMethodBridgeTest.class.getDeclaredMethod("target");
        Method carrier = HotSpotMethodBridgeTest.class.getDeclaredMethod("carrier");
        compiledCaller = HotSpotMethodBridgeTest.class.getDeclaredMethod("compiledCaller");

        initialTarget = HotSpotMethodBridge.state(target);
        assertEquals(0, initialTarget.code());
        assertTrue(HotSpotMethodBridge.compileNow(carrier, 4));
        carrierState = HotSpotMethodBridge.waitForCode(carrier, 5_000);
        assertNotEquals(0, carrierState.code());

        link = HotSpotMethodBridge.linkToCarrier(target, carrier);
        linkedTarget = HotSpotMethodBridge.state(target);

        assertTrue(HotSpotMethodBridge.compileNow(compiledCaller, 4));
        assertNotEquals(0, HotSpotMethodBridge.waitForCode(compiledCaller, 5_000).code());
    }

    @BeforeEach
    void resetCounters() {
        targetCount = 0;
        carrierCount = 0;
    }

    @Test
    @DisplayName("link 后 target 的可变入口字段指向 carrier 的 nmethod")
    void targetEntriesPointToCarrierNmethod() {
        assertEquals(carrierState.code(), link.code());
        assertEquals(carrierState.code(), linkedTarget.code());
        assertEquals(carrierState.compiledEntry(), linkedTarget.compiledEntry());
        assertEquals(carrierState.interpretedEntry(), linkedTarget.interpretedEntry());
        assertEquals(initialTarget.interpreterEntry(), linkedTarget.interpreterEntry());
        assertTrue(HotSpotMethodBridge.isCurrent(link));
    }

    @Test
    @DisplayName("target 被请求编译后不会覆盖已经安装的 carrier nmethod")
    void targetCompileRequestDoesNotOverwriteLink() {
        HotSpotMethodBridge.MethodState beforeTargetCompile = HotSpotMethodBridge.state(target);
        HotSpotMethodBridge.compileNow(target, 1);
        assertEquals(beforeTargetCompile, HotSpotMethodBridge.state(target));
        assertTrue(HotSpotMethodBridge.isCurrent(link));
    }

    @Test
    @DisplayName("解释器调用 target 时实际执行 carrier")
    void interpretedCallRunsCarrier() {
        interpretedCaller();
        assertEquals(0, targetCount);
        assertEquals(1, carrierCount);
    }

    @Test
    @DisplayName("已编译 caller 调用 target 时不会内联 target bytecode")
    void compiledCallerDoesNotInlineTarget() {
        for (int i = 0; i < 10_000; i++) {
            compiledCaller();
        }
        assertEquals(0, targetCount);
        assertEquals(10_000, carrierCount);
    }
}
