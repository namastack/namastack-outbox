aws ecs run-task \
  --cluster loadtest-cluster \
  --launch-type FARGATE \
  --task-definition performance-executor \
  --count 1 \
  --network-configuration "awsvpcConfiguration={subnets=[subnet-0e88a74666a150f5d],securityGroups=[sg-09ac32efd8592930c],assignPublicIp=DISABLED}" \
  --overrides '{
    "containerOverrides": [
      {
        "name": "performance-executor",
        "environment": [
          {"name": "API_URL","value":"http://producer.loadtest.internal:8082/outbox/record"},
          {"name": "RATE","value":"6000"},
          {"name": "DURATION","value":"5m"}
        ]
      }
    ]
  }'
