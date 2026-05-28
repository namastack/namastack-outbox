package io.namastack.outbox.event

import org.assertj.core.api.Assertions.assertThatNoException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("LogicalNameValidator")
class LogicalNameValidatorTest {
    @Nested
    @DisplayName("valid names")
    inner class ValidNames {
        @Test
        fun `simple alphanumeric name passes`() {
            assertThatNoException().isThrownBy { LogicalNameValidator.validate("OrderEvent", "ctx") }
        }

        @Test
        fun `name with dots passes`() {
            assertThatNoException().isThrownBy { LogicalNameValidator.validate("com.acme.OrderEvent", "ctx") }
        }

        @Test
        fun `name with hyphens and underscores passes`() {
            assertThatNoException().isThrownBy { LogicalNameValidator.validate("order_event-v2", "ctx") }
        }
    }

    @Nested
    @DisplayName("blank names")
    inner class BlankNames {
        @Test
        fun `empty string throws`() {
            assertThatThrownBy { LogicalNameValidator.validate("", "ctx") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("must not be blank")
        }

        @Test
        fun `whitespace-only string throws`() {
            assertThatThrownBy { LogicalNameValidator.validate("   ", "ctx") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("must not be blank")
        }
    }

    @Nested
    @DisplayName("names containing whitespace")
    inner class NamesWithWhitespace {
        @Test
        fun `name with internal space throws`() {
            assertThatThrownBy { LogicalNameValidator.validate("order event", "ctx") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("must not contain whitespace")
        }

        @Test
        fun `name with tab throws`() {
            assertThatThrownBy { LogicalNameValidator.validate("order\tevent", "ctx") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("must not contain whitespace")
        }
    }

    @Nested
    @DisplayName("reserved characters")
    inner class ReservedCharacters {
        @Test
        fun `hash throws`() {
            assertThatThrownBy { LogicalNameValidator.validate("foo#bar", "ctx") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("reserved characters")
                .hasMessageContaining("#")
        }

        @Test
        fun `comma throws`() {
            assertThatThrownBy { LogicalNameValidator.validate("foo,bar", "ctx") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("reserved characters")
        }

        @Test
        fun `open paren throws`() {
            assertThatThrownBy { LogicalNameValidator.validate("foo(bar", "ctx") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("reserved characters")
        }

        @Test
        fun `close paren throws`() {
            assertThatThrownBy { LogicalNameValidator.validate("foo)bar", "ctx") }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("reserved characters")
        }

        @Test
        fun `context string appears in error message`() {
            assertThatThrownBy { LogicalNameValidator.validate("bad#name", "my-handler") }
                .hasMessageContaining("my-handler")
        }
    }
}
