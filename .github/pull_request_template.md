What does this change and why?

- [ ] I added or updated tests, including the suites in `test_utils/` if it can be reached over the wire
- [ ] `mvn verify` passes locally
- [ ] The README or `docs/` is updated if the behavior changed
- [ ] `docs/protocol.md` is updated if the change adds or changes an operation, a request or reply field, or an error code
- [ ] `docs/operations.md` is updated if the change adds a configuration key, a metric or a startup log line
- [ ] `docs/simplejs.md` is updated if the change touches the JavaScript engine or any scripting surface (`RUN_SCRIPT`, procedures, triggers, before-write hooks, schedules, the `db` module, the sandbox)
- [ ] `docs/clustering.md` is updated if the change touches membership, ownership, quorum writes, routing, anti-entropy, distributed transactions or script placement
