# Contributing to Namastack Outbox

Thank you for your interest in contributing to Namastack Outbox! This document provides guidelines and information for contributors.

This project is written in **Kotlin** and targets both Kotlin and Java users of Spring Boot.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [Development Setup](#development-setup)
- [How to Contribute](#how-to-contribute)
- [Pull Request Process](#pull-request-process)
- [Coding Standards](#coding-standards)
- [Testing Guidelines](#testing-guidelines)
- [Documentation](#documentation)
- [Issue Reporting](#issue-reporting)

## Code of Conduct

By participating in this project, you agree to maintain a respectful and inclusive environment. Please be considerate and constructive in your interactions with other contributors.

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

## How to Contribute

### Types of Contributions

- **Bug fixes**: Fix issues reported in GitHub Issues
- **Features**: Implement new features (discuss first in an issue)
- **Documentation**: Improve docs, add examples, fix typos
- **Tests**: Add missing tests, improve test coverage

### Before You Start

1. **Check existing issues** to see if your contribution is already being worked on
2. **Open an issue** to discuss significant changes before starting work
3. **Keep changes focused** - one feature or fix per pull request

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
   
   > **Note**: Always prefix your commit message with the GitHub issue number (e.g., `GH-123`).

6. **Push to your fork**:
   ```bash
   git push origin feature/your-feature-name
   ```

7. **Open a Pull Request** against the `main` branch

### PR Requirements

- [ ] All tests pass (`./gradlew build`)
- [ ] Code style checks pass (`./gradlew ktlintCheck`)
- [ ] New code has appropriate test coverage
- [ ] Documentation is updated if needed
- [ ] Commit messages include issue number (e.g., `GH-123 Add feature...`)
- [ ] PR description explains the changes and motivation

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

## Documentation

### When to Update Docs

- Adding new public APIs
- Changing existing behavior
- Adding new configuration options
- Adding new features

### Documentation Locations

- **KDoc**: In-code documentation for APIs
- **README.md**: Project overview and quick start
- **namastack-outbox-docs/**: Full documentation site (MkDocs)
- **Example READMEs**: In each example project

## Issue Reporting

### Bug Reports

When reporting a bug, please include:

1. **Description**: Clear description of the issue
2. **Steps to reproduce**: Minimal steps to trigger the bug
3. **Expected behavior**: What you expected to happen
4. **Actual behavior**: What actually happened
5. **Environment**: Java version, Spring Boot version, database type
6. **Code sample**: Minimal code that reproduces the issue
7. **Stack trace**: Full error stack trace if applicable

### Feature Requests

When requesting a feature:

1. **Use case**: Describe your use case
2. **Proposed solution**: How you envision the feature working
3. **Alternatives**: Other solutions you've considered
4. **Additional context**: Any other relevant information

## Questions?

- Open a [GitHub Discussion](https://github.com/namastack/namastack-outbox/discussions) for questions
- Check existing issues and discussions first
- Join the community and help others!

---

Thank you for contributing to Namastack Outbox! Your contributions help make this library better for everyone. 🎉
