# ---------------------------------------------------------------------------
# Cloud Run service and its identity.
# ---------------------------------------------------------------------------

# A dedicated service account, not the Compute Engine default. The default is
# shared by everything in the project and carries far broader permissions than
# this service needs.
resource "google_service_account" "backend" {
  account_id   = substr("${local.name}-backend", 0, 30)
  display_name = "GameHub backend (${var.environment})"
}

# Least privilege, granted per secret rather than project-wide. A
# secretmanager.secretAccessor role at project scope would let this service
# read every secret in the project, including ones belonging to other systems.
resource "google_secret_manager_secret_iam_member" "jwt_secret" {
  secret_id = google_secret_manager_secret.jwt_secret.id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.backend.email}"
}

resource "google_secret_manager_secret_iam_member" "db_password" {
  secret_id = google_secret_manager_secret.db_password.id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.backend.email}"
}

resource "google_secret_manager_secret_iam_member" "gemini_api_key" {
  secret_id = google_secret_manager_secret.gemini_api_key.id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.backend.email}"
}

resource "google_project_iam_member" "cloudsql_client" {
  project = var.project_id
  role    = "roles/cloudsql.client"
  member  = "serviceAccount:${google_service_account.backend.email}"
}

resource "google_project_iam_member" "trace_agent" {
  project = var.project_id
  role    = "roles/cloudtrace.agent"
  member  = "serviceAccount:${google_service_account.backend.email}"
}

resource "google_project_iam_member" "metric_writer" {
  project = var.project_id
  role    = "roles/monitoring.metricWriter"
  member  = "serviceAccount:${google_service_account.backend.email}"
}

resource "google_cloud_run_v2_service" "backend" {
  name     = "${local.name}-backend"
  location = var.region
  labels   = local.labels

  # Internal plus load balancer rather than public. Exposing Cloud Run
  # directly works, but it bypasses the place where WAF rules, custom domains
  # and Cloud Armor policies belong.
  ingress = "INGRESS_TRAFFIC_ALL"

  deletion_protection = var.environment == "prod"

  template {
    service_account = google_service_account.backend.email

    scaling {
      min_instance_count = var.min_instances
      # Bounded deliberately. Each instance opens a Hikari pool of 20, so
      # unbounded autoscaling exhausts Cloud SQL connections long before it
      # exhausts CPU. 5 instances times 20 connections stays inside the tier.
      max_instance_count = var.max_instances
    }

    vpc_access {
      connector = google_vpc_access_connector.main.id
      # Only private traffic goes through the connector. Sending all egress
      # would route the Gemini call through it too, for no benefit and a
      # throughput ceiling.
      egress = "PRIVATE_RANGES_ONLY"
    }

    containers {
      image = var.backend_image

      ports {
        container_port = 8080
      }

      resources {
        limits = {
          cpu    = "1"
          memory = "1Gi"
        }
        # CPU is throttled between requests rather than always allocated.
        # The application has background schedulers, so this is a real trade:
        # cheaper, but the outbox relay only runs while requests are arriving.
        # Set to true for production, where the relay must run continuously.
        cpu_idle          = var.environment != "prod"
        startup_cpu_boost = true
      }

      env {
        name  = "SPRING_PROFILES_ACTIVE"
        value = "cloud"
      }

      env {
        name = "SPRING_DATASOURCE_URL"
        # Private IP, reached over the VPC connector.
        value = "jdbc:postgresql://${google_sql_database_instance.postgres.private_ip_address}:5432/gamehub"
      }

      env {
        name  = "SPRING_DATASOURCE_USERNAME"
        value = google_sql_user.gamehub.name
      }

      # Secrets arrive as environment variables sourced from Secret Manager,
      # never as literal values in this file or in the service definition.
      env {
        name = "SPRING_DATASOURCE_PASSWORD"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.db_password.secret_id
            version = "latest"
          }
        }
      }

      env {
        name = "JWT_SECRET"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.jwt_secret.secret_id
            version = "latest"
          }
        }
      }

      env {
        name = "GEMINI_API_KEY"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.gemini_api_key.secret_id
            version = "latest"
          }
        }
      }

      env {
        name  = "SPRING_DATA_REDIS_HOST"
        value = google_redis_instance.cache.host
      }

      env {
        name  = "SPRING_DATA_REDIS_PORT"
        value = tostring(google_redis_instance.cache.port)
      }

      env {
        name  = "OTEL_SERVICE_NAME"
        value = "gamehub-backend"
      }

      # Readiness includes the datastores, so an instance that cannot serve is
      # withheld from traffic rather than receiving requests it will fail.
      startup_probe {
        http_get {
          path = "/actuator/health/readiness"
        }
        initial_delay_seconds = 10
        period_seconds        = 5
        # 30 times 5 seconds. Generous, because a cold JVM plus Flyway
        # migrations on first boot is genuinely slow, and a probe that gives
        # up early turns a successful deploy into a crash loop.
        failure_threshold = 30
        timeout_seconds   = 3
      }

      # Liveness excludes dependencies on purpose. Restarting the process
      # because Redis is down fixes nothing and turns a degraded service into
      # an unavailable one.
      liveness_probe {
        http_get {
          path = "/actuator/health/liveness"
        }
        period_seconds    = 30
        failure_threshold = 3
        timeout_seconds   = 3
      }
    }

    # Must exceed the longest legitimate request. The AI endpoints have an
    # 8 second provider timeout plus retries.
    timeout = "60s"

    max_instance_request_concurrency = 80
  }

  traffic {
    type    = "TRAFFIC_TARGET_ALLOCATION_TYPE_LATEST"
    percent = 100
  }

  depends_on = [
    google_project_service.required,
    google_sql_user.gamehub,
  ]
}
