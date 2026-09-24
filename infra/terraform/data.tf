# ---------------------------------------------------------------------------
# Stateful services: Postgres, Redis, and the secrets they need.
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# Cloud SQL
# ---------------------------------------------------------------------------

resource "google_sql_database_instance" "postgres" {
  name             = "${local.name}-postgres"
  region           = var.region
  database_version = "POSTGRES_16"

  # Guards against `terraform destroy` taking the database with it. Set
  # explicitly rather than left to the provider default, because the default
  # is the kind of thing that changes between provider versions.
  deletion_protection = var.db_deletion_protection

  settings {
    tier              = var.db_tier
    availability_type = var.environment == "prod" ? "REGIONAL" : "ZONAL"
    disk_type         = "PD_SSD"
    disk_size         = 10
    disk_autoresize   = true
    user_labels       = local.labels

    ip_configuration {
      # No public IP. The only route in is the private network above.
      ipv4_enabled    = false
      private_network = google_compute_network.main.id
      ssl_mode        = "ENCRYPTED_ONLY"
    }

    backup_configuration {
      enabled = true
      # Point-in-time recovery, so the recovery target is a moment rather than
      # whenever the last nightly backup happened to run.
      point_in_time_recovery_enabled = true
      start_time                     = "03:00"
      transaction_log_retention_days = 7

      backup_retention_settings {
        retained_backups = 7
        retention_unit   = "COUNT"
      }
    }

    database_flags {
      # Matches docker-compose, so a slow query found locally is found here
      # by the same mechanism.
      name  = "log_min_duration_statement"
      value = "200"
    }

    database_flags {
      # The extension the schema uses for query analysis.
      name  = "cloudsql.enable_pg_cron"
      value = "off"
    }

    insights_config {
      query_insights_enabled  = true
      record_application_tags = true
    }
  }

  depends_on = [google_service_networking_connection.private_services]
}

resource "google_sql_database" "gamehub" {
  name     = "gamehub"
  instance = google_sql_database_instance.postgres.name
}

# Generated, never written down. A password chosen by a human ends up in a
# chat message; this one exists only in state and in Secret Manager.
resource "random_password" "db" {
  length  = 32
  special = true
  # Excludes characters that need escaping in a JDBC URL.
  override_special = "-_.~"
}

resource "google_sql_user" "gamehub" {
  name     = "gamehub"
  instance = google_sql_database_instance.postgres.name
  password = random_password.db.result
}

# ---------------------------------------------------------------------------
# Memorystore
#
# Every Redis key in GameHub is a cache or a lease with a Postgres fallback,
# so BASIC tier is the right trade: losing the instance costs latency, not
# data. STANDARD_HA would double the cost to protect something that is
# designed to be disposable.
# ---------------------------------------------------------------------------

resource "google_redis_instance" "cache" {
  name           = "${local.name}-redis"
  tier           = "BASIC"
  memory_size_gb = var.redis_memory_gb
  region         = var.region
  labels         = local.labels

  authorized_network = google_compute_network.main.id
  connect_mode       = "PRIVATE_SERVICE_ACCESS"

  redis_version = "REDIS_7_0"

  redis_configs = {
    # Matches docker-compose. Bounds memory, and every key here is safe to
    # evict by design.
    maxmemory-policy = "allkeys-lru"
  }

  depends_on = [google_service_networking_connection.private_services]
}

# ---------------------------------------------------------------------------
# Secrets
#
# Three secrets, none of which has its value in this repository. The JWT key
# is generated here; the database password comes from random_password; the
# Gemini key is supplied by the operator or left empty.
# ---------------------------------------------------------------------------

resource "random_password" "jwt_secret" {
  # 64 characters, comfortably above the 32-byte HS256 minimum the application
  # enforces at startup.
  length  = 64
  special = false
}

resource "google_secret_manager_secret" "jwt_secret" {
  secret_id = "${local.name}-jwt-secret"
  labels    = local.labels

  replication {
    auto {}
  }

  depends_on = [google_project_service.required]
}

resource "google_secret_manager_secret_version" "jwt_secret" {
  secret      = google_secret_manager_secret.jwt_secret.id
  secret_data = random_password.jwt_secret.result
}

resource "google_secret_manager_secret" "db_password" {
  secret_id = "${local.name}-db-password"
  labels    = local.labels

  replication {
    auto {}
  }

  depends_on = [google_project_service.required]
}

resource "google_secret_manager_secret_version" "db_password" {
  secret      = google_secret_manager_secret.db_password.id
  secret_data = random_password.db.result
}

resource "google_secret_manager_secret" "gemini_api_key" {
  secret_id = "${local.name}-gemini-api-key"
  labels    = local.labels

  replication {
    auto {}
  }

  depends_on = [google_project_service.required]
}

resource "google_secret_manager_secret_version" "gemini_api_key" {
  secret = google_secret_manager_secret.gemini_api_key.id
  # An empty key is a supported deployment: the application starts and the AI
  # endpoints run in fallback mode. A placeholder is used because Secret
  # Manager rejects an empty payload.
  secret_data = var.gemini_api_key != "" ? var.gemini_api_key : "unset"
}
