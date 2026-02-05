resource "aws_db_subnet_group" "db" {
  name       = "db-subnets"
  subnet_ids = module.vpc.private_subnets
}

resource "aws_db_instance" "loadtest_db" {
  identifier        = "loadtest-db"
  engine            = "postgres"
  engine_version    = "17"
  instance_class    = "db.m5.xlarge"
  allocated_storage = 20

  db_name  = "loadtest"
  username = jsondecode(aws_secretsmanager_secret_version.db_credentials.secret_string)["username"]
  password = jsondecode(aws_secretsmanager_secret_version.db_credentials.secret_string)["password"]

  performance_insights_enabled = true

  db_subnet_group_name   = aws_db_subnet_group.db.name
  vpc_security_group_ids = [aws_security_group.db_sg.id]

  publicly_accessible = false
  skip_final_snapshot = true
}

# IAM role for RDS Proxy
resource "aws_iam_role" "rds_proxy_role" {
  name = "rds-proxy-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "rds.amazonaws.com" }
    }]
  })
}

resource "aws_iam_policy" "rds_proxy_secrets" {
  name = "rds-proxy-secrets-policy"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "secretsmanager:GetSecretValue"
        ]
        Resource = aws_secretsmanager_secret.db_credentials.arn
      },
      {
        Effect = "Allow"
        Action = [
          "kms:Decrypt"
        ]
        Resource = "*"
        Condition = {
          StringEquals = {
            "kms:ViaService" = "secretsmanager.${var.aws_region}.amazonaws.com"
          }
        }
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "rds_proxy_secrets_attach" {
  role       = aws_iam_role.rds_proxy_role.name
  policy_arn = aws_iam_policy.rds_proxy_secrets.arn
}

# RDS Proxy
resource "aws_db_proxy" "loadtest_proxy" {
  name                   = "loadtest-proxy"
  debug_logging          = false
  engine_family          = "POSTGRESQL"
  idle_client_timeout    = 1800
  require_tls            = false
  role_arn               = aws_iam_role.rds_proxy_role.arn
  vpc_security_group_ids = [aws_security_group.rds_proxy_sg.id]
  vpc_subnet_ids         = module.vpc.private_subnets

  auth {
    auth_scheme = "SECRETS"
    iam_auth    = "DISABLED"
    secret_arn  = aws_secretsmanager_secret.db_credentials.arn
  }
}

resource "aws_db_proxy_default_target_group" "loadtest_proxy" {
  db_proxy_name = aws_db_proxy.loadtest_proxy.name

  connection_pool_config {
    connection_borrow_timeout    = 120
    max_connections_percent      = 100
    max_idle_connections_percent = 50
  }
}

resource "aws_db_proxy_target" "loadtest_proxy" {
  db_proxy_name          = aws_db_proxy.loadtest_proxy.name
  target_group_name      = aws_db_proxy_default_target_group.loadtest_proxy.name
  db_instance_identifier = aws_db_instance.loadtest_db.identifier
}


