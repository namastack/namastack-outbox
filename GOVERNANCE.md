# Project Governance

Namastack Outbox is developed openly. Contributions, reviews, and architectural
input are welcome. This document explains how responsibilities and decisions are
handled so that the project can consider different perspectives and still make
timely progress.

## Roles and Responsibilities

### Contributors

Contributors report problems, propose changes, participate in discussions, and
submit pull requests.

### Maintainers

Maintainers review contributions, help shape the technical direction, uphold the
project's compatibility and quality standards, and support releases and ongoing
maintenance.

### Lead Maintainer

The lead maintainer is responsible for the overall direction of the project and
for ensuring that decisions are made when consensus cannot be reached within a
reasonable period.

GitHub repository permissions provide the access needed to operate and maintain
the project. A permission level alone does not define decision ownership; decision
responsibility follows the roles and process described in this document.

## Decision Making

Routine, local, and easily reversible changes are decided through normal pull
request review.

Changes with significant or difficult-to-reverse consequences should use the
architectural decision process. Examples include changes to:

- public APIs or compatibility guarantees;
- persisted data, schemas, or migration requirements;
- ordering, retry, or failure-handling semantics;
- extension points and service provider interfaces;
- behavior shared across multiple persistence implementations or modules.

For these decisions:

1. Open a [GitHub Discussion](https://github.com/namastack/namastack-outbox/discussions)
   before implementation. Describe the problem, requirements, constraints,
   considered alternatives, proposed direction, and known trade-offs.
2. Name a decision owner and state a review period. Three to seven calendar days
   should be sufficient for most decisions.
3. Seek consensus by addressing material concerns and evaluating viable
   alternatives.
4. At the end of the review period, the decision owner records the outcome in an
   Architecture Decision Record (ADR). The lead maintainer is the default decision
   owner for cross-cutting changes.
5. Start implementation after the ADR is accepted and link it from the pull
   request.

Consensus is preferred, but it is not required indefinitely. If consensus cannot
be reached, the decision owner makes the decision after considering the documented
alternatives and their consequences. The ADR records the rationale and any known
disagreement.

An accepted decision may be reconsidered when new information reveals a concrete
correctness, compatibility, security, or release risk. Other improvements should
normally be proposed as follow-up work rather than reopening the implementing pull
request's overall design.

Urgent fixes may proceed before an ADR is accepted. If the fix establishes a
significant architectural precedent, the decision should be documented afterward.

## Architecture Decision Records

ADRs are stored in [`docs/adr`](docs/adr/README.md). They document why a decision
was made and which consequences the project knowingly accepts. They are not meant
to reproduce every implementation detail.

Accepted ADRs are historical records and are not rewritten when a decision changes.
A new ADR supersedes the previous one and links to it.

## Amendments

This governance process may be changed through the same architectural decision
process. It should remain lightweight and may be adjusted when experience shows
that it does not help the project reach clear and timely decisions.
