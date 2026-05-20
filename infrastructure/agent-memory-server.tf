# The self-hosted Redis Agent Memory Server (ECS/ALB/autoscaling infrastructure)
# has been replaced by the Redis Agent Memory managed service on Redis Cloud.
#
# To set up the managed service:
#   1. Create a database on Redis Cloud (handled by redis-database.tf)
#   2. Create an Agent Memory service for that database in the Redis Cloud console
#   3. Copy the API endpoint, API key, and Store ID into your terraform.tfvars
#
# See: https://redis.io/docs/latest/operate/rc/context-engine/agent-memory/create-service/
