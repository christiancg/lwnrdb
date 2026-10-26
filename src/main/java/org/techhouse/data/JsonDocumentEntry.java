package org.techhouse.data;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.UUID;
import org.techhouse.config.Globals;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;

public abstract class JsonDocumentEntry extends CollectionScopedEntry {
    private static final VarHandle CACHED_HEAP_BYTES = heapBytesHandle();

    protected static final EJson eJson = IocContainer.get(EJson.class);
    protected String _id;
    protected JsonObject data;
    protected volatile long previousByteSize;
    protected int cachedByteSize = -1;
    protected volatile long cachedHeapBytes = -1;

    public String toFileEntry() {
        stampId();
        return eJson.toJson(data);
    }

    protected void stampId() {
        if (_id == null) {
            _id = UUID.randomUUID().toString();
        }
        data.addProperty(Globals.PK_FIELD, _id);
    }

    public String get_id() {
        return _id;
    }

    public void adoptId(String equalId) {
        if (equalId != null && equalId.equals(_id)) {
            _id = equalId;
        }
    }

    public void set_id(String _id) {
        if (_id != null && _id.equals(this._id)) {
            return;
        }
        this._id = _id;
        this.cachedByteSize = -1;
        this.cachedHeapBytes = -1;
    }

    void rememberEncodedLength(int length) {
        cachedByteSize = length;
    }

    public JsonObject getData() {
        return data;
    }

    public void setData(JsonObject data) {
        this.data = data;
        this.cachedByteSize = -1;
        this.cachedHeapBytes = -1;
    }

    public long getCachedHeapBytes() {
        return cachedHeapBytes;
    }

    public long memoizeHeapBytes(long estimate) {
        return CACHED_HEAP_BYTES.compareAndSet(this, -1L, estimate) ? estimate : cachedHeapBytes;
    }

    public long getPreviousByteSize() {
        return previousByteSize;
    }

    public void setPreviousByteSize(long previousByteSize) {
        this.previousByteSize = previousByteSize;
    }

    private static VarHandle heapBytesHandle() {
        try {
            return MethodHandles.lookup().findVarHandle(JsonDocumentEntry.class, "cachedHeapBytes", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
