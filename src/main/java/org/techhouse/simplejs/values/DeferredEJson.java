package org.techhouse.simplejs.values;

import org.techhouse.ejson.elements.JsonBaseElement;

final class DeferredEJson extends JsValue {
    private final JsonBaseElement element;

    DeferredEJson(JsonBaseElement element) {
        this.element = element;
    }

    JsValue resolve() {
        return EJsonInterop.viewOrValue(element);
    }
}
