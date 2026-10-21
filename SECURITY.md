# Security

LWNRDB is experimental and I would not put anything you care about in it yet. That said, I do want to hear about security problems.

If you find one, please don't open a public issue. Use the private vulnerability reporting on this repository (the Security tab, then "Report a vulnerability"). A short description and the requests needed to reproduce it are enough.

I work on this in my spare time, so I can't promise a response time, but I will read every report and I will say thanks in the fix if you want me to.

With Docker, `LWNRDB_ADMIN_PASSWORD` is written in plain text into the container's `lwnrdb.cfg`, so anyone who can `docker exec` into the container or read its filesystem can read it. Mount your own `lwnrdb.cfg` if that matters to you.

Only the latest release gets fixes.
