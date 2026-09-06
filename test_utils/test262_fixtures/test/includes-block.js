/*---
description: Block-list includes are prepended in their declared order.
includes:
  - fixtureFirst.js
  - fixtureSecond.js
info: |
  This block scalar exists to prove free text is not read as frontmatter keys.
  flags: [module]
  features: [Temporal]
---*/

assert.sameValue(FIXTURE_ORDER, "first-second");
