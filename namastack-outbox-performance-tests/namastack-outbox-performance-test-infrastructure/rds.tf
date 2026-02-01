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
  username = "postgres"
  password = var.db_password

  performance_insights_enabled = true

  db_subnet_group_name   = aws_db_subnet_group.db.name
  vpc_security_group_ids = [aws_security_group.db_sg.id]

  publicly_accessible = false
  skip_final_snapshot = true
}
