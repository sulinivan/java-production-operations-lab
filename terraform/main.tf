# Infrastructure boundary проекта (README §5.1): Terraform создаёт только VM.
# Конфигурацию хоста делает Ansible, релизы приложения — Helm.

provider "lima" {}

resource "lima_instance" "lab" {
  name     = var.vm_name
  template = "template:ubuntu-24.04"

  cpus   = var.cpus
  memory = var.memory
  disk   = var.disk

  # Deployment target изолирован от developer machine (README §6):
  # шаблон ubuntu по умолчанию монтирует домашний каталог хоста — отключаем.
  config_overrides = "mounts: null\n"

  port_forwards = [
    {
      guest_port = 80
      host_port  = 80
      protocol   = "tcp"
    },
    {
      guest_port = 443
      host_port  = 443
      protocol   = "tcp"
    },
  ]
}
