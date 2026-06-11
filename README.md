# LightWeigh Non-Relational DataBase

LWNRDB is a non-relational database written completely in Java. It's main focus is to be lightweight, small, fast and easy to start.
Database speed (measured in IOPS) is not paramount.

## Motivation

I like learning new stuff and always had some complaints about things I would like to do in the most common database systems. At the same time, I wondered how do databases work internally. What's a better way to learn that building a new database engine?

As such, this DB is not intended to be the fastest one out there, the most reliable or even the simplest: it's a learning exercise that could be helpful for someone in some cases. 

## Philosophy

- Simplicity is paramount: plain Java-next (always targeting the latest version and using new features) without any added libraries.
- Fast start-up times: currently less than a second
- Small size: less than a megabyte
- If there's some feature that you might need some time in the distant future, then you actually don't need it
- There's no need to support everything already supported by other DBs

## Design choices

- Saving and updating a record is the same thing if you provide the primary key. In fact there's no specific command to insert or update, just save.
- Querying is always done in an aggregation pipeline. The only exemption is while getting a record by id
- IDs are always strings and must follow the next rules: 
  - Between 1 and 64 characters
  - Only alphanumeric characters allowed and the following symbols are allowed: "_" and "-"
- All numbers are treated as a double (just for simplicity)
- Disk space is cheap: there's no compressing of special codification of files to save space
- Database and collection names must follow the next rules:
  - Between 3 and 64 characters
  - Only alphanumeric characters allowed and the following symbols are allowed: "_" and "-"
- Indexes are updated in the background and admin collections also. This is to make the DB a little bit more agile.
- No composed indexes (at least for now), but an aggregation pipeline can use many indexes (in fact will use all of them if possible)
- Each collection is split across pages (one file per page) sized up to `maxPageSizeBytes`; admin metadata about a collection's pages lives in a parallel paged collection under `admin/pages_<collection>`, and the pagination of that admin collection is itself persisted under `admin/pages_pages_<collection>` (further levels are tracked in memory only and rebuilt at startup). New inserts use a first-fit search across existing pages, so space freed by deletions is reused before a new page is allocated.

## Pending tasks

- [x] Date type support 
- [ ] Geo type support
  - [ ] Distance operator
  - [ ] Within operator
- [ ] Vector type support
  - [ ] Semantic search
- [ ] Index usage in:
  - [ ] group by
  - [ ] join
  - [ ] sort
- [ ] Transactions
- [ ] Replication between nodes (no master-slave arch; all nodes are equal; no sharding)
- [ ] Better file locks
- [x] 95% test coverage
- [x] Request validation
- [ ] Iterative read depending on available memory and document count
- [ ] Collection and index eviction from cache depending on memory usage and query history (using LFU algorithm)
- [x] Numerical values that are integers shouldn't be printed with ".0"
- [x] Users and permissions
- [ ] Secure connections with TLS or something similar
- [ ] Listenable queries (you create the query and then the DB sends events when there are changes)
- [x] Remove lombok

## Wire Protocol / Message Reference

All messages are line-delimited JSON sent over a TCP connection. Every request must include a `type` field. Responses always contain `type`, `status` (`OK`, `ERROR`, `NOT_FOUND`), and `message`.

### Naming rules

- **Database / collection names**: 3–64 characters, alphanumeric + `_` and `-`. The name `admin` is reserved.
- **IDs (`_id`)**: 1–64 characters, alphanumeric + `_` and `-`.

### Operations

#### `LIST_DATABASES`
```json
{"type":"LIST_DATABASES"}
```

#### `CREATE_DATABASE`
```json
{"type":"CREATE_DATABASE","databaseName":"my_db"}
```

#### `DROP_DATABASE`
```json
{"type":"DROP_DATABASE","databaseName":"my_db"}
```

#### `LIST_COLLECTIONS`
```json
{"type":"LIST_COLLECTIONS","databaseName":"my_db"}
```

#### `CREATE_COLLECTION`
```json
{"type":"CREATE_COLLECTION","databaseName":"my_db","collectionName":"my_coll"}
```

#### `DROP_COLLECTION`
```json
{"type":"DROP_COLLECTION","databaseName":"my_db","collectionName":"my_coll"}
```

#### `SAVE` (insert or update)
`_id` is optional. If provided and the document exists it is updated; if it does not exist it is created with that id. If `_id` is omitted a UUID is auto-assigned.
```json
{"type":"SAVE","databaseName":"my_db","collectionName":"my_coll","object":{"name":"Alice"}}
```
```json
{"type":"SAVE","databaseName":"my_db","collectionName":"my_coll","object":{"_id":"user-1","name":"Alice"}}
```

#### `BULK_SAVE`
At least one object required.
```json
{"type":"BULK_SAVE","databaseName":"my_db","collectionName":"my_coll","objects":[{"name":"Alice"},{"_id":"user-2","name":"Bob"}]}
```

#### `FIND_BY_ID`
```json
{"type":"FIND_BY_ID","databaseName":"my_db","collectionName":"my_coll","_id":"user-1"}
```

#### `DELETE`
```json
{"type":"DELETE","databaseName":"my_db","collectionName":"my_coll","_id":"user-1"}
```

#### `AGGREGATE`
Queries run through a pipeline of steps. `aggregationSteps` may be empty (returns all documents).

```json
{
  "type": "AGGREGATE",
  "databaseName": "my_db",
  "collectionName": "my_coll",
  "aggregationSteps": [
    {"type":"FILTER","operator":{"fieldOperatorType":"EQUALS","field":"status","value":"active"}},
    {"type":"SORT","fieldName":"name","ascending":true},
    {"type":"LIMIT","limit":10}
  ]
}
```

**Aggregation step types:**

| Step | Required fields | Notes |
|---|---|---|
| `FILTER` | `operator` | Field or conjunction operator |
| `MAP` | `operators` (non-empty) | Each operator needs `fieldName` |
| `GROUP_BY` | `fieldName` | |
| `JOIN` | `joinCollection`, `localField`, `remoteField`, `asField` | `joinCollection` must satisfy naming rules |
| `COUNT` | — | Returns `{"count": N}` |
| `DISTINCT` | — | `fieldName` is optional; omitting it deduplicates whole documents |
| `LIMIT` | `limit` (> 0) | |
| `SKIP` | `skip` (>= 0) | |
| `SORT` | `fieldName`, `ascending` | |

**Field operator types:** `EQUALS`, `NOT_EQUALS`, `GREATER_THAN`, `GREATER_THAN_EQUALS`, `SMALLER_THAN`, `SMALLER_THAN_EQUALS`, `IN`, `NOT_IN`, `CONTAINS`

**Conjunction operator types:** `AND`, `OR`, `NOR`, `XOR`, `NAND`

#### `CREATE_INDEX`
```json
{"type":"CREATE_INDEX","databaseName":"my_db","collectionName":"my_coll","fieldName":"email"}
```

#### `DROP_INDEX`
```json
{"type":"DROP_INDEX","databaseName":"my_db","collectionName":"my_coll","fieldName":"email"}
```

#### `CLOSE_CONNECTION`
```json
{"type":"CLOSE_CONNECTION"}
```

### Users & Permissions

Every connection must authenticate before sending any protected operation. `LIST_DATABASES`, `AUTHENTICATE`, and `CLOSE_CONNECTION` are the only operations that do not require authentication.

#### `AUTHENTICATE`
```json
{"type":"AUTHENTICATE","username":"alice","password":"secret"}
```

#### `CREATE_USER` (admin only)
`globalPermissions`, `databasePermissions`, and `collectionPermissions` are all optional (default to empty). Collection permission keys must be in `database|collection` format.
```json
{
  "type": "CREATE_USER",
  "username": "bob",
  "password": "secret1234",
  "admin": false,
  "globalPermissions": ["CREATE_DATABASE"],
  "databasePermissions": {"ordersDb": "READ_WRITE"},
  "collectionPermissions": {"analyticsDb|events": "READ"}
}
```

#### `DELETE_USER` (admin only)
```json
{"type":"DELETE_USER","username":"bob"}
```

#### `CHANGE_PERMISSIONS` (admin only)
Replaces all permissions for the user in full.
```json
{
  "type": "CHANGE_PERMISSIONS",
  "username": "bob",
  "admin": false,
  "globalPermissions": [],
  "databasePermissions": {"ordersDb": "READ"},
  "collectionPermissions": {}
}
```

