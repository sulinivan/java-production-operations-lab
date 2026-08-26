output "instance_name" {
  description = "Реальное имя Lima-инстанса."
  value       = lima_instance.lab.instance_name
}

output "vm_status" {
  description = "Статус инстанса."
  value       = lima_instance.lab.status
}

output "ssh_config_path" {
  description = "Путь к SSH-конфигу Lima. Используется Ansible inventory и скриптами."
  value       = lima_instance.lab.ssh_config
}

output "ssh_command" {
  description = "Готовая команда подключения к VM."
  value       = "ssh -F ${lima_instance.lab.ssh_config} ${lima_instance.lab.hostname}"
}
