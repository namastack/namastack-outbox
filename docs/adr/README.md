# Architecture Decision Records

Architecture Decision Records (ADRs) document significant technical decisions in
Namastack Outbox. The decision process is described in
[`GOVERNANCE.md`](../../GOVERNANCE.md).

Use an ADR when a decision substantially affects public APIs, persistence,
compatibility, ordering or retry semantics, extension points, or multiple modules.
Small, local, and easily reversible changes do not need one.

## Creating an ADR

1. Discuss the proposal in
   [GitHub Discussions](https://github.com/namastack/namastack-outbox/discussions)
   before implementation.
2. Copy [`0000-template.md`](0000-template.md).
3. Assign the next four-digit number and a short descriptive name, for example
   `0001-compatibility-failure-handling.md`.
4. Set its status to `Proposed` while the decision is under review.
5. Link the GitHub Discussion and record the considered alternatives and their
   consequences.
6. Change the status to `Accepted` when the decision has been made.
7. Link the accepted ADR from the implementing pull request.

## Statuses

- **Proposed**: The decision is being discussed.
- **Accepted**: The decision has been approved for implementation.
- **Rejected**: The proposal was considered and declined.
- **Superseded**: A later ADR replaced the decision.

Accepted and rejected ADRs are historical records. Do not rewrite them to reflect
a later direction. Create a new ADR and link the records to each other instead.
