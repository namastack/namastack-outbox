# Secrets Manager secret for database credentials
resource "aws_secretsmanager_secret" "db_credentials" {
  name                    = "loadtest-db-credentials"
  recovery_window_in_days = 0 # Allow immediate deletion for load testing
}

resource "aws_secretsmanager_secret_version" "db_credentials" {
  secret_id = aws_secretsmanager_secret.db_credentials.id
  secret_string = jsonencode({
    username = "postgres"
    password = var.db_password
  })
}

# Secrets Manager secret for Grafana credentials
resource "aws_secretsmanager_secret" "grafana_credentials" {
  name                    = "loadtest-grafana-credentials"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "grafana_credentials" {
  secret_id = aws_secretsmanager_secret.grafana_credentials.id
  secret_string = jsonencode({
    admin_user     = "admin"
    admin_password = var.grafana_admin_password
  })
}
