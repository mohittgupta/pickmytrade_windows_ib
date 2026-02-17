# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

PickMyTrade IB App is a JavaFX desktop application (Windows-focused) that bridges the PickMyTrade trading platform with Interactive Brokers (IB) via the TWS API. It receives trade signals from the PickMyTrade backend (via Google Cloud Pub/Sub and a local HTTP server) and executes them as orders on IB TWS/Gateway.

## Build & Run

- **Java version**: 21+ (compiler targets Java 24)
- **Build system**: Maven
- **Build fat JAR**: `mvn clean package` (produces shaded JAR via maven-shade-plugin)
- **Run in dev**: `mvn javafx:run`
- **Main class**: `com.pickmytrade.ibapp.MainApp`
- **No test suite exists** in this project

## Architecture

### Entry Point & UI (`MainApp.java`)
The monolithic `MainApp` class (~1500+ lines) extends JavaFX `Application` and handles:
- Google OAuth login flow and token management
- TWS connection lifecycle (connect/disconnect/reconnect)
- Google Cloud Pub/Sub subscriber setup for receiving trade signals
- WebSocket client for heartbeat/status communication with PickMyTrade backend
- Auto-update mechanism (downloads new versions from `api.pickmytrade.io`)
- JavaFX UI: login dialog, connection settings, console log, TWS/server status indicators
- Windows taskbar integration via JNA

### Trade Execution Pipeline
1. **Signal ingestion**: Trade signals arrive via Pub/Sub (`MainApp` subscribes to a per-user subscription) or via local HTTP POST to `/place-trade` on `TradeServer`
2. **TradeServer**: Lightweight `com.sun.net.httpserver.HttpServer` that accepts JSON trade requests and delegates to `PlaceOrderService`
3. **PlaceOrderService**: Parses trade data (contract details, order details, account, quantities), constructs IB `Contract` and `Order` objects, handles entry orders with optional stop-loss/take-profit bracket orders, limit-to-market conversion timeouts, break-even logic, and reverse/close orders
4. **TwsEngine**: Wraps the IB `ApiController` — manages TWS connection, order placement/cancellation, position queries, open order retrieval, market data subscriptions, contract detail lookups, and option chain resolution. Processes order status updates via a blocking queue (`orderStatusQueue`)
5. **IBController**: Singleton holder for the shared `ApiController` instance

### Data Layer
- **Database**: SQLite via raw JDBC (no ORM at runtime despite Hibernate in pom.xml)
- **DatabaseConfig**: Static utility class with all SQL operations — table initialization, CRUD for tokens, connections, account data, order clients, and error logs
- **DB location**: `%APPDATA%/PickMYTrade/IB_{port}.db` (port-specific, e.g., `IB_7497.db`)
- **Key tables**: `tokens`, `connections`, `account_data`, `order_clients`, `error_log_data`
- **OrderClient entity**: Tracks full order lifecycle — entry/TP/SL IDs, statuses, filled prices, with `SentToServerStatus` enum (Initialized/Pushed/Failed)

### Configuration
- **Config.java**: Platform-aware log directory (`%APPDATA%/PickMYTrade` on Windows, `/var/log/PickMYTrade` on Linux), log level from `LOG_TYPE` env var (default: DEBUG)
- **LoggingConfig.java**: Programmatic Logback configuration with port-specific log files, rolling policy (5 files max, 2-day retention)
- **Default ports**: TWS port `7497`, trade server port `7507`

### External Services
- **PickMyTrade API**: `api.pickmytrade.io` — authentication, heartbeat, order status push, version checks
- **Google Cloud Pub/Sub**: Trade signal delivery (project: `pickmytrade`, subscription per user)
- **Google Cloud Secret Manager**: Retrieves Pub/Sub credentials
- **Google OAuth**: User authentication flow

## Key Patterns
- Thread pools are used extensively: 32-thread pools for trade processing, single-thread executors for WebSocket and order status processing
- Order status updates flow through a static `ArrayBlockingQueue` in `TwsEngine`, processed by a dedicated thread
- `PlaceOrderService.placeTrade()` returns `CompletableFuture<Boolean>` for async trade execution
- TWS connection uses `CountDownLatch` for synchronization during connect
- All database operations are static methods in `DatabaseConfig` using try-with-resources for connection management
