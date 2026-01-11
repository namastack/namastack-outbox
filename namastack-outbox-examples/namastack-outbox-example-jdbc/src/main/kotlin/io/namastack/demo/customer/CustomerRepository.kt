package io.namastack.demo.customer

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Repository
class CustomerRepository(
    private val jdbcTemplate: JdbcTemplate,
) {
    @Transactional
    fun save(customer: Customer) {
        jdbcTemplate.update(
            "INSERT INTO customer (id, firstname, lastname, email) VALUES (?, ?, ?, ?)",
            customer.id,
            customer.firstname,
            customer.lastname,
            customer.email,
        )
    }

    @Transactional
    fun deleteById(id: UUID) {
        jdbcTemplate.update("DELETE FROM customer WHERE id = ?", id)
    }

    fun findById(id: UUID): Customer? =
        jdbcTemplate
            .query(
                "SELECT id, firstname, lastname, email FROM customer WHERE id = ?",
                { rs, _ ->
                    Customer(
                        id = UUID.fromString(rs.getString("id")),
                        firstname = rs.getString("firstname"),
                        lastname = rs.getString("lastname"),
                        email = rs.getString("email"),
                    )
                },
                id,
            ).firstOrNull()
}
