package org.techhouse.simplejs.values;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.nodes.JsNode;

public final class JsFunction extends JsValue implements JsCallableProperties {
    private String name;
    private final List<JsNode> params;
    private final JsNode body;
    private final boolean arrow;
    private final boolean expressionBody;
    private final boolean async;
    private final boolean generator;
    private final Environment closure;
    private final PropertyTable table = new PropertyTable();
    private Set<String> deletedMetadataKeys;
    private JsValue prototype;
    // [[SourceText]]: the text of the function-like production this closure came from, or null when
    // the parser had no source to slice - see JsNode.sourceText.
    private String sourceText;
    private String moduleName;
    // Concise methods and accessors are not constructors, so they have no `prototype` property.
    private boolean method;
    private boolean derivedConstructor;

    public JsFunction(String name, List<JsNode> params, JsNode body, boolean arrow, boolean expressionBody,
            boolean async, boolean generator, Environment closure) {
        this.name = name;
        this.params = params;
        this.body = body;
        this.arrow = arrow;
        this.expressionBody = expressionBody;
        this.async = async;
        this.generator = generator;
        this.closure = closure;
    }

    public String getModuleName() {
        return moduleName;
    }

    public void setModuleName(String moduleName) {
        this.moduleName = moduleName;
    }

    public String getName() {
        return name;
    }

    public void setInferredName(String inferred) {
        if (name == null) {
            name = inferred;
        }
    }

    public String getSourceText() {
        return sourceText;
    }

    public void setSourceText(String sourceText) {
        this.sourceText = sourceText;
    }

    public List<JsNode> getParams() {
        return params;
    }

    public JsNode getBody() {
        return body;
    }

    public boolean isArrow() {
        return arrow;
    }

    public boolean isExpressionBody() {
        return expressionBody;
    }

    public boolean isAsync() {
        return async;
    }

    public boolean isGenerator() {
        return generator;
    }

    public boolean isDerivedConstructor() {
        return derivedConstructor;
    }

    public void markDerivedConstructor() {
        derivedConstructor = true;
    }

    public boolean isMethod() {
        return method;
    }

    public boolean isConstructor() {
        return !arrow && !async && !generator && !method;
    }

    public void markMethod() {
        this.method = true;
    }

    public Environment getClosure() {
        return closure;
    }

    // Properties of Generator/AsyncGenerator Function Instances: unlike an ordinary function's own
    // `prototype` (which back-links `.constructor` to the function itself), a generator function's
    // `prototype` object has no own properties at all - the `constructor` back-link instead lives on
    // the shared %GeneratorPrototype%/%AsyncGeneratorPrototype%, set up once in makeFunction.
    public JsValue getPrototype() {
        if (prototype == null) {
            final var created = new JsObject();
            if (!generator) {
                // {writable:true, enumerable:false, configurable:true} per
                // OrdinaryFunctionCreate/MakeConstructor - a plain `.set` would default the new key
                // to enumerable, which is wrong (a `for-in` over the prototype must not see it).
                created.defineValue("constructor", this);
                created.setFlags("constructor", new JsObject.PropertyFlags(true, false, true));
            }
            prototype = created;
        }
        return prototype;
    }

    public void setPrototype(JsValue prototype) {
        this.prototype = prototype;
    }

    @Override
    public PropertyTable ownProperties() {
        return table;
    }

    @Override
    public void setProperty(String key, JsValue value) {
        table.defineValue(key, value);
        table.setFlags(key, HIDDEN);
    }

    @Override
    public void setEnumerableProperty(String key, JsValue value) {
        table.set(key, value);
    }

    @Override
    public JsValue getProperty(String key) {
        return table.has(key) ? table.get(key) : null;
    }

    @Override
    public boolean hasProperty(String key) {
        return table.has(key);
    }

    @Override
    public boolean deleteProperty(String key) {
        return table.delete(key);
    }

    // The generic JsValue.deleteOwnProperty (reached when a delete arrives through a no-trap Proxy
    // forwarding to this function rather than through ExpressionEvaluator.evalDelete's own dedicated
    // JsCallableProperties handling) materialises "name"/"length"/"prototype" into the table and
    // deletes the table entry, but never calls markMetadataDeleted - so a later hasOwnProperty still
    // reports true via OrdinaryProperties.metadataKey, which trusts isMetadataDeleted as the sole
    // source of truth for those keys rather than re-checking the table. Overriding here keeps both
    // delete paths consistent, and rejects deleting "prototype" (always non-configurable) instead of
    // silently reporting success.
    @Override
    public boolean deleteOwnProperty(JsValue key) {
        if (key instanceof JsSymbol) {
            return super.deleteOwnProperty(key);
        }
        final var name = OrdinaryProperties.keyName(key);
        if (("name".equals(name) || "length".equals(name)) && !hasProperty(name)) {
            markMetadataDeleted(name);
            return true;
        }
        if ("prototype".equals(name) && !hasProperty(name) && (isConstructor() || isGenerator())) {
            return false;
        }
        return super.deleteOwnProperty(key);
    }

    @Override
    public void markMetadataDeleted(String key) {
        if (deletedMetadataKeys == null) {
            deletedMetadataKeys = new LinkedHashSet<>();
        }
        deletedMetadataKeys.add(key);
    }

    @Override
    public boolean isMetadataDeleted(String key) {
        return deletedMetadataKeys != null && deletedMetadataKeys.contains(key);
    }

    @Override
    public List<String> propertyKeys() {
        return new ArrayList<>(table.keys());
    }

    @Override
    public List<String> enumerablePropertyKeys() {
        return table.keys().stream().filter(table::isEnumerable).toList();
    }
}
