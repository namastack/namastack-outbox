package io.namastack.outbox

/**
 * Marker annotation that causes the Kotlin `allOpen` compiler plugin to make the annotated class
 * and all its public methods `open` at compile time.
 *
 * This is required for classes that are instantiated via `@Bean` factory methods (and thus lack
 * Spring stereotype annotations like `@Component`) but may still need to be proxied by CGLIB
 * at runtime — for example, when OpenTelemetry or other AOP-based instrumentation wraps beans
 * that contain `@Scheduled`, `@PostConstruct`, or `@PreDestroy` methods.
 *
 * Without this annotation, Kotlin classes are `final` by default, and CGLIB cannot create a
 * subclass proxy — leading to `BeanCreationException` at startup.
 *
 * @author Roland Beisel
 * @since 1.1.0
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class OpenForProxy
