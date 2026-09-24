# ---------------------------------------------------------------------------
# Inputs.
#
# No variable here has a secret as its default, and none is committed with a
# real value. terraform.tfvars is git-ignored; terraform.tfvars.example shows
# the shape.
# ---------------------------------------------------------------------------

variable "project_id" {
  description = "Google Cloud project id. No default: applying into the wrong project is not a recoverable mistake."
  type        = string
}

variable "region" {
  description = "Region for Cloud Run, Cloud SQL and Memorystore. All three must agree, or every request pays a cross-region round trip."
  type        = string
  default     = "europe-west1"
}

variable "environment" {
  description = "Environment name, used in resource names and labels."
  type        = string
  default     = "dev"

  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "environment must be dev, staging or prod."
  }
}

variable "backend_image" {
  description = "Fully qualified image, tagged by commit SHA. Never :latest, because a deployment must name an immutable artefact or a rollback has nothing to return to."
  type        = string

  validation {
    condition     = !endswith(var.backend_image, ":latest")
    error_message = "Use an immutable tag such as a commit SHA, not :latest."
  }
}

variable "db_tier" {
  description = "Cloud SQL machine type. db-f1-micro is the cheapest tier and is fine for a portfolio deployment; it is not a production sizing."
  type        = string
  default     = "db-f1-micro"
}

variable "db_deletion_protection" {
  description = "Whether Terraform is allowed to destroy the database. Defaults to true, so `terraform destroy` cannot silently take the data with it."
  type        = bool
  default     = true
}

variable "redis_memory_gb" {
  description = "Memorystore capacity. Leaderboards are the main consumer; 1GB holds a large number of sorted-set entries."
  type        = number
  default     = 1
}

variable "min_instances" {
  description = "Cloud Run minimum instances. Zero means scale to zero and pay nothing when idle, at the cost of a cold start on the first request."
  type        = number
  default     = 0
}

variable "max_instances" {
  description = "Cloud Run ceiling. Bounded deliberately: each instance opens a database connection pool, and unbounded autoscaling exhausts Cloud SQL connections long before it exhausts CPU."
  type        = number
  default     = 5
}

variable "gemini_api_key" {
  description = "Gemini API key, stored in Secret Manager. Leave empty to deploy with AI in fallback mode, which is a supported configuration."
  type        = string
  default     = ""
  sensitive   = true
}

variable "labels" {
  description = "Labels applied to every resource, so cost can be attributed."
  type        = map(string)
  default = {
    application = "gamehub"
    managed-by  = "terraform"
  }
}
