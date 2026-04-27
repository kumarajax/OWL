# Deployment Plan

## Local Development

Use Docker Compose for:

- PostgreSQL
- Redis
- RabbitMQ
- OpenSearch
- Qdrant
- Keycloak
- Core API
- Storage Control
- Storage Node
- AI Service
- Frontend

## Production Baseline

Recommended first production target:

- Linux servers with attached disks.
- Caddy or Nginx at edge for TLS.
- Keycloak clustered with PostgreSQL.
- PostgreSQL with backups and PITR.
- Redis HA.
- RabbitMQ quorum queues.
- Multiple Storage Nodes across separate disks/hosts.

## Kubernetes Later

Move stateless services first:

- Frontend
- Core API
- AI Service
- Workers
- Storage Control

Storage Nodes can run as DaemonSets or static services depending on disk management.

## Backup And DR

- PostgreSQL PITR backups.
- Keycloak realm export backups.
- Storage chunk replica audit.
- Periodic restore drills.
- Separate audit log archive.

