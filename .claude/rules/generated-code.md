---
paths:
  - "**/generated/**"
  - "**/__generated__/**"
  - "**/build/generated/**"
  - "web/src/api/generated/**"
  - "backend/src/main/java/**/generated/**"
  - "android/**/src/main/**/generated/**"
---
# Generated code rule

- Files under generated directories (OpenAPI server interfaces, TypeScript and Kotlin clients, Room/Hilt
  output, protobuf) are **never hand-edited**. The `protect-files` hook blocks writes; CI regenerates and fails on
  a diff.
- To change an API type or endpoint: edit `contracts/openapi.yaml` (architect, stage 3), run the generator
  (`make contract-lint` validates; the module build regenerates: `./gradlew openApiGenerate`,
  `npm run gen:api`), then adapt hand-written adapters. A breaking change needs an ADR and passes
  `make contract-diff BASE=main` only with the PR label.
- Never copy generated code into hand-written packages to "fix" it; write an adapter or a mapper beside it.
- Generated output is committed only where the build needs it offline (document which in the module CLAUDE.md);
  otherwise it is in `.gitignore` and produced by the build.
- Reviewers: a diff that touches these paths without a corresponding contract or generator change is a BLOCKER.
