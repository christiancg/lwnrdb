package org.techhouse.bckg_ops.events;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.techhouse.ops.index.IndexedValue;

public final class IndexPriors {
    public static final IndexPriors NONE = new IndexPriors(Map.of());

    private final Map<String, Map<String, List<IndexedValue>>> byField;

    private IndexPriors(Map<String, Map<String, List<IndexedValue>>> byField) {
        this.byField = byField;
    }

    public static IndexPriors of(List<EntityRef> refs) {
        final var fields = fieldsEveryRefCarries(refs);
        if (fields.isEmpty()) {
            return NONE;
        }
        final var byField = new HashMap<String, Map<String, List<IndexedValue>>>();
        for (final var field : fields) {
            final var byId = new HashMap<String, List<IndexedValue>>();
            for (final var ref : refs) {
                byId.computeIfAbsent(ref.id(), _ -> new ArrayList<>(1)).add(ref.priorIndexed().get(field));
            }
            byField.put(field, byId);
        }
        return new IndexPriors(byField);
    }

    public static IndexPriors of(List<EntityRef> inserted, List<EntityRef> updated) {
        if (inserted.isEmpty()) {
            return of(updated);
        }
        if (updated.isEmpty()) {
            return of(inserted);
        }
        final var refs = new ArrayList<EntityRef>(inserted.size() + updated.size());
        refs.addAll(inserted);
        refs.addAll(updated);
        return of(refs);
    }

    private static Set<String> fieldsEveryRefCarries(List<EntityRef> refs) {
        Set<String> fields = null;
        for (final var ref : refs) {
            final var priors = ref.priorIndexed();
            if (priors == null || priors.isEmpty()) {
                return Set.of();
            }
            if (fields == null) {
                fields = new HashSet<>(priors.keySet());
            } else {
                fields.retainAll(priors.keySet());
            }
            if (fields.isEmpty()) {
                return Set.of();
            }
        }
        return fields == null ? Set.of() : fields;
    }

    public Map<String, List<IndexedValue>> forField(String fieldName) {
        return byField.get(fieldName);
    }
}
