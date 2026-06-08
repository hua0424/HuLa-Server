# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

HuLa-Server (`com.luohuo.flex`) is the IM + AI backend for HuLa, built on Spring Cloud 2024 / Spring Boot 3 / Java 21, with Netty, MyBatis-Plus, RocketMQ, Redis, and MySQL. It is a Maven multi-module microservices system. The framework parts (modeled on lamp-cloud) are split out into a separately-versioned starter library.

## Two top-level Maven projects (build order matters)

This repo contains **two independent Maven reactors**, not one:

1. **`luohuo-util/`** — the framework/starter library (version `3.0.6`, artifacts under `com.luohuo`). Provides `luohuo-core`, `luohuo-databases`, `luohuo-uid`, `luohuo-tenant`, and the `*-starter` modules (cache, captcha, cloud, echo, log, mq, scan, swagger2, transaction, validator, xss). It is **not** part of the `luohuo-cloud` reactor — it must be `mvn install`ed to the local repo **first**, because `luohuo-cloud` depends on its published artifacts.

2. **`luohuo-cloud/`** — the actual microservices application (version `3.0.6`), which consumes the `luohuo-util` artifacts.

### Build commands

```bash
# 1. Install the framework library to the local Maven repo (REQUIRED first, fast flags):
cd luohuo-util
mvn clean install -Dmaven.javadoc.skip=true -Dgpg.skip=true -Dmaven.source.skip=true -DskipTests=true -f pom.xml

# 2. Install the microservices reactor. The `install` (not just `package`) step is REQUIRED:
#    src/main/filters/*.properties values are filtered into target/ during install — bootstrap.yml
#    placeholders like @nacos.ip@, @profile.active@, @database.type@ are resolved at this step.
cd ../luohuo-cloud
mvn clean install -DskipTests=true

# Bump version across a reactor:
mvn versions:set -DnewVersion="3.0.7" -DskipTests -DgenerateBackupPoms=false
```

There are no unit tests of note; `-DskipTests=true` is standard. Production deploy uses `luohuo-cloud/src/main/bin/*.sh` (Jenkins) — `run.sh <module> <dir> <profile> {start|stop|restart|status}`, plus per-service `restart-luohuo-*.sh` and `all-start.sh` / `all-stop.sh`.

## Required infrastructure (must be running before any service)

Nacos (dev: `3.0.2`, web port `8080`; older `8848`), RocketMQ, MySQL, Redis. Optional: Seata, Sentinel dashboard. See `luohuo-cloud/开发必看.md` for the local bring-up steps and `docs/install/docker/` for one-click component deployment. Import schemas from `docs/sql/` (`luohuo_dev.sql`, `luohuo_im_01.sql`).

## Configuration lives in Nacos, not in the jars

Each `*-server` ships only a thin `bootstrap.yml` (filtered placeholders) + `bootstrap-dev.yml` (switches logback config). **All real config is pulled from Nacos** via `shared-configs`: `common.yml`, `redis.yml`, the datasource config (`@database.type@`), and `rocketmq.yml`. The seed copies of every Nacos config live in `luohuo-support/luohuo-boot-server/src/main/resources/config/{dev,prod}/` (`mysql.yml`, `redis.yml`, `rocketmq.yml`, `sa-token.yml`, `oss.yml`, `gateway-server.yml`, per-service `*-server.yml`, …). **Edit config there and re-publish to Nacos** — editing a running service's classpath does nothing.

## Module anatomy

`luohuo-cloud` aggregates these reactors (see root `pom.xml`): `luohuo-dependencies-parent` (BOM / version management), `luohuo-public` (shared SDKs), `luohuo-base`, `luohuo-oauth`, `luohuo-ws`, `luohuo-im`, `luohuo-gateway`, `luohuo-support`, `luohuo-generator`, `luohuo-ai`, `luohuo-system`.

Each business service follows the same **five-layer split** (e.g. `luohuo-im/`):

- `*-entity` — domain entities, DTOs, enums
- `*-facade` — Feign client interfaces + DTOs exposed to **other** services (inter-service contract)
- `*-biz` — business logic, mappers, services (the bulk of the code)
- `*-controller` — REST controllers (some services keep controllers inside `*-biz` instead, e.g. `luohuo-ai`)
- `*-server` — the Spring Boot entry point (`*ServerApplication.java`) + `bootstrap.yml`; this is the deployable

`luohuo-public` holds cross-service building blocks: `luohuo-common`, `luohuo-model`, `luohuo-router`, `luohuo-config-sdk`, `luohuo-file-sdk`, `luohuo-data-scope-sdk`, `luohuo-database-mode`, `luohuo-login-user-facade`, `luohuo-sa-token-ext`.

## Service topology & gateway routing

The gateway (`luohuo-gateway`) is the single entry. It has a virtual `context-path: /api` that `ContextPathFilter` strips, then routes by path prefix (config in `.../config/dev/gateway-server.yml`), each with `StripPrefix=1`:

| Path prefix | Service | Role |
|---|---|---|
| `/oauth/**` | luohuo-oauth-server | login (password / SMS / email / QR-scan), token issuance |
| `/im/**` | luohuo-im-server | messages, groups, friends, conversations (persistence + dispatch) |
| `/ws/**` | luohuo-ws-server | WebFlux+Netty long connections, push, WebRTC P2P (SRS) |
| `/base/**` | luohuo-base-server | multi-tenant, org/role/RBAC, application registry |
| `/system/**` | luohuo-system-server | admin/config, stats, content audit |
| `/ai/**` | luohuo-ai-server | multimodal AI (chat / image / audio / video / music) |

`spring.application.path` in each `bootstrap.yml` (e.g. `/im`) must match its gateway prefix — `luohuo-scan-starter` uses it to register endpoint URIs for permission scanning.

## IM message dispatch (the core flow)

Single/group message path: **client → gateway → luohuo-im (persist) → `PushService` → query routing table for the target user's WS node → resolve node-device-user mapping → publish to that WS node's dedicated RocketMQ topic → WS node consumes → look up local session (fingerprint) map → push to client → client ACK → mark delivered**. This "precise routing" (vs. broadcast) is what keeps push O(k) instead of O(N) across nodes — do not reintroduce all-node broadcast. RocketMQ is also used for transactional/ordered messaging in IM.

## AI module (`luohuo-ai`)

Built on **Spring AI 1.0.0-M6**, integrating 20+ providers (OpenAI, DeepSeek, Kimi, 通义千问, Gitee AI/魔力方舟, 硅基流动/SiliconFlow, etc.) plus image (Midjourney, Stable Diffusion), TTS, text-to-video, and Suno music. Provider/model instances are created through `core/AiModelFactory` (impl `AiModelFactoryImpl`); chat provider selection goes through `core/model/strategy/chat/ChatStrategyFactory`. Image/audio/video generation are async task + status-polling flows. Controllers are organized by capability under `.../ai/controller/{chat,image,audio,video,music,knowledge,mindmap,model,platform}`. TinyFlow workflow engine is integrated for orchestrated AI scenarios.

## Conventions

- Auth/permissions: **SA-Token** (`1.42.0`), config in Nacos `sa-token.yml`; gateway does JWT verification + permission checks.
- Persistence: **MyBatis-Plus** (`3.5.11`) + `mybatis-plus-join`, **Dynamic Datasource** for multi-tenant sharding.
- Logging path is set in `bootstrap.yml` (absolute path, intentionally — see the comment there); never move logging config out of `bootstrap.yml` or it creates `log.path_IS_UNDEFINED` dirs.
- `gray_version` metadata in Nacos discovery is used for gray-release routing.
