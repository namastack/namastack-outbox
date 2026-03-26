#!/bin/bash
# Creates the SNS topics in LocalStack on startup
awslocal sns create-topic --name customers --region us-east-1
awslocal sns create-topic --name customer-registrations --region us-east-1
awslocal sns create-topic --name default-topic --region us-east-1

echo "SNS topics created successfully"

