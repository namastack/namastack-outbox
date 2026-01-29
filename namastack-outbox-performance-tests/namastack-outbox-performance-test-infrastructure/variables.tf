variable "aws_region" {
  type = string
  default = "eu-central-1"
}

variable "aws_access_key" {
  type = string
  sensitive = true
}
variable "aws_secret_key" {
  type = string
  sensitive = true
}

variable "db_password" {
  type = string
  sensitive = true
}

variable "grafana_admin_password" {
  type = string
  sensitive = true
}
