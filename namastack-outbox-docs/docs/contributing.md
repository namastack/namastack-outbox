# Contributing

Namastack Outbox is an actively maintained and constantly evolving project serving a diverse user base with versatile backgrounds and needs. In order to efficiently address the requirements of all our users, evaluate change requests, and fix bugs, we put in a lot of work.

Our ever-growing community includes many active users, who open new issues and discussions, evolving our [issue tracker] and [discussion board] into a knowledge base – an important addition to our documentation – yielding value to both new and experienced users.

[discussion board]: https://github.com/namastack/namastack-outbox/discussions
[issue tracker]: https://github.com/namastack/namastack-outbox/issues

## How You Can Contribute

We understand that reporting bugs, raising change requests, as well as engaging in discussions can be time-consuming, which is why we've carefully optimized our issue templates and defined guidelines to improve the overall interaction within the project.

Our goal is to ensure that our documentation, as well as issue tracker and discussion board, are __well-structured__, __easy to navigate__, and __searchable__, so you can find what you need quickly and efficiently. Thus, when you follow our guidelines, we can help you much faster.

### Creating an Issue

<div class="grid cards" markdown>

-   :material-bug-outline: &nbsp;
    __Something is not working?__

    ---

    Report a bug in Namastack Outbox by creating an issue with a
    reproduction

    ---

    [:octicons-arrow-right-24: Report a bug][report a bug]

-   :material-file-document-remove-outline: &nbsp;
    __Missing information in our docs?__

    ---

    Report missing information or potential inconsistencies in our
    documentation

    ---

    [:octicons-arrow-right-24: Report a docs issue][report a docs issue]

-   :material-lightbulb-on-20: &nbsp;
    __Want to submit an idea?__

    ---

    Propose a change, feature request, or suggest an improvement

    ---

    [:octicons-arrow-right-24: Request a change][request a change]

-   :material-account-question-outline: &nbsp;
    __Have a question or need help?__

    ---

    Ask a question on our [discussion board] and get in touch with our
    community

    ---

    [:octicons-arrow-right-24: Ask a question][discussion board]

</div>

[report a bug]: https://github.com/namastack/namastack-outbox/issues/new?template=bug_report.md
[report a docs issue]: https://github.com/namastack/namastack-outbox/issues/new?template=docs_issue.md
[request a change]: https://github.com/namastack/namastack-outbox/issues/new?template=feature_request.md

### Contributing Code

<div class="grid cards" markdown>

