The `lwnrdb.*` properties.

```properties
lwnrdb.hosts=db1:8989,db2:8989
lwnrdb.username=app
lwnrdb.password=${LWNRDB_PASSWORD}
lwnrdb.database=shop
lwnrdb.pool-size=16
lwnrdb.tls.enabled=true
lwnrdb.tls.trust-store=/etc/lwnrdb/trust.p12
```

@param hosts servers as `host:port` (`localhost:8989` by default)
@param username the user to authenticate as
@param password the password
@param database the database of entities that name none
@param poolSize pooled connections
@param connectTimeout socket and TLS handshake timeout
@param requestTimeout per-request timeout
@param acquireTimeout wait for a free pooled connection
@param shutdownTimeout wait for borrowed connections at shutdown
@param maxReplyBytes the largest reply read
@param temporalZone the zone zoned date-times are converted to
@param tls TLS settings
@param retry retry settings

## toSettings(JsonMapper)

@param objectMapper Boot's Jackson mapper, used as the base of POJO mapping, or null for the client's own
@return a settings builder holding these properties
