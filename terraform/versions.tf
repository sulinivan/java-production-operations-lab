terraform {
  required_version = ">= 1.6"

  required_providers {
    lima = {
      # Провайдер до 1.0: минорные версии могут ломать схему,
      # поэтому пин на patch-range (README провайдера).
      source  = "guidoiaquinti/lima"
      version = "~> 0.1.0"
    }
  }
}