#### `SET_DATABASE_OWNERS` (admin only)
Replaces the full owners list for a database. All usernames must already exist. The creator of a database is automatically set as its first owner.
```json
{"type":"SET_DATABASE_OWNERS","databaseName":"my_db","owners":["alice","bob"]}
```

#### `LIST_USERS` (admin only)
Returns all users with their permissions. `passwordHash` is never included in the response. `aggregationSteps` is optional; when omitted all users are returned.

Each user object in the response contains:

| Field | Type | Description |
|---|---|---|
| `_id` | string | Username |
| `admin` | boolean | Whether the user is a superadmin |
| `globalPermissions` | array | e.g. `["CREATE_DATABASE"]` |
| `databasePermissions` | object | e.g. `{"mydb": "READ_WRITE"}` |
| `collectionPermissions` | object | e.g. `{"mydb\|coll": "READ"}` |
| `ownedDatabases` | array | Databases where this user is an owner |

```json
{"type":"LIST_USERS"}
```

Supports the same `aggregationSteps` as `AGGREGATE` for filtering, sorting, counting, etc.:
```json
{
  "type": "LIST_USERS",
  "aggregationSteps": [
    {"type":"FILTER","operator":{"fieldOperatorType":"EQUALS","field":"admin","value":true}},
    {"type":"SORT","fieldName":"_id","ascending":true}
  ]
}
```

Example filters:

| Goal | Step |
|---|---|
| Find user by username | `FILTER` with `_id EQUALS "alice"` |
| Find all admins | `FILTER` with `admin EQUALS true` |
| Find owners of a database | `FILTER` with `ownedDatabases CONTAINS "mydb"` |
| Count users | `COUNT` |

### Permission model

| Concept | Description |
|---|---|
| `admin` flag | Superadmin — bypasses all permission checks |
| Database ownership | Full access to the database and all its collections, including the ability to drop it |
| `globalPermissions` | `CREATE_DATABASE` — required to create new databases |
| `databasePermissions` | Grants `READ` or `READ_WRITE` to all collections in a database |
| `collectionPermissions` | Grants `READ` or `READ_WRITE` to a specific `database\|collection` |

Ownership takes precedence over `databasePermissions` and `collectionPermissions`. A collection-level grant takes precedence over a database-level one. `READ_WRITE` also covers `READ`.

`DROP_DATABASE` requires admin privileges or ownership — the `globalPermissions` field no longer grants the ability to drop databases.

Operations that require `READ`: `FIND_BY_ID`, `AGGREGATE`, `LIST_COLLECTIONS`.  
Operations that require `READ_WRITE`: `SAVE`, `BULK_SAVE`, `DELETE`, `CREATE_COLLECTION`, `DROP_COLLECTION`, `CREATE_INDEX`, `DROP_INDEX`.

### Authentication errors

| Situation | `status` | `message` |
|---|---|---|
| Request sent before authenticating | `UNAUTHENTICATED` | `Must authenticate first` |
| Wrong username or password | `ERROR` | `The user doesn't exist or the wrong credentials have been provided` |
| Insufficient permissions | `FORBIDDEN` | `action is forbidden, no permissions` |

### Bootstrap

On first startup, if no admin user exists and `defaultAdminUsername` / `defaultAdminPassword` are set in `lwnrdb.cfg`, the server creates that user as superadmin. The password must be at least 8 characters. If neither an existing admin nor valid credentials are configured, the server starts but no privileged operations can be performed until an admin user is created directly.

**New `lwnrdb.cfg` keys:**
```
defaultAdminUsername=admin
defaultAdminPassword=
```

## Q&A

- I want X feature. Can you add it for me?
  - If it feasible and it makes sense, sure! But it might take some time. Please submit an issue
- Can I use it in production?
  - I wouldn't recommend doing that at least for now, as it is very experimental
- I discovered a bug. What should I do?
  - Please submit an issue with the steps to reproduce it and the expected result.

## Contributing

Pull requests are welcome! For major changes, please open an issue first
to discuss what you would like to change.

Please make sure to update tests as appropriate and add new ones if a new feature is developed or a big bug solved.
