package io.namastack.example.delivery.notifications

import org.springframework.modulith.ApplicationModule

@ApplicationModule(displayName = "Notifications", allowedDependencies = ["orders", "billing"])
class ModuleMetadata
