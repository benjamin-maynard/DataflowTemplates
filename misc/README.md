# Build Template

The easiest way to build the custom Dataflow template and export to GCS is via the included Cloud Build configuration located in [cloudbuild/cloudbuild.yaml](cloudbuild/cloudbuild.yaml).

The build job takes 2 substitutions:

- `_OUTPUT_BUCKET_NAME_` - The name of the GCS Bucket to output the template.
- `_FOLDER_PREFIX_` - The GCS Folder Prefix to store the compiled template.

You need to create the bucket, and ensure that the [Cloud Build Service Account](https://cloud.google.com/build/docs/cloud-build-service-account) `<project number>@cloudbuild.gserviceaccount.com` has access to the bucket (`roles/storage.ObjectAdmin`).

Also ensure the following API's are enabled (`gcloud services enable <api>`):

- `cloudresourcemanager.googleapis.com`
- `cloudbuild.googleapis.com`
- `cloudresourcemanager.googleapis.com`

You can optionally use the [included Terraform](terraform/) to create the bucket and grant access.

## Build and Execute

### (Optional) Run Terraform for GCS Bucket and IAM

1. Edit [terraform/terraform.tfvars](terraform/terraform.tfvars) as appropriate.
2. Init Terraform `terraform init`
3. Apply Terraform `terraform apply`

### Run Cloud Build Job
```
GCS_OUTPUT_BUCKET=test-dataflow-job-spanner-insert-or-ignore-template
gcloud builds submit . \
    --substitutions=_OUTPUT_BUCKET_NAME_=$GCS_OUTPUT_BUCKET,_FOLDER_PREFIX_="templates" \
    --config=misc/cloudbuild/cloudbuild.yaml
```

### Run Dataflow Job

```
export PROJECT=<project>
export REGION=<region>
export INSTANCE_ID=<instance>
export DATABASE_ID=<db>
export TEMPLATE_SPEC_GCSPATH="gs://$GCS_OUTPUT_BUCKET/templates/GCS_Text_to_Cloud_Spanner"
export IMPORT_MANIFEST="gs://<manifestlocationbucket>/<manifestjson>"

# From: v1/README_GCS_Text_to_Cloud_Spanner.md
gcloud dataflow jobs run "gcs-text-to-cloud-spanner-job14" \
  --project "$PROJECT" \
  --region "$REGION" \
  --num-workers=10 \
  --gcs-location "$TEMPLATE_SPEC_GCSPATH" \
  --parameters "instanceId=$INSTANCE_ID" \
  --parameters "databaseId=$DATABASE_ID" \
  --parameters "importManifest=$IMPORT_MANIFEST"
```
