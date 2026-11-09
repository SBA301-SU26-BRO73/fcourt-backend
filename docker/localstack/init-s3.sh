#!/bin/sh
# Runs once LocalStack is ready: creates the private bucket used by the services in local development.
set -eu
BUCKET="${STORAGE_S3_BUCKET:-fcourt-local}"
awslocal s3api create-bucket --bucket "$BUCKET" --create-bucket-configuration LocationConstraint=ap-southeast-1 >/dev/null 2>&1 || true
awslocal s3api put-public-access-block --bucket "$BUCKET" --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
echo "S3 bucket $BUCKET ready"
