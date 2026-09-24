# ---------------------------------------------------------------------------
# Provider and backend configuration.
#
# Versions are pinned with pessimistic constraints. An unpinned provider means
# a plan can change because Google published a release, not because anyone
# edited this code, and that is the opposite of infrastructure as code.
# ---------------------------------------------------------------------------

terraform {
  required_version = "~> 1.10"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.14"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # Remote state, commented out because it cannot be created by the code that
  # depends on it: the bucket has to exist first. Uncomment and run
  # `terraform init -migrate-state` once you have one.
  #
  # Remote state matters as soon as more than one person applies. Local state
  # means two people can apply conflicting plans with no locking and no shared
  # record of what actually exists.
  #
  # backend "gcs" {
  #   bucket = "gamehub-terraform-state"
  #   prefix = "env/prod"
  # }
}

provider "google" {
  project = var.project_id
  region  = var.region
}
