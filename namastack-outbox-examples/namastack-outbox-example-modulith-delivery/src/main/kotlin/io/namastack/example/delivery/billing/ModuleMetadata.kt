package io.namastack.example.delivery.billing

import org.springframework.modulith.ApplicationModule

@ApplicationModule(displayName = "Billing", allowedDependencies = ["orders"])
class ModuleMetadata
