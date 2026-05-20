package io.namastack.example.modulith.payment

import org.springframework.modulith.ApplicationModule

@ApplicationModule(displayName = "Payment", allowedDependencies = ["order"])
class ModuleMetadata
