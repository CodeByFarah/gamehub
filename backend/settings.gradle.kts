// Single Gradle module by design. The architectural boundaries between
// api / application / domain / infrastructure are enforced by ArchUnit rules
// in src/test/java/com/gamehub/architecture/, not by Gradle subprojects.
//
// Rationale in docs/adr/ADR-006-modular-monolith.md: subprojects buy you
// compile-time enforcement at the cost of a build graph that every reviewer
// has to learn. ArchUnit gives the same enforcement as a failing test, which
// is both faster to run and easier to read than a dependency diagram.
rootProject.name = "gamehub-backend"