-   :material-source-pull: &nbsp;
    __Want to create a pull request?__

    ---

    Learn how to create a comprehensive and useful pull request (PR)

    ---

    [:octicons-arrow-right-24: Pull request process](#pull-request-process)

-   :material-test-tube: &nbsp;
    __Want to add tests?__

    ---

    Improve test coverage and help ensure code quality

    ---

    [:octicons-arrow-right-24: Testing guidelines](#testing-guidelines)

</div>

## Getting Started

1. **Fork the repository** on GitHub
2. **Clone your fork** locally:
   ```bash
   git clone https://github.com/YOUR_USERNAME/namastack-outbox.git
   cd namastack-outbox
   ```
3. **Add the upstream remote**:
   ```bash
   git remote add upstream https://github.com/namastack/namastack-outbox.git
   ```

## Development Setup

### Prerequisites

- **JDK 21** or later
- **Kotlin** knowledge (project is 100% Kotlin)
- **Gradle** (wrapper included)
- **Docker & Docker Compose** (for integration tests with databases)
- **IDE**: IntelliJ IDEA recommended (with Kotlin plugin)

### Building the Project

```bash
# Build all modules
./gradlew build

# Run tests
./gradlew test

# Run tests with coverage
./gradlew test jacocoTestReport

# Check code style
./gradlew ktlintCheck

# Fix code style issues
./gradlew ktlintFormat

# Publish to local Maven repository (for testing)
./gradlew publishToMavenLocal
```

### Project Structure

```
namastack-outbox/
├── namastack-outbox-api/               # Public API interfaces and annotations
├── namastack-outbox-core/              # Core implementation
├── namastack-outbox-jpa/               # JPA/Hibernate persistence module
├── namastack-outbox-jdbc/              # JDBC persistence module
├── namastack-outbox-jackson/           # Jackson serialization support
├── namastack-outbox-kafka/             # Apache Kafka integration
├── namastack-outbox-rabbit/            # RabbitMQ integration
├── namastack-outbox-actuator/          # Spring Boot Actuator endpoints
├── namastack-outbox-metrics/           # Micrometer metrics support
├── namastack-outbox-starter-jpa/       # JPA starter (convenience dependency)
├── namastack-outbox-starter-jdbc/      # JDBC starter (convenience dependency)
├── namastack-outbox-integration-tests/ # Integration tests
├── namastack-outbox-examples/          # Example applications
└── namastack-outbox-docs/              # Documentation site
```

## Pull Request Process

1. **Create a feature branch**:
   ```bash
   git checkout -b feature/your-feature-name
   # or
   git checkout -b fix/issue-number-description
   ```

2. **Make your changes** following our coding standards

3. **Write/update tests** for your changes

4. **Run the full test suite**:
   ```bash
   ./gradlew build
   ```

5. **Commit your changes** with a clear message:
   ```bash
   git commit -m "GH-123 Add feature: description of your changes"
   ```

    !!! tip "Commit Message Format"
        Always prefix your commit message with the GitHub issue number (e.g., `GH-123`).

6. **Push to your fork**:
   ```bash
   git push origin feature/your-feature-name
   ```

7. **Open a Pull Request** against the `main` branch

### PR Requirements

- [x] All tests pass (`./gradlew build`)
- [x] Code style checks pass (`./gradlew ktlintCheck`)
- [x] New code has appropriate test coverage
- [x] Documentation is updated if needed
- [x] Commit messages include issue number (e.g., `GH-123 Add feature...`)
- [x] PR description explains the changes and motivation

## Coding Standards

### Kotlin Style

We use [ktlint](https://ktlint.github.io/) for code formatting.

### Code Quality

- Write **self-documenting code** with clear names
- Add **KDoc comments** for public APIs
- Keep functions **small and focused**
- Prefer **immutability** where possible
- Use **meaningful variable names**

### Example KDoc

```kotlin
/**
 * Schedules a record for asynchronous processing via the outbox pattern.
 *
 * The record is persisted atomically with your business transaction and
 * processed by registered handlers after the transaction commits.
 *
 * @param payload The domain object to be processed
 * @param key Logical grouping key for ordered processing
 * @param additionalContext Optional context metadata (tracing, tenant info)
 * @throws IllegalStateException if called outside a transaction
 *
 * @since 1.0.0
 */
fun schedule(payload: Any, key: String, additionalContext: Map<String, String> = emptyMap())
```

## Testing Guidelines

### Test Structure

- **Unit tests**: `src/test/kotlin/` in each module
- **Integration tests**: `namastack-outbox-integration-tests` module
- Use **descriptive test names** that explain the scenario

### Test Naming Convention

```kotlin
@Test
fun `creates record for single handler`() { ... }

@Test
fun `retries failed record with exponential backoff`() { ... }

@Test
fun `does not process record before next retry time`() { ... }
```

### Test Requirements

- All new features must have tests
- Bug fixes should include a test that reproduces the bug
- Aim for **high coverage** of business logic
- Use **MockK** for mocking in Kotlin

### Running Specific Tests

```bash
# Run tests for a specific module
./gradlew :namastack-outbox-core:test

# Run a specific test class
./gradlew test --tests "io.namastack.outbox.OutboxServiceTest"

# Run integration tests
./gradlew :namastack-outbox-integration-tests:test
```

## Checklist

Before interacting within the project, please take a moment to consider the following questions. By doing so, you can ensure that you are using the correct issue template and that you provide all necessary information when interacting with our community.

!!! warning "Issues, discussions, and comments are forever"
    Please note that everything you write is permanent and will remain
    for everyone to read – forever. Therefore, please always be nice and
    constructive, follow our contribution guidelines, and comply with our
    Code of Conduct.

### Before Creating an Issue

- Are you using the appropriate issue template, or is there another issue
  template that better fits the context of your request?

- Have you checked if a similar bug report or change request has already been
  created, or have you stumbled upon something that might be related?

- Did you fill out every field as requested and did you provide all additional
  information we maintainers need to comprehend your request?

### Before Asking a Question

- Is the topic a question for our [discussion board], or is it a bug report or
  change request that should better be raised on our [issue tracker]?

- Is there an open discussion on the topic of your request? If the answer is yes,
  does your question match the direction of the discussion, or should you open a
  new discussion?

- Did you provide our community with all the necessary information to
  understand your question and help you quickly, or can you make it easier to
  help you?

### Before Commenting

- Is your comment relevant to the topic of the current page, post, issue, or
  discussion, or is it a better idea to create a new issue or discussion?

- Does your comment add value to the conversation? Is it constructive and
  respectful to our community and us maintainers? Could you just use a
  :octicons-smiley-16: reaction instead?

## Code of Conduct

By participating in this project, you agree to maintain a respectful and inclusive environment. Please be considerate and constructive in your interactions with other contributors.

Our commitment is to foster a positive and supportive environment, free of inappropriate, offensive, or harmful behavior. We take any violations seriously and will take appropriate action in response to uphold these values.

## Questions?

- Open a [GitHub Discussion](https://github.com/namastack/namastack-outbox/discussions) for questions
- Check existing issues and discussions first
- Join the community and help others!

---

Thank you for contributing to Namastack Outbox! Your contributions help make this library better for everyone. :tada:

