variable "monitoring_alarm_actions" {
  description = "Optional SNS topic or other CloudWatch alarm action ARNs. Leave empty to create alarms without notifications."
  type        = list(string)
  default     = []
}

variable "monitoring_period_seconds" {
  description = "CloudWatch metric alarm and dashboard evaluation period."
  type        = number
  default     = 300

  validation {
    condition     = var.monitoring_period_seconds >= 60 && var.monitoring_period_seconds % 60 == 0
    error_message = "monitoring_period_seconds must be at least 60 and a multiple of 60."
  }
}

variable "monitoring_evaluation_periods" {
  description = "Number of consecutive periods required for worker utilization alarms."
  type        = number
  default     = 2

  validation {
    condition     = var.monitoring_evaluation_periods >= 1
    error_message = "monitoring_evaluation_periods must be at least 1."
  }
}

variable "monitoring_cpu_threshold" {
  description = "Average ECS cluster CPU utilization alarm threshold, in percent."
  type        = number
  default     = 80

  validation {
    condition     = var.monitoring_cpu_threshold > 0 && var.monitoring_cpu_threshold <= 100
    error_message = "monitoring_cpu_threshold must be greater than 0 and at most 100."
  }
}

variable "monitoring_memory_threshold" {
  description = "Average ECS cluster memory utilization alarm threshold, in percent."
  type        = number
  default     = 80

  validation {
    condition     = var.monitoring_memory_threshold > 0 && var.monitoring_memory_threshold <= 100
    error_message = "monitoring_memory_threshold must be greater than 0 and at most 100."
  }
}
