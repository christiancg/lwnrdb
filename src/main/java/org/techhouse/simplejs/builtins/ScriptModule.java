package org.techhouse.simplejs.builtins;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.exceptions.SimpleJsRuntimeException;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class ScriptModule {
    private ScriptModule() {
    }

    public static JsObject create(TextImporter importer, ResourceLimits limits, Intrinsics intrinsics) {
        final var module = new JsObject();
        module.set("importText",
                new JsNativeFunction("importText", (_, args) -> importText(importer, limits, intrinsics, args)));
        return module;
    }

    private static JsValue importText(TextImporter importer, ResourceLimits limits, Intrinsics intrinsics,
            List<JsValue> args) {
        if (limits == null || !limits.textImportEnabled()) {
            throw new JsThrowException(intrinsics.makeError("Error", "Script text import is not available"));
        }
        final var source = JsCoercion.toStr(arg(args, 0));
        final var explicitId = arg(args, 1);
        final var moduleId = explicitId instanceof JsUndefined
                ? "text:" + sha256(source)
                : JsCoercion.toStr(explicitId);
        return importer.importText(moduleId, source);
    }

    private static JsValue arg(List<JsValue> args, int index) {
        return index < args.size() ? args.get(index) : JsUndefined.getInstance();
    }

    private static String sha256(String source) {
        try {
            final var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new SimpleJsRuntimeException("SHA-256 is not available");
        }
    }
}
