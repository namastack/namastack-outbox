# Namastack Outbox guidance

## KDoc

Follow the style of nearby declarations. When adding or changing a documented class or method,
check its KDoc against the implementation and its callers before finishing the change.

- For a documented method with parameters, use a multiline KDoc and cover every parameter with
  `@param`. Reserve one-line KDocs for simple declarations without tags. For constructor
  properties, use `@property` or `@param` consistently with nearby code.
- For a documented method with a non-`Unit` result, add `@return`, including what `true`, `false`,
  `null`, or an empty collection means. Methods returning `Unit` do not need an `@return` tag.
- Document meaningful failure behavior with `@throws`, especially when an exception is wrapped
  or passed through to a caller.
- For sealed hierarchies, document the shared contract on the base type and the distinct role of
  each subtype on that subtype. Describe data holders as data holders, not as methods that execute.
- For new declarations, `@since` normally uses the next minor release after the current project
  version (for example, `1.11.x` becomes `1.12.0`). Check the planned release before setting it;
  keep the original `@since` on existing declarations.
- State guarantees about commits, ordering, retries, affected rows, and interruption only when
  the code enforces them. Check all documented methods in a touched class, not just one example.

## Kotlin style

After changing Kotlin code, run the ktlint check for each affected module and source set before
finishing. Use `./gradlew :<module>:ktlintMainSourceSetCheck` for production code and
`./gradlew :<module>:ktlintTestSourceSetCheck` for tests. Fix any violations in the changed files.
