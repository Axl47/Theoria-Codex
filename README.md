---
created_at: 2026-02-24T18:16
updated_at: 2026-02-24T18:19
---
# Theoria Codex

Theoria Codex is an Android-first, local-first, tag-driven art browser currently in MVP implementation.

The product spec lives at `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/docs/TheoriaSpec.md`.

## Current Implementation Status

The project now includes a runnable Android scaffold with a portrait-locked app shell and four top-level tabs:

- Search
- Explore
- Codex
- Settings

Core domain contracts from the spec are in place, including:

- Post and query models
- Codex models
- Source adapter interfaces and capability model
- Deterministic `QueryHash` utility (with initial unit tests)
- Draft/Applied query state primitives and source capability exclusion helpers

Core data scaffolding now includes:

- Repository interfaces for Codex, query state, settings, and cache behaviors
- In-memory repository implementations used as integration-safe placeholders
- File-backed repository implementations for persisted local state
- Unit tests for in-memory repository behavior

Stub-source execution now includes:

- JSON fixture datasets for Pixiv, Gelbooru, and AIBooru with paging/trending/scenarios
- Scenario-aware stub source adapters (`Normal`, `Partial Failure`, `Empty Results`, `Slow Network`)
- Unified capability-aware weighted search orchestrator with tests

## Project Structure

- `app`: Android application shell, navigation, and top-level UI placeholders.
- `core-domain`: immutable domain models and source adapter contracts.
- `core-data`: repository interfaces and data-layer contract surface.
- `core-stubs`: stub scenario models for fixture-based source simulation.
- `docs/execplans`: living execution plans.

## Local Development

From `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex`:

    ./gradlew tasks --all
    ./gradlew assembleDebug
    ./gradlew :core-domain:test

For app unit tests:

    ./gradlew testDebugUnitTest

For instrumentation and lint:

    ./gradlew connectedDebugAndroidTest
    ./gradlew lintDebug

Build before running on device/emulator:

    ./gradlew assembleDebug
    ./gradlew installDebug

## Implementation Plan

Execution is tracked in:

- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/docs/execplans/theoria-codex-mvp-execplan.md`
- `/Users/axel/Desktop/Code_Projects/Personal/Theoria Codex/working_list.md`
