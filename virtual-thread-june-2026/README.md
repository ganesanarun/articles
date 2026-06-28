# Dosa Kitchen — Java Virtual Threads Demo

A hands-on demo that makes Java Virtual Threads (Project Loom) tangible through a Dosa Kitchen metaphor.

## The metaphor

| Code concept                      | Kitchen metaphor                  |
|-----------------------------------|-----------------------------------|
| Platform / carrier thread         | Chef (physical person at a stove) |
| Virtual thread                    | A recipe / order task             |
| Blocking I/O (cold store call)    | Chef walking to the fridge        |
| `newFixedThreadPool`              | A kitchen with a fixed headcount  |
| `newVirtualThreadPerTaskExecutor` | Unlimited recipes, shared chefs   |

## Architecture

Two servers run side by side:

```
Customer → Kitchen (port 8081) → ColdStore (port 8080)
```

- **ColdStore** — simulates a slow dependency (database, API). Each request sleeps 300 ms, then responds. Backed by 80
  worker threads so it never becomes the bottleneck.
- **Kitchen** — the server under comparison. Accepts HTTP requests, fetches an ingredient from the ColdStore, and
  responds.

## Modes

| Flag                   | Executor                          | Threads                                                        |
|------------------------|-----------------------------------|----------------------------------------------------------------|
| `--mode OLD`           | `newFixedThreadPool`              | 16 platform threads (`CITY_STOVE_LIMIT × 2`)                   |
| `--mode NEW` (default) | `newVirtualThreadPerTaskExecutor` | One virtual thread per request; JVM calculated carrier threads |

In OLD mode, each platform thread blocks for the full 300 ms cold store round-trip. Beyond 16 concurrent requests,
orders queue up.

In NEW mode, virtual threads unmount from their carrier during the blocking I/O. The carrier is free to run other
virtual threads. On return the virtual thread may remount on a different carrier — the logs show when this happens.

## Requirements

- Java 21+
- `--add-opens java.base/java.lang=ALL-UNNAMED` — needed to read `currentCarrierThread()` via reflection

> **Do not use `--add-opens` in production.** This flag bypasses the Java module system to access `Thread.currentCarrierThread()`, an internal JVM API with no stability guarantees. It exists here purely to make carrier thread scheduling visible for learning purposes. Production code has no business reading carrier thread state.

## Run

```bash
# NEW mode (default)
java --add-opens java.base/java.lang=ALL-UNNAMED DosaKitchenServer.java

# OLD mode
java --add-opens java.base/java.lang=ALL-UNNAMED DosaKitchenServer.java --mode OLD
```

Send a request:

```bash
curl localhost:8081
```

Example response:

```
Sheldon Dosa House — ₹270
Mode     : NEW mode
Order    : 42
Thread   : chef before: chef-3 | chef after: chef-7 | rotated: true
Received : FULFILLED: chicken for order-42
```

## Reading the logs

**NEW mode** — each log line shows the virtual thread name and its current carrier:

```
[task=recipe-42 | handler=chef-3] #Order 42 received → calling cold store
[task=recipe-42 | handler=chef-7] #order 42 returned  → chef rotated: chef-3 → chef-7
```

`chef rotated` means the virtual thread unmounted during the cold store call and resumed on a different carrier — the
core of what virtual threads enable.

**OLD mode** — platform thread name only (carrier = thread, no rotation possible):

```
[chef-1              ] #42 received → calling cold store
[chef-1              ] #42 returned  → cooking on same thread
```

## Lifecycle summary

Press `Ctrl+C` to print a summary of how work was distributed across carriers:

```
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 Kitchen summary — NEW mode
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 Total orders  : 500
 Same chef     :  82  (16%)
 Rotated chef  : 418  (83%)

 Chef                                 touches
 ─────────────────────────────────────────────
 chef-1                               132
 chef-2                               129
 chef-3                               118
 ...
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
```

In OLD mode the same 16 chefs handle everything with equal touches. In NEW mode a small pool of carriers handles
thousands of concurrent recipes.

## Load test

Requires [k6](https://k6.io/docs/get-started/installation/).

```bash
k6 run load-test.js
```

The test ramps from 10 to 60 virtual users — well past OLD mode's 16-thread ceiling — and holds there for 50 seconds. At
60 VUs with 300 ms cold store latency: