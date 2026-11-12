# Compatibility and upgrades

LWNRDB is at 0.x and still experimental. This page says what may change from one release to the next, how the server
protects a data folder from a release that cannot read it, and how to move your data to a new release.

## Version numbers

Four numbers describe a deployment:

| Number | Where you see it | What it covers |
|---|---|---|
| Release | The jar name, the Docker tag, `SERVER_INFO`'s `version` | The server as a whole (semver, `0.x` for now) |
| `protocolVersion` | `SERVER_INFO`, for any authenticated user | The wire: request and response fields, operations, error codes |
| `storageFormatVersion` | `SERVER_INFO`, for any authenticated user, and `{filePath}/format.json` | Every file the server writes under `filePath` |
| Client library versions | Maven Central, npm, NuGet, PyPI | Each client's own API; a client speaks one `protocolVersion` |

`protocolVersion` and `storageFormatVersion` are both `1` today. They move independently of the release: most releases
change neither.

## What may change in 0.x

Anything may change in a 0.x release, including the wire and the files. Two rules keep it visible:

- a change to the wire raises `protocolVersion`;
- a change to what any file under `filePath` looks like raises `storageFormatVersion`.

Every release's entry in [CHANGELOG.md](../CHANGELOG.md), which is also its GitHub release notes, names both numbers and says when either one moved.

## The format stamp

The server records the storage format in `{filePath}/format.json`:

```json
{"formatVersion":1,"writtenBy":"0.1.0"}
```

`writtenBy` is the release that created the stamp, and a stamped folder is never rewritten, so it keeps naming that
release. At startup, before it writes or deletes anything, the server reads the stamp:

| The folder holds | What the server does |
|---|---|
| Nothing yet | Starts and stamps it with its own format |
| Data but no stamp (written before the stamp existed) | Reads it as format `1`, starts and stamps it |
| A stamp with its own format | Starts |
| A stamp with a newer format | Refuses to start |
| A stamp with an older format | Refuses to start |
| A stamp it cannot read or parse | Refuses to start; it never treats the folder as empty |

A refusal is one `FATAL` line and exit status `1`, and the folder is left exactly as it was. The line names the stamp
file and says what to do:

```
/data/db/format.json says this folder holds storage format 2 (written by lwnrdb 0.3.0), but this release (0.2.0)
reads only format 1. Run lwnrdb 0.3.0 or newer on this folder.

/data/db/format.json says this folder holds storage format 1 (written by lwnrdb 0.1.0), but this release (0.2.0)
reads only format 2. Dump it with lwnrdb 0.1.0 (java -jar lwnrdb.jar --dump ... > backup.jsonl), then start this
release on an empty filePath and restore it (--restore < backup.jsonl).
```

There is no in-place migration. A release reads exactly one storage format, and dump and restore move the data across
a change.

## Upgrading

Check the new release's `storageFormatVersion` in [CHANGELOG.md](../CHANGELOG.md) against `SERVER_INFO` on the running server.

**Same storage format.** Stop the server (it shuts down cleanly on `SIGTERM` or `Ctrl+C`), replace the jar or the
image, and start it on the same `filePath`.

**Different storage format.**

1. With the old release still running, dump everything (as an admin, so users and grants come along):
   ```bash
   java -jar lwnrdb.jar --dump --password ... > backup.jsonl
   ```
2. Stop the old server and keep its folder until the new one is verified.
3. Start the new release on an empty `filePath`.
4. Restore into it:
   ```bash
   java -jar lwnrdb.jar --restore --password ... < backup.jsonl
   ```

A dump reads each database through one consistent snapshot, so step 1 can run while the application keeps writing;
the writes made after it are not in the dump.

**A cluster.** Run every node on the same release. Peers exchange documents, never files, so the storage format is
local to each node, but mixed releases are not tested. Upgrade the whole cluster the same way: with the same format,
replace the jar on every node and restart them; with a different one, dump from the old cluster and restore into a new
one.

## Downgrading

The same procedure in reverse. When the older release reads the same storage format, swap the jar back. When it does
not, the stamp makes it refuse the folder, so dump with the newer release and restore with the older one, which works
only when the older release understands every request the dump holds.

## Client libraries

A client works with any server that speaks the same `protocolVersion`, whatever either release is. A server that raises
`protocolVersion` comes with client releases that speak it.
