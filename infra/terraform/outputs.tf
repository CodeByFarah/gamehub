# ---------------------------------------------------------------------------
# Outputs.
#
# Nothing sensitive is output unmarked. Terraform prints outputs in plain text
# and writes them to state, so an unmarked password is a password in the
# terminal scrollback and in CI logs.
# ---------------------------------------------------------------------------

output "backend_url" {
  description = "Public URL of the Cloud Run service."
  value       = google_cloud_run_v2_service.backend.uri
}

output "artifact_registry_repository" {
  description = "Where to push backend images."
  value       = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.backend.repository_id}"
}

output "database_private_ip" {
  description = "Cloud SQL private IP. Not reachable from the internet; use the Cloud SQL Auth Proxy to connect."
  value       = google_sql_database_instance.postgres.private_ip_address
}

output "database_connection_name" {
  description = "Instance connection name, used by the Cloud SQL Auth Proxy."
  value       = google_sql_database_instance.postgres.connection_name
}

output "redis_host" {
  description = "Memorystore private endpoint."
  value       = google_redis_instance.cache.host
}

output "backend_service_account" {
  description = "Identity the backend runs as. Grant new permissions to this, not to the project default."
  value       = google_service_account.backend.email
}

output "deploy_command" {
  description = "Command to deploy a new image without a full terraform apply."
  value       = <<-EOT
    gcloud run deploy ${google_cloud_run_v2_service.backend.name} \
      --image <REGION>-docker.pkg.dev/${var.project_id}/gamehub/gamehub-backend:<COMMIT_SHA> \
      --region ${var.region} \
      --project ${var.project_id}
  EOT
}

output "secret_ids" {
  description = "Secret Manager entries this deployment reads. Values are never output."
  value = {
    jwt_secret     = google_secret_manager_secret.jwt_secret.secret_id
    db_password    = google_secret_manager_secret.db_password.secret_id
    gemini_api_key = google_secret_manager_secret.gemini_api_key.secret_id
  }
}

output "kafka_note" {
  description = "Why there is no managed Kafka here."
  value       = <<-EOT
    Kafka is intentionally absent from this configuration.

    Google Cloud Managed Service for Apache Kafka exists, but it has no
    scale-to-zero tier and costs more per month than everything else here
    combined. For a portfolio deployment that is the wrong trade.

    The options, in the order they should be considered:

      1. Run the backend with the event pipeline disabled. The outbox still
         records every event durably in Postgres, so nothing is lost; the
         consumers simply do not run and derived state is not updated.

      2. Replace the Kafka transport with Pub/Sub. The outbox pattern means
         only the relay and the listener annotations change, not the events,
         the idempotency ledger, or any consumer logic. This is the point of
         publishing through an outbox rather than directly.

      3. Provision Managed Service for Apache Kafka, if the cost is
         acceptable.

    docs/deployment.md covers all three.
  EOT
}
