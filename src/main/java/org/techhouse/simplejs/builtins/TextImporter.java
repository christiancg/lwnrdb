package org.techhouse.simplejs.builtins;

import org.techhouse.simplejs.values.JsValue;

@FunctionalInterface
public interface TextImporter {
    JsValue importText(String moduleId, String source);
}
