package org.techhouse.simplejs.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class ModuleResult {
    public JsValue last = JsUndefined.getInstance();
    public final Map<String, JsValue> namedExports = new LinkedHashMap<>();
    public JsValue exportDefault;
    public boolean hasReturn;
    public JsValue returnValue = JsUndefined.getInstance();
}
