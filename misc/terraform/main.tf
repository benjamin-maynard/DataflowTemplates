/*
 Copyright 2024 Google LLC

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

      https://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

data "google_project" "this" {
  project_id = var.project
}

resource "google_project_service" "dependencies" {
  for_each = toset([
    "storage.googleapis.com",
    "cloudbuild.googleapis.com",
    "cloudresourcemanager.googleapis.com"
  ])
  service = each.key
  project = var.project
}

resource "google_storage_bucket" "dataflow-out-template" {
  depends_on                  = [google_project_service.dependencies]
  name                        = "${var.project}-spanner-insert-or-ignore-template"
  project                     = var.project
  location                    = var.region
  uniform_bucket_level_access = true
  force_destroy               = false
}

resource "google_storage_bucket_iam_member" "cloud-build-bucket-object-admin" {
  bucket = google_storage_bucket.dataflow-out-template.id
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${data.google_project.this.number}@cloudbuild.gserviceaccount.com"
}
