package org.techhouse.data;

import java.util.UUID;
import org.techhouse.config.Globals;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;

public abstract class JsonDocumentEntry extends CollectionScopedEntry {
    protected static final EJson eJson = IocContainer.get(EJson.class);
    protected String _id;
    protected JsonObject data;
    // Pre-update byte size for the corresponding file entry. Only set for updates,
    // so that page-size accounting can compute the size delta after an update.
    protected long previousByteSize;

    public String toFileEntry() {
        if (_id == null) {
            _id = UUID.randomUUID().toString();
        }
        data.addProperty(Globals.PK_FIELD, _id);
        return eJson.toJson(data);
    }

    public String get_id() {
        return _id;
    }

    public void set_id(String _id) {
        this._id = _id;
    }

    public JsonObject getData() {
        return data;
    }

    public void setData(JsonObject data) {
        this.data = data;
    }

    public long getPreviousByteSize() {
        return previousByteSize;
    }

    public void setPreviousByteSize(long previousByteSize) {
        this.previousByteSize = previousByteSize;
    }
}
