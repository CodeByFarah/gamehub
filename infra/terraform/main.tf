# ---------------------------------------------------------------------------
# GameHub on Google Cloud.
#
#   Cloud Run      stateless backend, scales to zero
#   Cloud SQL      Postgres 16, private IP only
#   Memorystore    Redis, private IP only
#   Secret Manager JWT signing key, database password, Gemini key
#   VPC connector  the only path from Cloud Run to the private data plane
#
# Kafka is deliberately absent. See the note at the bottom of this file.
# ---------------------------------------------------------------------------

locals {
  name   = "gamehub-${var.environment}"
  labels = merge(var.labels, { environment = var.environment })
}

# Enabled explicitly rather than assumed. A missing API produces an error
# hundreds of lines into an apply, which is a poor way to learn about it.
resource "google_project_service" "required" {
  for_each = toset([
    "run.googleapis.com",
    "sqladmin.googleapis.com",
    "redis.googleapis.com",
    "secretmanager.googleapis.com",
    "artifactregistry.googleapis.com",
    "vpcaccess.googleapis.com",
    "servicenetworking.googleapis.com",
    "monitoring.googleapis.com",
    "cloudtrace.googleapis.com",
  ])

  service = each.value
  # Left enabled on destroy. Disabling a service can break unrelated resources
  # elsewhere in the project that happen to share it.
  disable_on_destroy = false
}

# ---------------------------------------------------------------------------
# Network
#
# Cloud SQL and Memorystore get private IPs only. A database reachable from
# the internet is one credential leak away from being a breach, and the
# private path costs nothing extra.
# ---------------------------------------------------------------------------

resource "google_compute_network" "main" {
  name                    = "${local.name}-network"
  auto_create_subnetworks = false
  depends_on              = [google_project_service.required]
}

resource "google_compute_subnetwork" "main" {
  name          = "${local.name}-subnet"
  network       = google_compute_network.main.id
  region        = var.region
  ip_cidr_range = "10.10.0.0/24"

  # Required for Cloud Run direct VPC egress and useful for flow logs.
  private_ip_google_access = true
}

# Address range Google uses for managed services inside this VPC.
resource "google_compute_global_address" "private_services" {
  name          = "${local.name}-private-services"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  prefix_length = 16
  network       = google_compute_network.main.id
}

resource "google_service_networking_connection" "private_services" {
  network                 = google_compute_network.main.id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.private_services.name]
}

resource "google_vpc_access_connector" "main" {
  name   = substr("${local.name}-conn", 0, 25)
  region = var.region

  subnet {
    name = google_compute_subnetwork.connector.name
  }

  # Sized small. The connector is a network hop, not a compute tier, and
  # over-provisioning it is pure cost.
  min_instances = 2
  max_instances = 3

  depends_on = [google_project_service.required]
}

resource "google_compute_subnetwork" "connector" {
  name    = "${local.name}-connector-subnet"
  network = google_compute_network.main.id
  region  = var.region
  # A /28 is the size the connector requires.
  ip_cidr_range = "10.10.1.0/28"
}

# ---------------------------------------------------------------------------
# Artifact Registry
# ---------------------------------------------------------------------------

resource "google_artifact_registry_repository" "backend" {
  location      = var.region
  repository_id = "gamehub"
  format        = "DOCKER"
  labels        = local.labels

  # Keeps storage bounded without a manual clean-up job. Untagged images are
  # intermediate layers nothing refers to.
  cleanup_policies {
    id     = "delete-untagged"
    action = "DELETE"
    condition {
      tag_state  = "UNTAGGED"
      older_than = "604800s" # 7 days
    }
  }

  depends_on = [google_project_service.required]
}
