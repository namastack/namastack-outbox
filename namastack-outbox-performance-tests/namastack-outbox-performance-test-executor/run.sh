aws ecs run-task \
  --cluster loadtest-cluster \
  --launch-type FARGATE \
  --task-definition performance-executor \
  --count 1 \
  --network-configuration "awsvpcConfiguration={subnets=[subnet-0fb787534e850a6f0],securityGroups=[sg-053971b44904fc6c4],assignPublicIp=ENABLED}" \
  --overrides '{
    "containerOverrides": [
      {
        "name": "performance-executor",
        "environment": [
          {"name": "API_URL","value":"http://10.0.102.139:8082/outbox/record"},
          {"name": "RATE","value":"5000"},
          {"name": "DURATION","value":"2m"}
        ]
      }
    ]
  }'
