# Contributing

Thanks for your interest in improving the Webhooks Toolkit for Spring Boot! Bug reports, documentation fixes,
and pull requests are all welcome.

By participating you agree to follow the [Code of Conduct](CODE_OF_CONDUCT.md). Security issues must **not** be
filed as public issues — see [SECURITY.md](SECURITY.md).

## Before you start

- **Bugs:** search [existing issues](https://github.com/jinternals/webhooks/issues) first, then open one using the
  bug template, including a minimal reproduction.
- **Features / API changes:** please open an issue to discuss the design *before* writing code. The toolkit
  deliberately keeps a small scope (Standard Webhooks compliance, no persistence — see
  [Design scope](README.md#design-scope)), so early alignment saves everyone time.
- **Small fixes** (typos, docs, obvious bugs) can go straight to a pull request.

## Prerequisites

| Tool | Version |
| --- | --- |
| JDK | 21 |
| Maven | 3.9+ |
| Docker | optional — only for `webhooks-sample/integration-test.sh` |

## Building

```bash
# Build + tests + 100% coverage gate (~1 min)
mvn clean verify

# Full gate as CI runs it: Spotless + 100% JaCoCo + 100% PITest mutation score (parallelized)
mvn -T 1C clean verify -Pmutation,format

# Fast inner loop: compile and run tests only
mvn test

# One module (add -Pmutation for mutation testing)
mvn -pl webhooks-consumer-autoconfigure -am verify
```

The sample apps are a separate build that consumes the installed libraries:

```bash
mvn install -DskipTests
mvn -f webhooks-sample/pom.xml verify
```

## Quality gate

Every library module enforces:

- **100% JaCoCo line and branch coverage** — on every `mvn verify`, and
- **100% PITest mutation score** (`STRONGER` mutators) — with `-Pmutation` (always on in CI).

A pull request that lowers either will fail CI. In practice this means:

- Every new branch needs a test that exercises it, and tests must *assert* behaviour — a test that merely
  executes code will leave mutants alive.
- Don't lower the thresholds. If a class genuinely cannot be unit-tested, exclude it explicitly in the module's
  `pom.xml` **with a comment explaining why**, and call it out in the PR description.
- Reports are written to `<module>/target/site/jacoco/` and `<module>/target/pit-reports/`; CI uploads them as
  the `quality-reports` artifact.

Run mutation testing for a single module while iterating:

```bash
mvn -Pmutation -pl webhooks-core -am verify
```

## Code style

Formatting is enforced with [Spotless](https://github.com/diffplug/spotless) +
[Palantir Java Format](https://github.com/palantir/palantir-java-format) (4-space indent, 120 columns):

```bash
mvn spotless:apply   # reformat in place — run before committing
mvn -Pformat verify  # fail on any formatting violation
```

Other conventions:

- Public API types and SPIs carry Javadoc. The release build runs Javadoc with doclint, so broken `{@link}`
  references fail it (CI checks this on every PR).
- Keep the docs in sync: user-visible behaviour changes should update [`README.md`](README.md), the relevant page
  under [`docs/`](docs/README.md), and — for spec-level behaviour — [`docs/reference/SPEC.md`](docs/reference/SPEC.md).
- Add an entry under **Unreleased** in [`CHANGELOG.md`](CHANGELOG.md) for any user-visible change.

## Pull request process

1. Fork the repository and create a branch from `main` (e.g. `fix/clock-skew-overflow`).
2. Make focused commits with clear messages in the imperative mood ("Add …", "Fix …").
3. Run `mvn spotless:apply` and `mvn clean verify -Pmutation` locally.
4. Open a pull request and fill in the template. Link the issue it resolves (`Fixes #123`).
5. CI must be green, and at least one maintainer review is required before merge.

## Licensing of contributions

This project is licensed under the [Apache License 2.0](LICENSE). Unless you explicitly state otherwise, any
contribution you intentionally submit for inclusion is licensed under the same terms (Apache-2.0 §5), without any
additional terms or conditions.
