# Contributing

Thanks for taking the time. This is a learning project and it is still experimental, so things move and break. Pull requests are welcome, but for anything big please open an issue first so we can talk about it before you spend a weekend on it.

## Building and testing

You need JDK 27 or newer, the build refuses to run on anything older. The reason is that the regex engine of the embedded JavaScript interpreter reads its Unicode data from the JDK, and I want a script to answer the same on every machine.

```bash
mvn clean package -DskipTests
mvn verify
mvn spotless:apply
```

`mvn verify` is the one that matters. It runs the unit tests and then every quality gate: Spotless, Checkstyle, PMD, CPD and SpotBugs, plus a 95% instruction coverage check. If anything complains the build fails, and so does the pull request. Run `mvn spotless:apply` before you commit so the formatting never gets in the way. To run a single test use `mvn test -Dtest=MyTestClass#myTestMethod`.

The unit tests mirror the package layout under `src/test`. Anything that can be reached over the wire also needs coverage in the integration suites under `test_utils/`, which talk to a real server. Look for the suite that already covers the area you touched and add your cases there. Most of them need a server running on port 8989 with `defaultAdminPassword=Tst-Admin-9xQ2mV` in its `lwnrdb.cfg`, and a few start their own. Each script says which one it is at the top.

## Things the build will check for you

I am strict about a few things, so it is better to know them before you write code.

There are no comments in the code. Not Javadoc, not line comments, not XML comments. If something needs a comment to be understood, rename it, extract it or split it until it doesn't. A test counts them against `config/comment-baseline.txt`, which can only go down, so a new comment fails the build. Toolchain directives such as `// NOPMD` or `@SuppressWarnings` are fine.

A file can have at most 500 lines, tests included, and a method at most 200. If you hit the limit, split the class instead of looking for an exclusion. The build also fails on duplicated blocks of code, so a copied helper will be flagged.

There are no runtime dependencies and there won't be. JUnit and Mockito are the only libraries allowed, and only for tests. The JSON parser, the regex engine and the rest are written in the project on purpose.

## Pull requests

Keep them focused on one thing. Add tests for what you changed, run `mvn verify` locally, and describe what the change does and why in the pull request. If it changes behavior that is documented in the README, update the README in the same pull request.

## Reporting bugs

Open an issue with the steps to reproduce it, what you expected to happen and what happened instead. The exact requests you sent and the error code you got back help a lot. If you think it is a security problem, please read [SECURITY.md](SECURITY.md) first.

## Releases

Versions are not stored in the pom. The build takes the version from `-Drevision`, and when it is missing it falls back to `1.0-SNAPSHOT`, so a local build always produces `target/lwnrdb.jar`.

To cut a release, tag the commit on `main` and push the tag:

```bash
git tag v0.1.0
git push origin v0.1.0
```

The release workflow takes it from there. It runs the full build with that version, attaches `lwnrdb-0.1.0.jar` to a GitHub release and publishes the Docker image to `ghcr.io/christiancg/lwnrdb` with the version and `latest` tags.
