aws ecs run-task \
  --cluster loadtest-cluster \
  --launch-type FARGATE \
  --task-definition performance-executor \
  --count 1 \
  --network-configuration "awsvpcConfiguration={subnets=[subnet-0aee441509ba04f43,subnet-09465e291136819e1],securityGroups=[sg-0b624e64e8c1ad8c8],assignPublicIp=ENABLED}" \
  --overrides '{
    "containerOverrides": [
      {
        "name": "performance-executor",
        "environment": [
          {"name": "API_URL","value":"http://10.0.101.48:8082/outbox/record"},
          {"name": "RATE","value":"400"},
          {"name": "DURATION","value":"5m"}
        ]
      }
    ]
  }'
