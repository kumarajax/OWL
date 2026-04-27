# Architecture Design

## Goals

OWL Drive is a fully self-hosted cloud storage and collaboration platform. The design avoids paid object storage and uses local disks attached to storage nodes. It scales horizontally through stateless API services, distributed chunk storage, background workers, and event-driven processing.

## High-Level Components

- Web App: Next.js, React, TypeScript, Tailwind CSS.
- API Gateway / Edge: Nginx or Caddy terminating TLS and routing traffic.
- Identity Provider: Keycloak for users, organizations, groups, OAuth federation, MFA, and sessions.
- Core API: Spring Boot service for file metadata, permissions, sharing, audit, and orchestration.
- Storage Control Service: assigns chunk placement, tracks node health, schedules replication/repair/rebalance.
- Storage Nodes: store encrypted file chunks on mounted local disks.
- Database: PostgreSQL for metadata, permissions, audit, storage maps, and policies.
- Cache: Redis for rate limits, short-lived auth/session state, upload sessions, locks.
- Queue: RabbitMQ initially; Kafka later for high-scale event streams.
- Search: OpenSearch for metadata, content, OCR, and AI summaries with permission filtering.
- AI Service: FastAPI service for summaries, classification, DLP, embeddings, and duplicate detection.
- Vector Store: Qdrant initially; pgvector is acceptable for smaller deployments.
- Workers: background services for virus scanning, extraction, OCR, AI, repair, notifications, and retention.

## Request Flow

1. Browser authenticates through Keycloak.
2. Frontend calls Core API with JWT access token.
3. Spring Security validates JWT, roles, tenant, groups, and scopes.
4. Core API validates object existence and permissions on every request.
5. Uploads create an upload session and chunk manifest.
6. Storage Control Service assigns chunk replicas to Storage Nodes.
7. Storage Nodes accept encrypted chunks, validate checksums, and report health.
8. Core API commits metadata only after required replicas are durable.
9. Events are emitted for audit, search indexing, virus scan, preview, AI, and notification jobs.

## Storage Model

Files are split into content-addressable chunks. Physical paths use UUIDs and never expose user file names or logical paths.

Chunk write path:

1. Client uploads to Core API or directly to pre-authorized Storage Node endpoints.
2. Chunk SHA-256 is calculated before commit.
3. Chunk is encrypted with envelope encryption.
4. Chunk replicas are written to distinct disks/nodes.
5. Storage Control records chunk locations and replica health.

Recommended defaults:

- Chunk size: 8 MiB for normal files; adaptive larger chunks for multi-GB files.
- Replication factor: 3 for production, 2 for small home lab.
- Checksum: SHA-256.
- Encryption: AES-256-GCM per chunk; envelope key protected by KMS-compatible local key service or HSM later.

## Multi-Tenancy

Every metadata row is scoped by `tenant_id`. Personal users have a personal tenant. Organizations own one or more tenants and groups. Every query must include tenant and permission constraints.

## Scaling Plan

- Scale Core API horizontally behind load balancer.
- Keep APIs stateless.
- Partition large tables by tenant/time where needed.
- Use read replicas for analytics and admin views.
- Separate hot metadata from cold audit/event data.
- Add Storage Nodes dynamically through heartbeat registration.
- Use background repair and rebalance workers.
- Move from RabbitMQ to Kafka when event throughput requires partitioned streams.

