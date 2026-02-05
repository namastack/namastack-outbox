# Cloud Map namespace for internal service discovery
resource "aws_service_discovery_private_dns_namespace" "loadtest" {
  name        = "loadtest.internal"
  description = "Private DNS namespace for load test services"
  vpc         = module.vpc.vpc_id
}

# Service discovery for producer
resource "aws_service_discovery_service" "producer" {
  name = "producer"

  dns_config {
    namespace_id = aws_service_discovery_private_dns_namespace.loadtest.id

    dns_records {
      ttl  = 10
      type = "A"
    }

    routing_policy = "MULTIVALUE"
  }

  health_check_custom_config {
    failure_threshold = 1
  }
}

# Service discovery for processor
resource "aws_service_discovery_service" "processor" {
  name = "processor"

  dns_config {
    namespace_id = aws_service_discovery_private_dns_namespace.loadtest.id

    dns_records {
      ttl  = 10
      type = "A"
    }

    routing_policy = "MULTIVALUE"
  }

  health_check_custom_config {
    failure_threshold = 1
  }
}

# Service discovery for prometheus
resource "aws_service_discovery_service" "prometheus" {
  name = "prometheus"

  dns_config {
    namespace_id = aws_service_discovery_private_dns_namespace.loadtest.id

    dns_records {
      ttl  = 10
      type = "A"
    }

    routing_policy = "MULTIVALUE"
  }

  health_check_custom_config {
    failure_threshold = 1
  }
}

