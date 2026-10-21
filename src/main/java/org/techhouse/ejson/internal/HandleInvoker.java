package org.techhouse.ejson.internal;

import java.lang.invoke.MethodHandle;

final class HandleInvoker {
    private HandleInvoker() {
    }

    static Object apply(MethodHandle unary, Object argument) throws Exception {
        try {
            return (Object) unary.invokeExact(argument);
        } catch (Throwable thrown) {
            throw rethrown(thrown);
        }
    }

    static void accept(MethodHandle binary, Object first, Object second) throws Exception {
        try {
            binary.invokeExact(first, second);
        } catch (Throwable thrown) {
            throw rethrown(thrown);
        }
    }

    static Object supply(MethodHandle nullary) throws Exception {
        try {
            return (Object) nullary.invokeExact();
        } catch (Throwable thrown) {
            throw rethrown(thrown);
        }
    }

    static Object spread(MethodHandle spreader, Object[] arguments) throws Exception {
        try {
            return (Object) spreader.invokeExact(arguments);
        } catch (Throwable thrown) {
            throw rethrown(thrown);
        }
    }

    private static Exception rethrown(Throwable thrown) {
        if (thrown instanceof Error error) {
            throw error;
        }
        return (Exception) thrown;
    }
}
