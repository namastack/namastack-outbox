resource "aws_iam_role" "ecs_task_execution_role" {
  name = "ecs-task-execution-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Effect    = "Allow"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "ecs_execution_role_policy" {
  role       = aws_iam_role.ecs_task_execution_role.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_policy" "ecs_secrets" {
  name = "ecs-secrets-access"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "secretsmanager:GetSecretValue"
        ]
        Resource = [
          aws_secretsmanager_secret.db_credentials.arn,
          aws_secretsmanager_secret.grafana_credentials.arn
        ]
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "ecs_secrets_attach" {
  role       = aws_iam_role.ecs_task_execution_role.name
  policy_arn = aws_iam_policy.ecs_secrets.arn
}

resource "aws_iam_policy" "ecs_ssm" {
  name = "ecs-ssm-access"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "ssm:StartSession",
          "ssm:DescribeSessions",
          "ssm:GetConnectionStatus",
          "ssm:TerminateSession",
          "ssm:SendCommand"
        ]
        Resource = "*"
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "ecs_ssm_attach" {
  role       = aws_iam_role.ecs_task_execution_role.name
  policy_arn = aws_iam_policy.ecs_ssm.arn
}

resource "aws_iam_role" "ecs_task_role" {
  name = "ecs-task-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_policy" "grafana_cloudwatch_read" {
  name = "grafana-cloudwatch-read"
  policy = jsonencode({
    Version = "2012-10-17",
    Statement = [
      {
        Effect = "Allow",
        Action = [
          "logs:DescribeLogGroups",
          "logs:DescribeLogStreams",
          "logs:GetLogEvents",
          "logs:FilterLogEvents",
          "logs:StartQuery",
          "logs:GetQueryResults",
          "logs:StopQuery"
        ],
        Resource = "*"
      },
      {
        Effect = "Allow",
        Action = [
          "cloudwatch:ListMetrics",
          "cloudwatch:GetMetricData",
          "cloudwatch:GetMetricStatistics",
          "cloudwatch:DescribeAlarms",
          "cloudwatch:DescribeAlarmsForMetric"
        ],
        Resource = "*"
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "grafana_cloudwatch_attach" {
  role       = aws_iam_role.ecs_task_role.name
  policy_arn = aws_iam_policy.grafana_cloudwatch_read.arn
}

resource "aws_cloudwatch_log_group" "grafana" {
  name              = "/ecs/grafana"
  retention_in_days = 1
}

resource "aws_cloudwatch_log_group" "performance_processor" {
  name              = "/ecs/performance-processor"
  retention_in_days = 1
}

resource "aws_cloudwatch_log_group" "performance_producer" {
  name              = "/ecs/performance-producer"
  retention_in_days = 1
}

resource "aws_cloudwatch_log_group" "performance_executor" {
  name              = "/ecs/performance-executor"
  retention_in_days = 1
}

resource "aws_cloudwatch_log_group" "prometheus" {
  name              = "/ecs/prometheus"
  retention_in_days = 1
}

resource "aws_ecs_cluster" "loadtest_cluster" {
  name = "loadtest-cluster"
}

data "aws_ecr_repository" "grafana" {
  name = "outbox/grafana"
}

data "aws_ecr_repository" "performance-test-executor" {
  name = "outbox/performance-test-executor"
}

data "aws_ecr_repository" "performance-test-processor" {
  name = "outbox/performance-test-processor"
}

data "aws_ecr_repository" "performance-test-producer" {
  name = "outbox/performance-test-producer"
}

data "aws_ecr_repository" "prometheus" {
  name = "outbox/prometheus"
}

data "aws_ecr_image" "grafana" {
  repository_name = "outbox/grafana"
  most_recent     = true
}

data "aws_ecr_image" "performance-test-executor" {
  repository_name = "outbox/performance-test-executor"
  most_recent     = true
}

data "aws_ecr_image" "performance-test-processor" {
  repository_name = "outbox/performance-test-processor"
  most_recent     = true
}

data "aws_ecr_image" "performance-test-producer" {
  repository_name = "outbox/performance-test-producer"
  most_recent     = true
}

data "aws_ecr_image" "prometheus" {
  repository_name = "outbox/prometheus"
  most_recent     = true
}

resource "aws_ecs_task_definition" "grafana" {
  family                   = "grafana"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 2048
  memory                   = 4096
  task_role_arn            = aws_iam_role.ecs_task_role.arn
  execution_role_arn       = aws_iam_role.ecs_task_execution_role.arn

  container_definitions = jsonencode([
    {
      name  = "grafana"
      image = "${data.aws_ecr_repository.grafana.repository_url}@${data.aws_ecr_image.grafana.image_digest}"

      portMappings = [{ containerPort = 3000 }]

      environment = [
        {
          name  = "GF_AUTH_ANONYMOUS_ENABLED",
          value = "false"
        }
      ]

      secrets = [
        {
          name      = "GF_SECURITY_ADMIN_USER",
          valueFrom = "${aws_secretsmanager_secret.grafana_credentials.arn}:admin_user::"
        },
        {
          name      = "GF_SECURITY_ADMIN_PASSWORD",
          valueFrom = "${aws_secretsmanager_secret.grafana_credentials.arn}:admin_password::"
        }
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = "/ecs/grafana"
          awslogs-region        = var.aws_region
          awslogs-stream-prefix = "ecs"
        }
      }
    }
  ])
}

resource "aws_ecs_service" "grafana" {
  name            = "grafana"
  cluster         = aws_ecs_cluster.loadtest_cluster.id
  task_definition = aws_ecs_task_definition.grafana.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = module.vpc.private_subnets
    assign_public_ip = false
    security_groups  = [aws_security_group.grafana_sg.id]
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.loadtest_lb_tg.arn
    container_name   = "grafana"
    container_port   = 3000
  }

  triggers = {
    redeployment = data.aws_ecr_image.grafana.image_digest
  }
}

resource "aws_ecs_task_definition" "performance_processor" {
  family                   = "performance-processor"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 2048
  memory                   = 4096
  task_role_arn            = aws_iam_role.ecs_task_role.arn
  execution_role_arn       = aws_iam_role.ecs_task_execution_role.arn

  container_definitions = jsonencode([{
    name  = "performance-processor"
    image = "${data.aws_ecr_repository.performance-test-processor.repository_url}@${data.aws_ecr_image.performance-test-processor.image_digest}"

    portMappings = [{
      containerPort = 8081
      protocol      = "tcp"
    }]

    environment = [
      {
        name  = "SPRING_DATASOURCE_URL",
        value = "jdbc:postgresql://${aws_db_proxy.loadtest_proxy.endpoint}:5432/${aws_db_instance.loadtest_db.db_name}"
      }
    ]

    secrets = [
      {
        name      = "SPRING_DATASOURCE_USERNAME",
        valueFrom = "${aws_secretsmanager_secret.db_credentials.arn}:username::"
      },
      {
        name      = "SPRING_DATASOURCE_PASSWORD",
        valueFrom = "${aws_secretsmanager_secret.db_credentials.arn}:password::"
      }
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = "/ecs/performance-processor"
        awslogs-region        = var.aws_region
        awslogs-stream-prefix = "ecs"
      }
    }
  }])
}

resource "aws_ecs_service" "performance_processor" {
  name            = "performance-processor"
  cluster         = aws_ecs_cluster.loadtest_cluster.id
  task_definition = aws_ecs_task_definition.performance_processor.arn
  desired_count   = 3
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = module.vpc.private_subnets
    assign_public_ip = false
    security_groups  = [aws_security_group.processor_sg.id]
  }

  service_registries {
    registry_arn = aws_service_discovery_service.processor.arn
  }

  triggers = {
    redeployment = data.aws_ecr_image.performance-test-processor.image_digest
  }
}

resource "aws_ecs_task_definition" "performance_producer" {
  family                   = "performance-producer"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 2048
  memory                   = 4096
  task_role_arn            = aws_iam_role.ecs_task_role.arn
  execution_role_arn       = aws_iam_role.ecs_task_execution_role.arn

  container_definitions = jsonencode([{
    name  = "performance-producer"
    image = "${data.aws_ecr_repository.performance-test-producer.repository_url}@${data.aws_ecr_image.performance-test-producer.image_digest}"

    portMappings = [{
      containerPort = 8082
      protocol      = "tcp"
    }]

    environment = [
      {
        name  = "SPRING_R2DBC_NAME",
        value = aws_db_instance.loadtest_db.db_name
      },
      {
        name  = "SPRING_R2DBC_URL",
        value = "r2dbc:postgresql://${aws_db_proxy.loadtest_proxy.endpoint}:5432"
      },
      {
        name  = "SPRING_R2DBC_PROPERTIES_SSLMODE",
        value = "require"
      }
    ]

    secrets = [
      {
        name      = "SPRING_R2DBC_USERNAME",
        valueFrom = "${aws_secretsmanager_secret.db_credentials.arn}:username::"
      },
      {
        name      = "SPRING_R2DBC_PASSWORD",
        valueFrom = "${aws_secretsmanager_secret.db_credentials.arn}:password::"
      }
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = "/ecs/performance-producer"
        awslogs-region        = var.aws_region
        awslogs-stream-prefix = "ecs"
      }
    }
  }])
}

resource "aws_ecs_service" "performance_producer" {
  name                   = "performance-producer"
  cluster                = aws_ecs_cluster.loadtest_cluster.id
  task_definition        = aws_ecs_task_definition.performance_producer.arn
  desired_count          = 1
  launch_type            = "FARGATE"
  enable_execute_command = true

  network_configuration {
    subnets          = module.vpc.private_subnets
    assign_public_ip = false
    security_groups  = [aws_security_group.producer_sg.id]
  }

  service_registries {
    registry_arn = aws_service_discovery_service.producer.arn
  }

  triggers = {
    redeployment = data.aws_ecr_image.performance-test-producer.image_digest
  }
}

resource "aws_ecs_task_definition" "performance_executor" {
  family                   = "performance-executor"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 2048
  memory                   = 4096
  task_role_arn            = aws_iam_role.ecs_task_role.arn
  execution_role_arn       = aws_iam_role.ecs_task_execution_role.arn

  container_definitions = jsonencode([{
    name  = "performance-executor"
    image = "${data.aws_ecr_repository.performance-test-executor.repository_url}@${data.aws_ecr_image.performance-test-executor.image_digest}"

    environment = [
      {
        name  = "API_URL",
        value = "http://producer.loadtest.internal:8082/outbox/record"
      },
      {
        name  = "RATE",
        value = "200"
      },
      {
        name  = "DURATION",
        value = "5m"
      }
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = "/ecs/performance-executor"
        awslogs-region        = var.aws_region
        awslogs-stream-prefix = "ecs"
      }
    }
  }])
}

resource "aws_ecs_task_definition" "prometheus" {
  family                   = "prometheus"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 1024
  memory                   = 2048
  task_role_arn            = aws_iam_role.ecs_task_role.arn
  execution_role_arn       = aws_iam_role.ecs_task_execution_role.arn

  container_definitions = jsonencode([{
    name  = "prometheus"
    image = "${data.aws_ecr_repository.prometheus.repository_url}@${data.aws_ecr_image.prometheus.image_digest}"

    portMappings = [{
      containerPort = 9090
      protocol      = "tcp"
    }]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = "/ecs/prometheus"
        awslogs-region        = var.aws_region
        awslogs-stream-prefix = "ecs"
      }
    }
  }])
}

resource "aws_ecs_service" "prometheus" {
  name            = "prometheus"
  cluster         = aws_ecs_cluster.loadtest_cluster.id
  task_definition = aws_ecs_task_definition.prometheus.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = module.vpc.private_subnets
    assign_public_ip = false
    security_groups  = [aws_security_group.prometheus_sg.id]
  }

  service_registries {
    registry_arn = aws_service_discovery_service.prometheus.arn
  }

  triggers = {
    redeployment = data.aws_ecr_image.prometheus.image_digest
  }
}
