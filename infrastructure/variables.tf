variable "application_prefix" {
  description = "Prefix for all resources"
  type        = string
}


variable "openai_api_key" {
  description = "OpenAI API key"
  type        = string
  sensitive   = true
}

variable "openai_model_name" {
  description = "OpenAI model name for the skill"
  type        = string
}

variable "openai_chat_temperature" {
  description = "Temperature setting for OpenAI chat model"
  type        = number
  default     = 0.8
}

variable "openai_chat_max_tokens" {
  description = "Maximum tokens for OpenAI chat model"
  type        = number
  default     = 4096
}

variable "cohere_api_key" {
  description = "Cohere API key"
  type        = string
  sensitive   = true
}

variable "cohere_model_name" {
  description = "Model name for scoring model"
  type        = string
  default = "rerank-multilingual-v3.0"
}

variable "langcache_api_base_url" {
  description = "LangCache API base URL"
  type        = string
}

variable "langcache_api_key" {
  description = "LangCache API key"
  type        = string
  sensitive   = true
}

variable "langcache_cache_id" {
  description = "LangCache cache ID"
  type        = string
}

variable "redis_agent_memory_api_url" {
  description = "Redis Agent Memory managed service API endpoint"
  type        = string
}

variable "redis_agent_memory_api_key" {
  description = "Redis Agent Memory managed service API key (Bearer token)"
  type        = string
  sensitive   = true
}

variable "redis_agent_memory_store_id" {
  description = "Redis Agent Memory Store ID"
  type        = string
}

variable "alexa_skill_id" {
  type = string
}

variable "knowledge_base_bucket_name" {
  description = "S3 bucket for knowledge data files"
  type        = string
}

variable "create_knowledge_base_bucket" {
  description = "Whether to create a new knowledge base bucket or use an existing one"
  type        = bool
  default     = true
}

resource "random_id" "random_id" {
  byte_length = 4
}
