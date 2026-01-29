output "grafana_url" {
  value = "https://${aws_lb.loadtest_lb.dns_name}"
}

output "db_endpoint" {
  value = aws_db_instance.loadtest_db.address
}

output "db_port" {
  value = aws_db_instance.loadtest_db.port
}
