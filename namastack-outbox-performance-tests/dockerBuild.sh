#!/bin/bash

set -e  # exit on any error
set -o pipefail

# AWS & ECR configuration
AWS_REGION="eu-central-1"
AWS_ACCOUNT_ID="181389211132"
ECR_NAMESPACE="outbox"

GRADLE_MODULES=("performance-test-processor" "performance-test-producer")

# Step 0: Ensure AWS CLI and Docker are logged in
echo "Logging into AWS ECR..."
aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"

# Step 1: Clean and build all gradle modules
echo "Cleaning and building all modules..."
./gradlew clean build -x test

# Step 2: Loop through modules
for MODULE in "${GRADLE_MODULES[@]}"; do
    echo "======================================="
    echo "Processing module: $MODULE"

    MODULE_DIR="./namastack-outbox-$MODULE"
    IMAGE_NAME="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${ECR_NAMESPACE}/${MODULE}:latest"

    # Check if Dockerfile exists
    if [[ ! -f "$MODULE_DIR/Dockerfile" ]]; then
        echo "⚠️ Dockerfile not found in $MODULE_DIR, skipping module..."
        continue
    fi

    echo "Building Docker image for $MODULE..."
    docker build -t "$IMAGE_NAME" "$MODULE_DIR"

    echo "Pushing Docker image to ECR: $IMAGE_NAME"
    docker push "$IMAGE_NAME"

    echo "✅ Module $MODULE done"
done

# Step 3: Build and push executor module
K6_MODULE_DIR="./namastack-outbox-performance-test-executor"
K6_IMAGE_NAME="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/${ECR_NAMESPACE}/performance-test-executor:latest"

if [[ ! -f "$K6_MODULE_DIR/Dockerfile" ]]; then
    echo "⚠️ Dockerfile not found in $K6_MODULE_DIR, skipping module..."
    exit
fi

echo "Building Docker image for namastack-outbox-performance-test-executor..."
docker build -t "$K6_IMAGE_NAME" "$K6_MODULE_DIR"
echo "Pushing Docker image to ECR: $IMAGE_NAME"
docker push "$K6_IMAGE_NAME"

echo "✅ Module namastack-outbox-performance-test-executor done"

echo "======================================="
echo "All modules built and pushed successfully!"
