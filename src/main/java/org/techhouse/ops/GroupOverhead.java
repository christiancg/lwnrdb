package org.techhouse.ops;

import java.util.ArrayList;
import org.techhouse.cache.heap.HeapLayout;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.utils.SaltedKey;

final class GroupOverhead {
    private static final int TABLE_SLOTS_PER_ENTRY = 2;
    static final long BYTES = bytesPerGroup(HeapLayout.current());

    private GroupOverhead() {
    }

    static long bytesPerGroup(HeapLayout layout) {
        return layout.shallowSize(SaltedKey.class) + layout.shallowSize(HeapLayout.jdkClass("java.util.HashMap$Node"))
                + layout.referenceArraySize(TABLE_SLOTS_PER_ENTRY) + layout.shallowSize(ArrayList.class)
                + layout.arrayListArraySize(1) + layout.shallowSize(JsonObject.class)
                + layout.shallowSize(JsonArray.class);
    }
}
