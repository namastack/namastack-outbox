<h1 align="center">
  <a href="https://www.namastack.io/outbox/">
    <picture>
      <source
        media="(prefers-color-scheme: dark)"
        srcset="./.github/assets/namastack_outbox_logo_dark.png"
      >
      <source
        media="(prefers-color-scheme: light)"
        srcset="./.github/assets/namastack_outbox_logo_light.png"
      >
      <img
        src="./.github/assets/namastack_outbox_logo_light.png"
        alt="Namastack Outbox"
      >
    </picture>
  </a>
</h1>

<p align="center">Reliable event delivery with ordering, retries, horizontal scaling and observability.</p>

---

<div align="center">
  <p>
    <a href="https://www.namastack.io/outbox/">Documentation</a> ·
    <a href="https://www.namastack.io/outbox/quickstart/">Getting Started</a> ·
    <a href="https://github.com/namastack/namastack-outbox/releases">Releases</a> ·
    <a href="https://github.com/namastack/namastack-outbox/discussions">Community</a>
  </p>

  <a href="https://github.com/namastack/namastack-outbox/releases/latest"><img src="https://img.shields.io/github/v/release/namastack/namastack-outbox?style=flat-square" alt="Release"></a>
  <a href="https://github.com/namastack/namastack-outbox/actions/workflows/gradle-test.yml"><img src="https://img.shields.io/github/actions/workflow/status/namastack/namastack-outbox/gradle-test.yml?branch=main&amp;style=flat-square&amp;label=build" alt="Build"></a>
  <a href="https://codecov.io/github/namastack/namastack-outbox"><img src="https://img.shields.io/codecov/c/github/namastack/namastack-outbox?style=flat-square" alt="Coverage"></a>
  <a href="https://github.com/namastack/namastack-outbox/stargazers"><img src="https://img.shields.io/github/stars/namastack/namastack-outbox?style=flat-square" alt="GitHub Stars"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/namastack/namastack-outbox?style=flat-square" alt="License"></a>
</div>

---

Namastack Outbox is an open-source **transactional outbox implementation for Spring Boot**, built for reliable event-driven applications with Java and Kotlin.

It persists events atomically with your business data and processes them asynchronously with built-in support for retries, ordering, horizontal scaling and observability — without requiring additional infrastructure.

**[Explore the documentation →](https://www.namastack.io/outbox/)**

## Why Namastack Outbox?

Reliable event publishing becomes surprisingly difficult once failures, retries, concurrency and multiple application instances enter the picture.

Namastack Outbox handles these concerns as part of your Spring Boot application.

* **Transactional guarantees** — persist events atomically with your business data
* **At-least-once delivery** — configurable retry and failure handling
* **Strict ordering** — preserve processing order for events sharing the same key
* **Horizontal scaling** — automatic partitioning and rebalancing across application instances
* **Observability** — metrics, Actuator integration and distributed tracing
* **Context propagation** — preserve tracing, tenant and correlation information
* **Flexible persistence** — JDBC, JPA/Hibernate and MongoDB
* **Messaging integrations** — Kafka, RabbitMQ and AWS SNS
* **Spring Modulith integration** — production-grade event externalization using the transactional outbox pattern

---

## How It Works

Business data and its outbox record commit together. Namastack delivers the record asynchronously with ordering and retries.

<div align="center">
  <picture>
    <source
      media="(prefers-color-scheme: dark)"
      srcset="./namastack-outbox-docs/static/img/landing/diagram_dark.svg"
    >
    <source
      media="(prefers-color-scheme: light)"
      srcset="./namastack-outbox-docs/static/img/landing/diagram_light.svg"
    >
    <img
      src="./namastack-outbox-docs/static/img/landing/diagram_light.svg"
      alt="Namastack Outbox architecture and event delivery flow"
      width="600"
    >
  </picture>
</div>

---

## Documentation

Everything you need to get started, configure and operate Namastack Outbox is available in the documentation.

* **[Getting Started →](https://www.namastack.io/outbox/quickstart/)**
* **[Documentation →](https://www.namastack.io/outbox/)**
* **[Reference →](https://www.namastack.io/outbox/reference/)**
* **[API Reference →](https://javadoc.io/doc/io.namastack/namastack-outbox-api)**

---

## Community

Namastack Outbox is open source and contributions of all sizes are welcome.

* [GitHub Discussions](https://github.com/namastack/namastack-outbox/discussions) — questions, ideas and general discussion
* [GitHub Issues](https://github.com/namastack/namastack-outbox/issues) — bugs and feature requests
* [Discord](https://discord.gg/XNHP5Yhxy7) — chat with the community
* [Contributing Guide](CONTRIBUTING.md) — contribute to the project

---

## Contributors

Thanks to everyone who has contributed to Namastack Outbox.

[![Contributors](https://contrib.rocks/image?repo=namastack/namastack-outbox)](https://github.com/namastack/namastack-outbox/graphs/contributors)

---

## Support the Project

Namastack Outbox is independently developed and maintained as open-source software.

If Namastack Outbox helps you or your team, sponsoring the project helps support its continued development, maintenance and documentation.

[![Sponsor Namastack](https://img.shields.io/badge/Sponsor-Namastack-ea4aaa?style=for-the-badge\&logo=github)](https://github.com/sponsors/namastack)

---

## License

Namastack Outbox is licensed under the [Apache License 2.0](LICENSE).
