package io.github.dreamlike.hotspot.methodbridge;

import java.lang.reflect.Method;

public final class JniMethodAddressBridge {
    private JniMethodAddressBridge() {
    }

    public static native long resolve(Method method);
}
