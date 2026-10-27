package org.techhouse.ejson.type_adapters.impl;

import java.util.Set;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.internal.CharJsonOut;
import org.techhouse.ejson.internal.ClassPlan;
import org.techhouse.ejson.internal.FieldBinding;
import org.techhouse.ejson.internal.JsonOut;
import org.techhouse.ejson.type_adapters.TypeAdapter;
import org.techhouse.log.LogRateLimiter;
import org.techhouse.log.Logger;

public class ReflectionTypeAdapter<T> implements TypeAdapter<T> {

    private static final LogRateLimiter DESERIALISATION_FAILURE_LOG = new LogRateLimiter(
            Globals.LOG_RATE_LIMIT_PER_SECOND);

    private final Logger logger = Logger.logFor(ReflectionTypeAdapter.class);
    private final Class<T> clazz;

    public ReflectionTypeAdapter(Class<T> clazz) {
        if (clazz == null) {
            throw new NullPointerException("clazz can't be null");
        }
        this.clazz = clazz;
    }

    @Override
    public String toJson(T value) {
        final var out = new CharJsonOut();
        toJson(value, out);
        return out.toString();
    }

    @Override
    public void toJson(T value, JsonOut out) {
        out.append('{');
        final var actualClass = value.getClass();
        final var plan = ClassPlan.of(actualClass);
        final var bindings = plan.bindings();
        var separated = false;
        for (final var binding : bindings) {
            if (plan.omitsNulls() && holdsNull(binding, value)) {
                continue;
            }
            if (separated) {
                out.append(',');
            }
            separated = true;
            out.append(binding.nameToken());
            final var mark = out.mark();
            try {
                appendFieldValue(binding, value, out);
            } catch (Exception e) {
                logger.error("Serialising " + actualClass.getName() + "." + binding.name()
                        + " failed; emitting null in its place", e);
                out.reset(mark);
                out.append("null");
            }
        }
        out.append('}');
    }

    private static boolean holdsNull(FieldBinding binding, Object instance) {
        try {
            return binding.get(instance) == null;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public T fromJson(JsonBaseElement value) {
        return fromJson(value, Set.of());
    }

    @Override
    public T fromJson(JsonBaseElement value, Set<String> skippedKeys) {
        JsonObject obj;
        if (value.getJsonType() == JsonBaseElement.JsonType.OBJECT) {
            obj = (JsonObject) value;
        } else {
            return null;
        }
        try {
            final var plan = ClassPlan.of(clazz);
            final var instance = plan.newInstance(obj);
            if (!plan.isRecord()) {
                assignFields(plan, instance, obj, skippedKeys);
            }
            return clazz.cast(instance);
        } catch (Exception e) {
            final var entry = DESERIALISATION_FAILURE_LOG.admit("Could not read a " + clazz.getSimpleName() + ": "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            if (entry != null) {
                logger.warning(entry);
            }
            return null;
        }
    }

    private static void assignFields(ClassPlan plan, Object instance, JsonObject obj, Set<String> skippedKeys)
            throws Exception {
        for (var i = 0; i < obj.size(); i++) {
            final var binding = plan.bindingNamed(obj.keyAt(i));
            if (binding != null && !skippedKeys.contains(binding.name())) {
                binding.assign(instance, obj.valueAt(i));
            }
        }
    }

    private void appendFieldValue(FieldBinding binding, Object instance, JsonOut out) throws Exception {
        final var value = binding.get(instance);
        if (value == null) {
            out.append("null");
            return;
        }
        binding.requireAdapter();
        final var adapter = binding.adapter();
        if (adapter != null) {
            adapter.toJson(value, out);
        } else {
            final var field = binding.field();
            logger.error("No type adapter for " + field.getDeclaringClass().getName() + "." + field.getName()
                    + " of type " + field.getType().getName() + "; emitting null in its place");
            out.append("null");
        }
    }
}
