package org.techhouse.simplejs.values;

// A property descriptor in the shape ToPropertyDescriptor produces: every field is nullable because
// [[DefineOwnProperty]] has to tell an attribute that was absent from the descriptor object apart
// from one explicitly set to undefined/false.
public record PropertyDescriptor(JsValue value, JsValue getter, JsValue setter, Boolean writable, Boolean enumerable,
        Boolean configurable) {
    public static PropertyDescriptor data(JsValue value, JsObject.PropertyFlags flags) {
        return new PropertyDescriptor(value, null, null, flags.writable(), flags.enumerable(), flags.configurable());
    }

    public static PropertyDescriptor accessor(JsValue getter, JsValue setter, JsObject.PropertyFlags flags) {
        return new PropertyDescriptor(null, getter == null ? JsUndefined.getInstance() : getter,
                setter == null ? JsUndefined.getInstance() : setter, null, flags.enumerable(), flags.configurable());
    }

    public boolean isAccessorDescriptor() {
        return getter != null || setter != null;
    }

    public boolean writableOr(boolean current) {
        return writable == null ? current : writable;
    }

    public boolean enumerableOr(boolean current) {
        return enumerable == null ? current : enumerable;
    }

    public boolean configurableOr(boolean current) {
        return configurable == null ? current : configurable;
    }
}
