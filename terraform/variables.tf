variable "vm_name" {
  description = "Имя Lima-инстанса (deployment target лаборатории)."
  type        = string
  default     = "java-prod-ops-lab"
}

variable "cpus" {
  description = "vCPU для VM. 6 — бюджет под app + зависимости + monitoring (ADR-002)."
  type        = number
  default     = 6
}

variable "memory" {
  description = "RAM VM. Java + PostgreSQL + MinIO + Redis x3 + ClamAV + Prometheus stack."
  type        = string
  default     = "12GiB"
}

variable "disk" {
  description = "Диск VM: образы kind, PVC, логи, backups."
  type        = string
  default     = "80GiB"
}
