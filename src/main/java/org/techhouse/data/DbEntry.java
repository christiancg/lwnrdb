package org.techhouse.data;

import com.google.gson.JsonObject;
import lombok.Data;

import java.util.UUID;

@Data
public class DbEntry {
    private String _id;
    private String databaseName;
    private String collectionName;
    private JsonObject data;
    public String toFileEntry() {
        if (_id == null) {
            _id = UUID.randomUUID().toString();
        }
        data.addProperty("_id", _id);
        return data.toString();
    }
}
