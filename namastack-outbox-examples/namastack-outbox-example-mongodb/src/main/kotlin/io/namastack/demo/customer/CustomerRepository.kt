package io.namastack.demo.customer

import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.stereotype.Repository

@Repository
class CustomerRepository(
    private val mongoTemplate: MongoTemplate,
) {
    fun save(customer: Customer) {
        mongoTemplate.save(customer, COLLECTION_NAME)
    }

    fun deleteById(id: String) {
        val query = Query(Criteria.where("id").`is`(id))
        mongoTemplate.remove(query, COLLECTION_NAME)
    }

    fun findById(id: String): Customer? {
        val query = Query(Criteria.where("id").`is`(id))
        return mongoTemplate.find(query, Customer::class.java, COLLECTION_NAME).firstOrNull()
    }

    companion object {
        private const val COLLECTION_NAME = "customers"
    }
}
