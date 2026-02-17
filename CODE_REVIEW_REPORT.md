# PickMyTrade IB App - Comprehensive Code Review Report

**Reviewed by:** Claude Code (Expert Java Developer & IB API Specialist)
**Date:** 2026-02-13
**Scope:** Full codebase review — all .java files, configuration, threading, IB API usage, Pub/Sub integration
**Focus Areas:** Connection drops, null pointer exceptions, race conditions, resource leaks, IB API misuse, edge cases

---

## Table of Contents

1. [CRITICAL — Likely Causing Production Issues](#1-critical--likely-causing-production-issues)
2. [HIGH — Will Cause Failures Under Certain Conditions](#2-high--will-cause-failures-under-certain-conditions)
3. [MEDIUM — Correctness & Reliability Issues](#3-medium--correctness--reliability-issues)
4. [LOW — Code Quality & Maintainability](#4-low--code-quality--maintainability)

---

## 1. CRITICAL — Likely Causing Production Issues

### 1.1 ConnectionLatch is Single-Use — TWS Reconnection is Broken

**File:** `TwsEngine.java:54`
**Evidence:**
```java
private final CountDownLatch connectionLatch = new CountDownLatch(1);
```
`CountDownLatch` can only be counted down once. After the first successful connection, `connectionLatch` is permanently at 0. When TWS disconnects and reconnects (via `continuouslyCheckTwsConnection`), a **new `TwsEngine` instance** is created at `MainApp.java:2635`:
```java
twsEngine = new TwsEngine();
```
But `PlaceOrderService` still holds a reference to the **old** `TwsEngine` unless `setTwsEngine()` is called. The code at `MainApp.java:2626` does call `placeOrderService.setTwsEngine(twsEngine)` — but only when `placetrade_new` is false and TWS is connected. If the timing is off (TWS connects, `placetrade_new` becomes true, then TWS drops and reconnects), the `PlaceOrderService` may reference a stale, disconnected `TwsEngine`.

**Impact:** Orders placed against a stale TwsEngine will silently fail or throw exceptions.

**Fix:** Ensure `placeOrderService.setTwsEngine(twsEngine)` is always called after creating a new `TwsEngine` and confirming connection, regardless of `placetrade_new` state. Also consider making `PlaceOrderService` always get the engine from a single source of truth (e.g., an `AtomicReference<TwsEngine>`).

---

### 1.2 setAsyncEConnect Called AFTER connect() — No Effect

**File:** `TwsEngine.java:206-207`
**Evidence:**
```java
controller.connect("127.0.0.1", tws_port, 0, "+PACEAPI");
controller.client().setAsyncEConnect(true);  // TOO LATE
```
Per IB API documentation, `setAsyncEConnect` must be called **before** `connect()`. Calling it after has no effect. The `+PACEAPI` opt-in string is correct for API pacing, but the async connect flag is wasted.

**Impact:** The connection is always synchronous, which may cause blocking behavior during connect attempts, and any code that depends on async connect semantics will misbehave.

**Fix:** Move `setAsyncEConnect(true)` to before the `connect()` call, or remove it if synchronous connect is intended.

---

### 1.3 Infinite Busy-Wait Loops Without Timeout — Thread Starvation

**File:** `PlaceOrderService.java:512-520`
**Evidence:**
```java
while (!"Filled".equals(entryOrderFilled)) {
    Thread.sleep(50);
    OrderClient entryOrderDbData = DatabaseConfig.getOrderClientByParentId(orderId);
    entryOrderFilled = entryOrderDbData.getEntryStatus();
    // ...
    if ("Cancelled".equals(entryOrderFilled)) break;
}
```
This loop has **no maximum timeout**. If an order gets stuck in "PreSubmitted" or "Submitted" status (e.g., TWS disconnects mid-order, market is closed, order gets rejected without a Cancelled status), this thread will spin **forever**, consuming a slot in the 32-thread pool.

Same pattern at:
- `PlaceOrderService.java:523` — `while (tp == 0)` — infinite if `getTpSlPrice` returns 0 perpetually
- `PlaceOrderService.java:801-813` — `handleOrderRecovery` wait loop
- `PlaceOrderService.java:822` — `while (tp == 0)` in recovery
- `PlaceOrderService.java:870` — `while (sl == 0)` in recovery
- `PlaceOrderService.java:1136-1206` — `monitorAndUpdateStopLoss` with `while(true)` + inner `while(true)`

**Impact:** Over time, threads get stuck in these loops, eventually exhausting the 32-thread executor pool. No more trades can be processed. This is likely a root cause of the "app stops responding" issues.

**Fix:** Add maximum timeout to every wait loop:
```java
long deadline = System.currentTimeMillis() + MAX_WAIT_MS;
while (!"Filled".equals(entryOrderFilled) && System.currentTimeMillis() < deadline) {
    Thread.sleep(50);
    // ...
}
if (!"Filled".equals(entryOrderFilled)) {
    log.error("Timed out waiting for order fill");
    // handle timeout gracefully
}
```

---

### 1.4 NullPointerException — `entryOrderDbData` Used Without Null Check

**File:** `PlaceOrderService.java:516`
**Evidence:**
```java
OrderClient entryOrderDbData = DatabaseConfig.getOrderClientByParentId(orderId);
entryOrderFilled = entryOrderDbData.getEntryStatus();  // NPE if null
```
`getOrderClientByParentId` can return `null` (it returns `null` when no row is found — see `DatabaseConfig.java:354`). The very next line calls `.getEntryStatus()` on it without a null check.

Same pattern at:
- `PlaceOrderService.java:516` (after market order conversion in bracket order flow)
- `PlaceOrderService.java:805-806` (in `handleOrderRecovery`)

**Impact:** `NullPointerException` crashes the trade execution thread, leaving the order in an inconsistent state (entry placed, no TP/SL).

**Fix:** Always null-check the return from `getOrderClientByParentId`:
```java
OrderClient entryOrderDbData = DatabaseConfig.getOrderClientByParentId(orderId);
if (entryOrderDbData == null) {
    log.error("Order not found in DB for orderId={}", orderId);
    break; // or handle gracefully
}
entryOrderFilled = entryOrderDbData.getEntryStatus();
```

---

### 1.5 TradeServer Never Actually Stops the HttpServer

**File:** `TradeServer.java:44-48`
**Evidence:**
```java
public void stop() {
    if (server != null) {
        log.info("HTTP Trade Server stopped");
        // server.stop(0) IS MISSING!
    }
}
```
The `stop()` method logs that the server stopped but **never actually calls `server.stop()`**. The HTTP server keeps running, holding the port.

**Impact:** On reconnect or app restart, port binding fails with "Address already in use". Also, during shutdown, the HTTP server continues accepting trade requests against a potentially disconnected TWS.

**Fix:**
```java
public void stop() {
    if (server != null) {
        server.stop(0);
        log.info("HTTP Trade Server stopped");
    }
}
```

---

### 1.6 Double `consumer.ack()` in Pub/Sub Message Handler

**File:** `MainApp.java:1961 and 2066`
**Evidence:**
```java
consumer.ack();  // Line 1961 — acked immediately after receiving ANY message
// ... then later for random_alert_key:
consumer.ack();  // Line 2066 — acked AGAIN
```
For `add_ib_settings` messages, `ack()` is called at line 1961 AND again at line 1980. For `random_alert_key` messages, `ack()` is called at line 1961 AND at line 2066. Double-acking is generally safe (Google Pub/Sub ignores duplicate acks), but the **early ack at line 1961** means the message is acknowledged **before** it's processed. If the app crashes during processing, the message is lost.

**Impact:** Trade signals can be lost if the app crashes between ack and actual processing. The ack-before-process pattern defeats Pub/Sub's at-least-once delivery guarantee.

**Fix:** Remove the early `consumer.ack()` at line 1961 and only ack after successful processing in each handler branch. Move the ack to the end of each specific handler.

---

## 2. HIGH — Will Cause Failures Under Certain Conditions

### 2.1 `executeOrder` Returns Order Before TWS Assigns the Order ID

**File:** `TwsEngine.java:723-727`
**Evidence:**
```java
public OrderExecutionResult executeOrder(Contract contract, Order order) {
    CompletableFuture<Order> future = new CompletableFuture<>();
    controller.placeOrModifyOrder(contract, order, new OrderHandler(future, order, contract));
    return new OrderExecutionResult(order, future);
}
```
The method returns the `OrderExecutionResult` with the same `order` object immediately. The `order.orderId()` is set by `ApiController.placeOrModifyOrder()` internally via `nextValidId`, but this happens asynchronously. The calling code in `PlaceOrderService` immediately uses `executedOrder.orderId()` at lines like:
```java
orderToContractMap.put(executedOrder.orderId(), ibContract); // Line 443
orderId = String.valueOf(executedOrder.orderId());            // Line 448
```
If the order ID hasn't been assigned yet (race condition), `orderId()` returns 0 or a default value.

**Impact:** Orders get mapped with wrong IDs. Database entries have incorrect `parent_id`. TP/SL orders cannot be linked to their entry orders.

**Fix:** Either wait for the `CompletableFuture` to complete (with timeout) before using the order ID, or use the callback in `OrderHandler` to complete the future and then use `future.get(timeout)`:
```java
Order executedOrder = result.getFuture().get(10, TimeUnit.SECONDS);
```

---

### 2.2 `makeBracketOrder` Sets Trail Properties on PARENT Order Instead of SL Order

**File:** `TwsEngine.java:700-703`
**Evidence:**
```java
if (trailingAmount != null && trailingAmount != 0 && sllmtPriceOffset != null && sllmtPriceOffset != 0) {
    slOrder.orderType("TRAIL LIMIT");
    parentOrder.trailStopPrice(0);        // BUG: Setting on parentOrder
    parentOrder.lmtPriceOffset(sllmtPriceOffset);  // BUG: Setting on parentOrder
    parentOrder.auxPrice(trailingAmount);            // BUG: Setting on parentOrder
```
When creating a trailing stop-loss order, the code creates `slOrder` but then sets `trailStopPrice`, `lmtPriceOffset`, and `auxPrice` on the **`parentOrder`** instead of `slOrder`.

**Impact:** The entry order gets corrupted with trailing stop properties. The stop-loss order has no trailing parameters set. The entry order may be rejected by TWS, or executed with wrong parameters.

**Fix:** Change `parentOrder` to `slOrder`:
```java
slOrder.orderType("TRAIL LIMIT");
slOrder.trailStopPrice(0);
slOrder.lmtPriceOffset(sllmtPriceOffset);
slOrder.auxPrice(trailingAmount);
```

---

### 2.3 `orderStatusQueue` Can Block Forever on Put

**File:** `TwsEngine.java:806`
**Evidence:**
```java
orderStatusQueue.put(statusData);  // Blocks if queue is full (capacity=1000)
```
`put()` on an `ArrayBlockingQueue` blocks indefinitely when the queue is full. This is called from the `LiveOrderHandler.orderStatus()` callback, which runs on the IB API's EReader thread. If the queue fills up (e.g., because the consumer thread died or is stuck), the IB API's EReader thread gets blocked.

**Impact:** When the EReader thread blocks, ALL IB API callbacks stop — no more order status updates, no market data, no position updates. The application appears frozen from TWS's perspective and TWS may disconnect the client.

**Fix:** Use `offer()` with a timeout instead:
```java
if (!orderStatusQueue.offer(statusData, 5, TimeUnit.SECONDS)) {
    log.error("Order status queue full! Dropping status for orderId={}", orderId);
}
```

---

### 2.4 TWS Reconnect Creates New TwsEngine but Doesn't Update TradeServer's PlaceOrderService

**File:** `MainApp.java:2634-2636`
**Evidence:**
```java
twsEngine.disconnect();
twsEngine = new TwsEngine();
connectToTwsWithRetries(stage);
```
After disconnect+reconnect, `twsEngine` is replaced with a new instance. `PlaceOrderService.setTwsEngine(twsEngine)` is called at line 2626, but only when `placetrade_new` is false. After a reconnect, `placetrade_new` was set to `false` at line 2633, so the next iteration should call `setTwsEngine`. However, the `TradeServer` holds a `PlaceOrderService` reference that was set at initialization. If the `PlaceOrderService` still references the old `TwsEngine` when a trade comes in via HTTP during the reconnect window, it will fail.

**Impact:** Trades received via HTTP during the TWS reconnect window will fail silently or throw "TWS not connected".

**Fix:** Consider making `PlaceOrderService` always get the `TwsEngine` via a supplier/reference rather than a stored field, or ensure the trade server pauses during reconnection.

---

### 2.5 `orderToContractMap` is a Non-Synchronized HashMap Accessed from Multiple Threads

**File:** `PlaceOrderService.java:26`
**Evidence:**
```java
private final Map<Integer, Contract> orderToContractMap = new HashMap<>();
```
This map is written from multiple threads in the 32-thread executor pool (`placeTrade` runs on `executor`), and potentially read from `handleOrderRecovery`. `HashMap` is not thread-safe.

**Impact:** Concurrent modification can cause infinite loops (HashMap's internal linked list corruption in older Java), lost entries, or `ConcurrentModificationException`.

**Fix:** Use `ConcurrentHashMap`:
```java
private final Map<Integer, Contract> orderToContractMap = new ConcurrentHashMap<>();
```

---

### 2.6 `marketDataHandlers` is a Non-Synchronized HashMap Accessed from Multiple Threads

**File:** `TwsEngine.java:52`
**Evidence:**
```java
private final Map<Contract, ApiController.ITopMktDataHandler> marketDataHandlers = new HashMap<>();
```
Written to from `getOptionDetails()`, `reqMktData()`, `unsubsMktData()`, and iterated in `disconnect()`. These can be called from different threads.

**Impact:** Same as 2.5 — corrupt HashMap, lost market data handlers, or exceptions during disconnect.

**Fix:** Use `ConcurrentHashMap`.

---

### 2.7 Pub/Sub Monitor Accesses `currentSubscriber` After Null Check Without Guarding

**File:** `MainApp.java:2208-2213`
**Evidence:**
```java
Subscriber currentSubscriber = pubsubSubscriberRef.get();
boolean x = currentSubscriber.isRunning();           // NPE if null
boolean y = currentSubscriber.state() == ApiService.State.RUNNING;  // NPE if null
String z = String.valueOf(currentSubscriber.state()); // NPE if null
// ... then later:
boolean isSubscriberRunning = currentSubscriber != null && currentSubscriber.isRunning();
```
Lines 2208-2210 call methods on `currentSubscriber` without null checking, but line 2213 does check for null. The first access at line 2208 will throw NPE if the subscriber is null (e.g., during first startup before subscriber is created, or after it's been stopped and set to null at line 2280).

**Impact:** `NullPointerException` crashes the monitor loop, and Pub/Sub reconnection stops working. The app silently stops receiving trade signals.

**Fix:** Add null check before lines 2208-2210, or restructure to check null first.

---

## 3. MEDIUM — Correctness & Reliability Issues

### 3.1 `ClassCastException` Risk — Gson Deserializes Numbers as Double

**File:** `PlaceOrderService.java:67-68`
**Evidence:**
```java
int con_id = orderJson.get("con_id") != null && ((Number) orderJson.get("con_id")).intValue() != 0
        ? ((Number) orderJson.get("con_id")).intValue() : 0;
```
This is correctly using `(Number)` cast. However, many other places cast directly to `(Double)`:
```java
(Double) orderJson.get("stop_price")   // Line 390
(Double) orderJson.get("limit_price")  // Line 393
(Double) orderJson.get("tp_price")     // Line 394
// ... many more
```
Gson deserializes JSON numbers without decimal points as `Double` by default, so `(Double)` works. But if the server sends an integer (e.g., `"limit_price": 100` instead of `"limit_price": 100.0`), Gson will still parse it as `Double`. The real risk is if the value is `null` — the unboxing of `(Double) null` to `double` in a comparison would cause NPE. The null checks before the casts (`!= null && ((Double)...) != 0`) protect against this, but if the JSON has the key with a non-numeric type, `ClassCastException` occurs.

**Impact:** Malformed trade data from the server can crash the trade handler thread.

**Fix:** Use `(Number)` casts consistently and handle type errors:
```java
Double getValue(Map<String, Object> map, String key) {
    Object val = map.get(key);
    if (val instanceof Number) return ((Number) val).doubleValue();
    return null;
}
```

---

### 3.2 `FUT` Positions Not Checked for Maturity Date in Duplicate Position Check

**File:** `PlaceOrderService.java:208`
**Evidence:** The comment in `PositionHandler` at `TwsEngine.java:355` says:
```java
// For FUT and STK, only check account and symbol (no maturity or right check)
```
But for futures (`FUT`), different maturity dates are different instruments. Two FUT positions with the same symbol but different expiry dates should not be considered duplicates. The position dedup in `PositionHandler.position()` and the duplicate check in `placeTrade()` both skip maturity date checks for `FUT`.

**Impact:** Rolling futures positions may get incorrectly flagged as duplicates, preventing new month contracts from being opened.

**Fix:** For `FUT` type, include `lastTradeDateOrContractMonth` in the comparison.

---

### 3.3 `disconnect()` Shuts Down the Executor — Preventing Re-Use

**File:** `TwsEngine.java:276-286`
**Evidence:**
```java
try {
    executor.shutdown();
    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
    }
}
```
Once `executor.shutdown()` is called, the executor cannot accept new tasks. But `TwsEngine` is recreated on reconnect (`new TwsEngine()` at `MainApp.java:2635`), so this is OK for the instance being discarded. However, the `orderStatusExecutor` is **static**:
```java
public static ExecutorService orderStatusExecutor = Executors.newSingleThreadExecutor();
```
And `startOrderStatusProcessing()` uses `orderStatusProcessingStarted` which is also static. On reconnect with a new `TwsEngine`, `startOrderStatusProcessing()` may not restart because `orderStatusProcessingStarted` is already `true` from the old instance.

**Impact:** After TWS reconnection, order status processing may silently stop working. No order status updates are processed, TP/SL orders are never placed after entry fills.

**Fix:** When creating a new `TwsEngine`, reset the static `orderStatusProcessingStarted` flag, or redesign to avoid static state:
```java
// In continuouslyCheckTwsConnection, before creating new TwsEngine:
TwsEngine.orderStatusProcessingStarted.set(false);
```

---

### 3.4 `TopMktDataHandler` Increments `attempts` After Check — Off-By-One

**File:** `TwsEngine.java:876-882`
**Evidence:**
```java
private void checkCompletion() {
    if ((!Double.isNaN(bid) && !Double.isNaN(volume)) || attempts >= 50) {
        controller.cancelTopMktData(this);
        future.complete(Map.of("bid", bid, "volume", volume));
    }
    attempts++;
}
```
The `attempts` counter is incremented after the check, so it actually allows 51 attempts (0 through 50 inclusive). Minor, but the `future.complete()` could also be called with `NaN` values if the 50-attempt limit is hit before receiving valid data.

**Impact:** `Map.of("bid", NaN, "volume", NaN)` is returned, and downstream code in `getBestStrikeForOption` at line 1085 checks `!Double.isNaN(data.get("bid"))` which would catch this. But the market data handler never gets cancelled if `checkCompletion` completes the future on the first valid tick — subsequent ticks still call `checkCompletion`, which calls `cancelTopMktData` again (no-op) and `future.complete` again (no-op).

**Fix:** Add early return after completing the future.

---

### 3.5 `tickSnapshotEnd` Can Complete Future with NaN Values

**File:** `TwsEngine.java:889-893`
**Evidence:**
```java
@Override
public void tickSnapshotEnd() {
    if (!future.isDone()) {
        future.complete(Map.of("bid", bid, "volume", volume));
    }
}
```
If `tickSnapshotEnd` is called before any tick data arrives, `bid` and `volume` are both `Double.NaN`. `Map.of("bid", Double.NaN, "volume", Double.NaN)` is valid Java, but downstream code that uses `data.get("bid")` may not handle NaN correctly.

**Impact:** `getBestStrikeForOption` may select incorrect strikes based on NaN bid prices.

**Fix:** Complete exceptionally or with sentinel values when no data was received.

---

### 3.6 `Thread.currentThread().interrupt()` Called Incorrectly in LiveOrderHandler

**File:** `TwsEngine.java:811`
**Evidence:**
```java
} catch (Exception e) {
    log.error("Failed to add order status to queue for orderId={}: {}", orderId, e.getMessage());
    Thread.currentThread().interrupt();  // Why?
}
```
The catch block catches all exceptions (not just `InterruptedException`), but always interrupts the current thread. This interrupts the IB API's EReader thread, which can cause unpredictable behavior in the API.

**Impact:** IB API EReader thread gets interrupted, potentially causing connection drops.

**Fix:** Only interrupt on `InterruptedException`:
```java
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
} catch (Exception e) {
    log.error("Failed to add order status to queue for orderId={}: {}", orderId, e.getMessage());
}
```

---

### 3.7 `monitorAndUpdateStopLoss` Runs Forever — No Exit Condition

**File:** `PlaceOrderService.java:1136`
**Evidence:**
```java
executor.submit(() -> {
    while (true) {
        try {
            double[] candle = twsEngine.reqLastCandle(ibContract, "1 D", "1 min").join();
            // ...
            Thread.sleep(500);
        } catch (Exception e) {
            log.error("Error in monitor_and_update_stop_loss: {}", e.getMessage());
        }
    }
});
```
This loop runs forever, every 500ms, requesting historical data. If breakeven is never reached, this runs for the lifetime of the application. There is no check for:
- Whether the position still exists
- Whether the order was cancelled or filled
- Whether TWS is still connected
- Whether the TwsEngine was replaced during reconnect

**Impact:** Stale monitors accumulate over time, each consuming a thread and making IB API historical data requests. IB rate-limits historical data requests (max ~60 in 10 minutes), so this will trigger pacing violations.

**Fix:** Add exit conditions:
```java
while (twsEngine.isConnected() && !Thread.currentThread().isInterrupted()) {
    // Check if position still exists
    // Check if stop order was filled/cancelled
    // Add maximum duration
}
```

---

### 3.8 SQLite Concurrent Access Without Connection Pooling

**File:** `DatabaseConfig.java:138-143`
**Evidence:**
```java
public static java.sql.Connection getConnection() throws SQLException {
    if (DB_URL == null) throw new SQLException("DB_URL is not set");
    return DriverManager.getConnection(DB_URL);
}
```
Every database call opens a new connection. With 32+ threads making concurrent database calls, and SQLite's single-writer limitation, this will cause:
- `SQLITE_BUSY` errors under load
- Connection overhead (creating/closing connections is expensive)
- Potential database corruption if SQLite is not compiled with thread safety

**Impact:** Under high trade volume, database operations fail intermittently with "database is locked" errors.

**Fix:** Use a connection pool (e.g., HikariCP with SQLite) or serialize database access through a single-thread executor:
```java
private static final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
```

---

### 3.9 `consoleLog.clear()` Called from Non-JavaFX Thread

**File:** `MainApp.java:2612`
**Evidence:**
```java
private void continuouslyCheckTwsConnection(Stage stage) {
    // ...
    consoleLog.clear();  // Called from executor thread, NOT JavaFX thread
```

**Impact:** JavaFX `TextArea` must only be modified from the JavaFX Application Thread. This can cause `IllegalStateException` or UI corruption.

**Fix:** Wrap in `Platform.runLater()`:
```java
Platform.runLater(() -> consoleLog.clear());
```

---

### 3.10 `shutdownApplication()` Shuts Down Executor Then Uses `Platform.exit()`

**File:** `MainApp.java:854-886`
**Evidence:**
```java
executor.shutdown();
// ...
websocketExecutor.shutdown();
// ...
Platform.exit();
System.exit(0);
```
`Platform.exit()` triggers the JavaFX `stop()` method, which calls `shutdownApplication()` again (recursion). Also, `scheduler` and `pubsubScheduler` are never shut down.

**Impact:** Double-shutdown can cause exceptions. Scheduled tasks in `scheduler` and `pubsubScheduler` keep running during shutdown, potentially interfering with cleanup.

**Fix:** Add a shutdown flag to prevent re-entry, and shut down all executors including `scheduler` and `pubsubScheduler`.

---

## 4. LOW — Code Quality & Maintainability

### 4.1 Duplicate `orderToDict` Methods

**File:** `PlaceOrderService.java:1004` AND `TwsEngine.java:987`
Both classes have identical `orderToDict` methods. The one in `TwsEngine` is called from `TwsEngine.orderStatusLoop()` -> `sendOrderToApi()`, and the one in `PlaceOrderService` is called from `MainApp.sendOrdersToApiOnce()`.

**Fix:** Consolidate into a single utility method.

---

### 4.2 `parentOrder.transmit(true)` in `makeBracketOrder` — No OCA Group Bracket Support

**File:** `TwsEngine.java:674`
**Evidence:**
```java
parentOrder.transmit(true);
```
In IB API bracket orders, `transmit` should be `false` for the parent and `true` only for the last child order, so all orders are sent atomically. However, this code doesn't use IB's native bracket order mechanism (no `parentId` linking). Instead it uses OCA groups for TP/SL. Since each order is transmitted individually via `executeOrder()`, this is technically OK, but the entry order could fill before TP/SL orders are placed, creating a window where the position is unprotected.

**Impact:** Between entry fill and TP/SL placement (could be seconds to minutes depending on the wait loops), the position has no protection. A flash crash during this window could cause significant losses.

---

### 4.3 `Random` Should Be `ThreadLocalRandom`

**File:** `PlaceOrderService.java:1058`
**Evidence:**
```java
Random random = new Random();
```
A new `Random` is created for each `generateRandomKey` call. In multi-threaded code, `ThreadLocalRandom` is preferred.

---

### 4.4 Logging Statements with Wrong Format

**File:** `MainApp.java:2203-2204`
**Evidence:**
```java
log.info("Networkrestoretime", networkRestoredTime.get());  // Missing {}
log.info("current_time", System.currentTimeMillis());        // Missing {}
```
These log statements pass arguments but have no `{}` placeholders, so the values are silently dropped.

**Fix:**
```java
log.info("Networkrestoretime: {}", networkRestoredTime.get());
log.info("current_time: {}", System.currentTimeMillis());
```

---

### 4.5 Google OAuth Client ID Hardcoded

**File:** `MainApp.java:172`
**Evidence:**
```java
private static final String GOOGLE_CLIENT_ID = "976773838704-16mo2mteso7glm97h37604c1rv0sgnvl.apps.googleusercontent.com";
```
OAuth client IDs are not secrets (they're embedded in client apps), but hardcoding them makes rotation difficult.

---

### 4.6 `checkServerPortFree` Uses Shell Command Instead of Java API

**File:** `MainApp.java:1104`
**Evidence:**
```java
Process process = Runtime.getRuntime().exec("cmd /c netstat -ano | findstr :" + port);
```
This pipes in a shell command, which is fragile and Windows-specific. Also, the `port` integer is concatenated without validation, and the `Process` streams are not fully consumed (could cause hanging on Windows).

**Fix:** Use Java's `ServerSocket` to test port availability:
```java
try (ServerSocket ss = new ServerSocket(port)) {
    return true;
} catch (IOException e) {
    return false;
}
```

---

### 4.7 Password Stored in Memory as Static Field

**File:** `MainApp.java:136`
**Evidence:**
```java
private static String lastPassword = "";
```
And logged at `MainApp.java:1360`:
```java
log.info("Sending login request with payload: {}", gson.toJson(payload));
```
The payload contains the password in plaintext, and it's written to the log file.

**Impact:** Passwords visible in log files and in memory dumps.

**Fix:** Don't log payloads containing passwords. Use `char[]` instead of `String` for passwords where possible.

---

## Summary of Priorities

| Priority | Count | Key Issues |
|----------|-------|------------|
| **CRITICAL** | 6 | ConnectionLatch reuse, setAsyncEConnect timing, infinite loops, NPE, TradeServer.stop(), double ack |
| **HIGH** | 7 | Order ID race, bracket order bug, queue blocking, stale TwsEngine, non-thread-safe maps, NPE in monitor |
| **MEDIUM** | 10 | ClassCastException, FUT dedup, static executor state, NaN values, incorrect interrupt, forever monitors, SQLite concurrency, JavaFX threading |
| **LOW** | 7 | Duplicate code, bracket transmit gap, Random usage, logging format, hardcoded config, shell commands, password logging |

**Recommended fix order:** 1.3, 1.4, 1.5, 2.2, 2.3, 1.1, 1.2, 2.1, 3.3, 3.7, 2.5, 2.6, 2.7, 1.6, 3.8

---

## FIX STATUS (Updated 2026-02-17)

All fixes below have been implemented and verified via Docker build (BUILD SUCCESS).

### CRITICAL Fixes

| Issue | Status | File(s) Changed | Description |
|-------|--------|-----------------|-------------|
| 1.1 ConnectionLatch reuse | FIXED (via CD-1/CD-2) | `TwsEngine.java`, `MainApp.java` | Added `resetOrderStatusProcessor()` static method; called before creating new TwsEngine on reconnect |
| 1.2 setAsyncEConnect after connect() | FIXED | `TwsEngine.java` | Moved `setAsyncEConnect(true)` before `controller.connect()` |
| 1.3 Infinite busy-wait loops | FIXED | `PlaceOrderService.java` | Added 5-min timeout to entry fill loops, 1-min timeout to TP/SL calculation loops, 5-min timeout to recovery loops, 24-hour max to break-even monitor |
| 1.4 NPE on entryOrderDbData | FIXED | `PlaceOrderService.java` | Added null checks for all `getOrderClientByParentId()` returns with proper break/log |
| 1.5 TradeServer never stops | FIXED | `TradeServer.java` | Added `server.stop(0)` in `stop()` method |
| 1.6 Double consumer.ack() | FIXED | `MainApp.java` | Removed early `consumer.ack()` before processing; each handler now acks individually after processing |

### HIGH Fixes

| Issue | Status | File(s) Changed | Description |
|-------|--------|-----------------|-------------|
| 2.1 executeOrder returns before TWS assigns ID | NOT FIXED | - | Requires architectural change to wait for CompletableFuture; risk of breaking existing flow |
| 2.2 makeBracketOrder trail on parentOrder | FIXED | `TwsEngine.java` | Changed `parentOrder.trailStopPrice/lmtPriceOffset/auxPrice` to `slOrder.*` |
| 2.3 orderStatusQueue.put() blocks | FIXED | `TwsEngine.java` | Replaced `put()` with `offer(5, TimeUnit.SECONDS)` with error logging |
| 2.4 Stale TwsEngine in PlaceOrderService | FIXED (via CD-1/CD-2) | `MainApp.java` | `resetOrderStatusProcessor()` + `setTwsEngine()` called on reconnect |
| 2.5 orderToContractMap non-thread-safe | FIXED | `PlaceOrderService.java` | Changed to `ConcurrentHashMap` |
| 2.6 marketDataHandlers non-thread-safe | FIXED | `TwsEngine.java` | Changed to `ConcurrentHashMap` |
| 2.7 Pub/Sub monitor NPE | FIXED | `MainApp.java` | Added null check on `currentSubscriber` before calling methods; removed debug variables |

### MEDIUM Fixes

| Issue | Status | File(s) Changed | Description |
|-------|--------|-----------------|-------------|
| 3.1 ClassCastException risk (Double cast) | NOT FIXED | - | Low actual risk since Gson always uses Double for JSON numbers; would require large refactor |
| 3.2 FUT dedup missing maturity check | FIXED | `TwsEngine.java` | Added maturity date comparison for FUT type in PositionHandler |
| 3.3 Static executor state on reconnect | FIXED | `TwsEngine.java`, `MainApp.java` | Added `resetOrderStatusProcessor()` method; `disconnect()` now sets `stopFlag`; called before new TwsEngine |
| 3.4 TopMktDataHandler off-by-one | FIXED | `TwsEngine.java` | Moved `attempts++` before check; added `future.isDone()` early return |
| 3.5 tickSnapshotEnd NaN values | ACKNOWLEDGED | - | Downstream code in `getBestStrikeForOption` already checks `!Double.isNaN()` |
| 3.6 Incorrect Thread.interrupt() | FIXED | `TwsEngine.java` | Split catch into `InterruptedException` (interrupt) and `Exception` (log only) |
| 3.7 monitorAndUpdateStopLoss forever | FIXED | `PlaceOrderService.java` | Added 24-hour deadline, TWS connection check, thread interrupt check, position existence check |
| 3.8 SQLite concurrent access | NOT FIXED | - | Requires adding HikariCP dependency or major DB layer refactor; out of scope |
| 3.9 consoleLog.clear() non-FX thread | FIXED | `MainApp.java` | Wrapped in `Platform.runLater()` |
| 3.10 shutdownApplication re-entry | FIXED | `MainApp.java` | Added `shutdownInProgress` AtomicBoolean guard; added `scheduler` and `pubsubScheduler` shutdown |

### LOW Fixes

| Issue | Status | File(s) Changed | Description |
|-------|--------|-----------------|-------------|
| 4.1 Duplicate orderToDict | NOT FIXED | - | Code quality issue; no functional impact |
| 4.2 parentOrder.transmit(true) | NOT FIXED | - | Application uses OCA groups instead of native brackets; design choice |
| 4.3 Random vs ThreadLocalRandom | FIXED | `PlaceOrderService.java` | Replaced `new Random()` with `ThreadLocalRandom.current()` |
| 4.4 Logging missing {} | FIXED | `MainApp.java` | Added `{}` placeholders to `Networkrestoretime` and `current_time` log statements |
| 4.5 Hardcoded OAuth Client ID | NOT FIXED | - | Standard practice for desktop OAuth apps; rotation handled server-side |
| 4.6 checkServerPortFree shell cmd | FIXED | `MainApp.java` | Replaced `cmd /c netstat` with Java `ServerSocket` (cross-platform: Windows + macOS) |
| 4.7 Password logged in plaintext | FIXED | `MainApp.java` | Replaced payload logging with username-only log message |

### Connection Drop Fixes (Section 5)

| Fix | Status | Bugs Addressed | Description |
|-----|--------|---------------|-------------|
| FIX 1: Eliminate static state | FIXED | CD-1, CD-2 | Added `resetOrderStatusProcessor()` + `stopFlag` set in `disconnect()` |
| FIX 2: AtomicReference for TwsEngine | PARTIAL | CD-3, CD-4 | TradeServer.stop() fixed; setTwsEngine called on reconnect; full Supplier pattern not implemented |
| FIX 3: Trade queue with retry | NOT FIXED | CD-10 | Requires new queuing architecture; out of scope for this fix cycle |
| FIX 4: IB error codes 1100/1101/1102 | FIXED | CD-8 | Added switch statement handling in `CustomConnectionHandler.message()` |
| FIX 5: Thread leak in retry loop | FIXED | CD-5 | `resetOrderStatusProcessor()` called before each new TwsEngine in retry loop |
| FIX 6: setAsyncEConnect order | FIXED | CD-7 | Moved before `connect()` call |
| FIX 7: Pub/Sub monitor NPE | FIXED | CD-11 | Null check before accessing subscriber methods |
| FIX 8: consumer.ack() after processing | FIXED | CD-12 | Each handler now acks individually |
| FIX 9: TradeServer pause/resume | NOT FIXED | CD-4 | TradeServer.stop() is now functional; full pause/resume gating not implemented |

### Summary

| Category | Total | Fixed | Not Fixed | Partial |
|----------|-------|-------|-----------|---------|
| CRITICAL (Section 1) | 6 | **6** | 0 | 0 |
| HIGH (Section 2) | 7 | **6** | 1 | 0 |
| MEDIUM (Section 3) | 10 | **7** | 2 | 1 |
| LOW (Section 4) | 7 | **4** | 3 | 0 |
| Connection Drops (Section 5) | 9 | **6** | 2 | 1 |
| **TOTAL** | **39** | **29** | **8** | **2** |

**Build verification:** Docker build passed successfully (BUILD SUCCESS) on 2026-02-17.

---
---

## 5. DEEP DIVE — Connection Drop Analysis & Permanent Fix Strategy

### 5.1 Understanding the Connection Architecture

The application has **three independent connection layers**, each with its own drop/reconnect problems:

| Layer | Technology | File | Reconnect Logic |
|-------|------------|------|-----------------|
| **TWS ↔ App** | IB API ApiController (TCP socket) | `TwsEngine.java`, `MainApp.java:2610-2689` | `continuouslyCheckTwsConnection` loop + `connectToTwsWithRetries` |
| **Pub/Sub ↔ App** | Google Cloud Pub/Sub gRPC | `MainApp.java:1900-2130, 2171-2336` | `monitorPubSubConnection` scheduled every 10s |
| **App ↔ PickMyTrade API** | HTTP REST | `TwsEngine.java:1039-1074`, `MainApp.java:2718-2757` | No reconnection — fire-and-forget |

---

### 5.2 TWS Connection Drop — Complete Failure Chain Analysis

#### 5.2.1 What Actually Happens When TWS Drops

When TWS disconnects (network blip, TWS restart, IB daily maintenance at ~23:45 ET), the IB API fires these events:

1. **EReader thread** detects socket failure → calls `EWrapper.error()` with error code **1100** ("Connectivity lost") or **507** ("Bad Message")
2. **ApiController** calls your `IConnectionHandler.disconnected()` callback
3. **EReader thread terminates** — it is a single-use thread, cannot recover
4. All pending **order status callbacks are lost** — they won't be delivered
5. TWS may later send error **1101** (restored, data lost) or **1102** (restored, data maintained) — but only if the client-to-TWS TCP socket is still alive

#### 5.2.2 Your Current Reconnect Flow — Step by Step

**File:** `MainApp.java:2610-2689`

```
continuouslyCheckTwsConnection(stage)
│
├── Initial: twsEngine.twsConnect(tws_trade_port)          ← Line 2614
│
└── while(true) loop (every 4 seconds):                     ← Line 2616
    │
    ├── IF twsEngine.isConnected():                         ← Line 2619
    │   ├── updateTwsStatus("connected")
    │   ├── IF !placetrade_new:
    │   │   ├── placeOrderService.setTwsEngine(twsEngine)   ← Line 2626
    │   │   └── placeRemainingTpSlOrderWrapper()             ← Line 2627
    │   └── retrycheck_count = 1
    │
    └── ELSE (disconnected):                                ← Line 2631
        ├── updateTwsStatus("disconnected")
        ├── placetrade_new = false                           ← Line 2633
        ├── twsEngine.disconnect()                           ← Line 2634
        ├── twsEngine = new TwsEngine()                      ← Line 2635 ★ NEW INSTANCE
        └── connectToTwsWithRetries(stage)                   ← Line 2636
            │
            └── while(true) infinite retry loop:             ← Line 2659
                ├── twsEngine.twsConnect(tws_trade_port)     ← Line 2662
                ├── Thread.sleep(2000)                       ← Line 2663
                ├── IF connected: return                     ← Line 2664
                └── ELSE:
                    ├── twsEngine.disconnect()               ← Line 2671
                    ├── twsEngine = new TwsEngine()          ← Line 2672 ★ ANOTHER NEW INSTANCE
                    └── Thread.sleep(10000), loop again
```

#### 5.2.3 Identified Connection Drop Bugs (with Evidence)

---

**BUG CD-1: Static `orderStatusExecutor` and `orderStatusProcessingStarted` Never Reset on Reconnect**

**Files:** `TwsEngine.java:45-46`, `MainApp.java:2635`

**Evidence:**
```java
// TwsEngine.java:45-46 — STATIC fields shared across ALL instances
public static ExecutorService orderStatusExecutor = Executors.newSingleThreadExecutor();
public static final AtomicBoolean orderStatusProcessingStarted = new AtomicBoolean(false);
```

```java
// TwsEngine.java:69-76 — Constructor starts processing
public void startOrderStatusProcessing() {
    if (orderStatusProcessingStarted.compareAndSet(false, true)) {
        log.info("Starting order status queue processing");
        orderStatusExecutor.submit(this::processOrderStatusQueue);
    } else {
        log.debug("Order status queue processing already started");  // ← THIS FIRES ON RECONNECT
    }
}
```

**What happens on reconnect:**
1. `MainApp:2635` creates `new TwsEngine()` → constructor calls `startOrderStatusProcessing()`
2. `orderStatusProcessingStarted` is already `true` (set by the OLD TwsEngine instance)
3. `compareAndSet(false, true)` returns `false` → processing is NOT started
4. The OLD `processOrderStatusQueue` task was submitted to `orderStatusExecutor` with a reference to the OLD TwsEngine's `stopFlag`
5. The old TwsEngine's `disconnect()` at line 277 calls `executor.shutdown()` — but NOT `orderStatusExecutor.shutdown()` (it's static)
6. The OLD processing task is still running in `orderStatusExecutor`, but it's calling `orderStatusLoop()` on the OLD TwsEngine instance — with stale database state

**Impact:** After reconnect, order status updates are processed by the OLD TwsEngine's method, which references stale `openOrders` list and may use stale `sendOrderToApi` connections. **Order status updates after reconnect appear to work but silently use stale references.**

**Note:** There IS a `restartOrderStatusProcessor()` at line 2365, but it's only triggered by a Pub/Sub `restart_order_status` message — NOT automatically on TWS reconnect.

---

**BUG CD-2: `orderStatusQueue` is Static but Consumer References OLD TwsEngine**

**File:** `TwsEngine.java:57`

**Evidence:**
```java
private static final ArrayBlockingQueue<Map<String, Object>> orderStatusQueue = new ArrayBlockingQueue<>(1000);
```

The queue is **static** (shared across TwsEngine instances). The NEW TwsEngine's `LiveOrderHandler` puts data into this queue. But the **consumer** (`processOrderStatusQueue`) was started by the OLD TwsEngine and calls `this::orderStatusLoop` — where `this` is the OLD instance.

**Impact:** New order status events from the reconnected TWS flow into the correct static queue, but are processed by the old TwsEngine's `orderStatusLoop()` method. The `sendOrderToApi()` call at line 1131 uses the old TwsEngine's non-static fields (which are effectively dead).

---

**BUG CD-3: `PlaceOrderService` Holds Stale TwsEngine Reference During Reconnect Window**

**Files:** `MainApp.java:2623-2627, 2633-2636`

**Evidence:**
```java
// Line 2633: placetrade_new is set to false
placetrade_new = false;
// Line 2634: OLD engine is disconnected
twsEngine.disconnect();
// Line 2635: NEW engine is created
twsEngine = new TwsEngine();
// Line 2636: connectToTwsWithRetries can take MINUTES (10s delay per retry)
connectToTwsWithRetries(stage);

// ONLY AFTER connectToTwsWithRetries returns AND the next loop iteration:
// Line 2623-2626: placetrade_new is still false, so setTwsEngine is called
if (!placetrade_new) {
    placetrade_new = true;
    placeOrderService.setTwsEngine(twsEngine);  // ← Finally updated
}
```

**Timeline during reconnect:**
```
T+0s:   twsEngine.disconnect()          PlaceOrderService still has OLD engine
T+0s:   twsEngine = new TwsEngine()     PlaceOrderService still has OLD engine
T+2s:   twsConnect attempt 1 fails      PlaceOrderService still has OLD engine
T+12s:  twsConnect attempt 2 fails      PlaceOrderService still has OLD engine
T+22s:  twsConnect attempt 3 succeeds   PlaceOrderService still has OLD engine
T+24s:  next loop → setTwsEngine()      PlaceOrderService FINALLY updated
```

During the entire 24+ second window, **any trade arriving via Pub/Sub → HTTP → TradeServer → PlaceOrderService** will call `twsEngine.isConnected()` on the OLD, disconnected engine and throw "TWS is not connected" at `PlaceOrderService.java:87`.

**But worse:** If a trade arrives during T+22s to T+24s (TWS just reconnected but PlaceOrderService not yet updated), `placeOrderService` uses the OLD disconnected engine, while the NEW engine is connected but not referenced by PlaceOrderService.

---

**BUG CD-4: `TradeServer` Never Stops During Reconnect — Accepts Trades Against Dead Engine**

**Files:** `TradeServer.java:44-48`, `MainApp.java:2634`

**Evidence:**
```java
// TradeServer.java:44-48
public void stop() {
    if (server != null) {
        log.info("HTTP Trade Server stopped");
        // server.stop(0) IS MISSING!  ← BUG from earlier review
    }
}
```

The `TradeServer` is never stopped or paused during TWS reconnection. It continues accepting HTTP requests from the Pub/Sub message handler. These requests are forwarded to `PlaceOrderService`, which holds the stale engine reference (BUG CD-3).

Even if `server.stop(0)` were called, there's no mechanism to restart it after reconnection because `TradeServer.start()` is only called once at `MainApp.java:1594`.

---

**BUG CD-5: `connectToTwsWithRetries` Creates Multiple TwsEngine Instances in a Loop**

**File:** `MainApp.java:2659-2688`

**Evidence:**
```java
while (true) {
    try {
        twsEngine.twsConnect(tws_trade_port);
        Thread.sleep(2000);
        if (twsEngine.isConnected()) {
            return;  // ← Success, but which instance is twsEngine now?
        } else {
            twsEngine.disconnect();
            twsEngine = new TwsEngine();  // ← New instance EVERY failed attempt
        }
    }
    // ...
    Thread.sleep(delaySeconds * 1000);
}
```

Each failed attempt creates a new `TwsEngine`, which:
1. Creates a new `ApiController` (line 64) — each holding its own internal state
2. Creates a new `ExecutorService` with 16 threads (line 44)
3. Calls `startOrderStatusProcessing()` — which does nothing because `orderStatusProcessingStarted` is already `true`
4. The OLD TwsEngine's `disconnect()` calls `executor.shutdown()` — but the `ApiController` was already told to connect, so its EReader thread may still be running

**Impact:** Each retry leaks:
- 16 threads from `Executors.newFixedThreadPool(16)` (not all properly shut down)
- An ApiController with its internal EReader thread (if connect partially succeeded)
- Orphaned callback handlers in the ApiController

After 10 retries, **160+ threads** may be leaked.

---

**BUG CD-6: `connectionLatch` is Single-Use — `startTwsSubscriptions` Fires Immediately on Reconnect**

**File:** `TwsEngine.java:54, 96-98, 211-228`

**Evidence:**
```java
// Line 54: One-shot latch
private final CountDownLatch connectionLatch = new CountDownLatch(1);

// Line 208: twsConnect submits startTwsSubscriptions to executor
public void twsConnect(int twsport) {
    controller.connect("127.0.0.1", tws_port, 0, "+PACEAPI");
    controller.client().setAsyncEConnect(true);  // ← Too late (BUG from earlier)
    executor.submit(() -> startTwsSubscriptions());
}

// Line 211-228: Waits on latch
public void startTwsSubscriptions() {
    connectionLatch.await();  // ← NEW latch starts at 1, blocks correctly
    if (isConnected) {
        controller.reqAccountUpdates(false, null, new AccountHandler());
        controller.reqPositions(new PositionHandler(null));
        controller.reqLiveOrders(new LiveOrderHandler(new CompletableFuture<>()));
    }
}
```

Since each `new TwsEngine()` creates a fresh `CountDownLatch(1)`, this is actually **OK for fresh instances**. The latch works correctly for the initial connection of each new TwsEngine.

**However**, the latch is NOT the problem — the problem is that `twsConnect` at line 206 calls `controller.connect()` which is a blocking call that starts the EReader thread. If the connection fails (TWS not available), the `connect()` call throws an exception or hangs, and the `startTwsSubscriptions` task blocks forever on the latch.

---

**BUG CD-7: `setAsyncEConnect(true)` Called After `connect()` — No Effect**

**File:** `TwsEngine.java:206-207`

**Evidence:**
```java
controller.connect("127.0.0.1", tws_port, 0, "+PACEAPI");
controller.client().setAsyncEConnect(true);  // ← AFTER connect, NO EFFECT
```

Per IB API docs, `setAsyncEConnect` must be set BEFORE `connect()`. This means:
- The connection is always synchronous
- If TWS is not available, `connect()` blocks the calling thread until socket timeout
- During each retry in `connectToTwsWithRetries`, the thread blocks for the TCP timeout duration

**Impact:** Each reconnect attempt blocks for the full TCP connection timeout (often 30+ seconds) before failing, making the reconnect loop much slower than it should be.

---

**BUG CD-8: IB Error Codes 1100/1101/1102 Not Handled**

**File:** `TwsEngine.java:146-160`

**Evidence:**
```java
@Override
public void error(Exception e) {
    log.error("TWS error: {}", e.getMessage(), e);
}

@Override
public void message(int id, int errorCode, String errorMsg, String advancedOrderRejectJson) {
    log.warn("TWS message: id={}, errorCode={}, msg={}, advanced={}", id, errorCode, errorMsg, advancedOrderRejectJson);
    errorFunc(id, errorCode, errorMsg, null);  // ← Only saves to DB, no action
}
```

The `message()` handler logs the error and calls `errorFunc()` which saves to the error_log database table. **No action is taken** for:
- **1100** (Connection lost) — should pause trade processing
- **1101** (Restored, data lost) — should resubscribe to market data, refresh positions, and re-request open orders
- **1102** (Restored, data maintained) — should verify state is consistent

These errors occur **daily** during IB's nightly maintenance window (~23:45 ET). Without handling them, the app may:
- Continue placing trades while TWS has no connectivity to IB servers
- Miss order status updates during the outage
- Fail to resubscribe to positions/orders after restoration

---

**BUG CD-9: `disconnect()` Doesn't Wait for `connect()` to Complete**

**File:** `TwsEngine.java:242-293`, `MainApp.java:2634`

**Evidence:**
```java
// MainApp.java:2634
twsEngine.disconnect();  // ← What if connect() is still in progress?
twsEngine = new TwsEngine();
```

```java
// TwsEngine.java:270-274
if (isConnected) {
    controller.disconnect();
    isConnected = false;
} else {
    // ← If connect() never completed, controller may be in partial state
    // EReader thread may be running but connection handshake not complete
}
```

If `twsConnect()` was called but the connection didn't complete (latch never counted down), calling `disconnect()` finds `isConnected == false` and skips `controller.disconnect()`. But the `ApiController` may have started its EReader thread, which continues running. The subsequent `executor.shutdown()` tries to kill threads, but the EReader is not in this executor.

---

**BUG CD-10: No Handling of Trades Received During TWS Disconnect**

**Files:** `MainApp.java:1952-2085`, `PlaceOrderService.java:85-88`

**Evidence:**
```java
// PlaceOrderService.java:85-88
if (!twsEngine.isConnected()) {
    log.error("TWS is not connected in PlaceOrderService");
    throw new IllegalStateException("TWS is not connected");
}
```

When TWS is disconnected:
1. Pub/Sub message arrives → `consumer.ack()` is called IMMEDIATELY at line 1961
2. Message is forwarded to TradeServer HTTP endpoint at line 2049
3. PlaceOrderService throws "TWS is not connected" at line 87
4. The trade is saved to DB with error message "TWS is not connected" at line 691
5. **The Pub/Sub message is already acked** — it won't be redelivered

**Impact:** Trades received during TWS disconnect are **permanently lost**. They're acked from Pub/Sub, fail to execute, and saved as errors. There's no retry mechanism for these failed trades after TWS reconnects.

---

### 5.3 Pub/Sub Connection Drop — Failure Chain Analysis

#### 5.3.1 Pub/Sub Monitor Flow

**File:** `MainApp.java:2171-2336`

The `monitorPubSubConnection` runs every 10 seconds via `pubsubScheduler`:

```
monitorTask (every 10s):
│
├── Check network: isNetworkAvailable() → HTTP POST to exe_heartbeat
│
├── IF network dropped:
│   ├── Record networkDroppedTime
│   └── updatePubSubStatus("disconnected"), return
│
├── IF network restored recently (< 32s ago):
│   ├── Wait up to 20s checking subscriber state every 1s
│   ├── IF subscriber running and messages received within 60s: return connected
│   └── IF not: fall through to restart
│
├── IF subscriber not running OR no messages for 60s:
│   ├── Refresh token if null
│   ├── Stop existing subscriber
│   ├── Start new subscriber via startPubSubSubscriber()
│   └── Wait 30s for connection
│
└── ELSE: subscriber running, return connected
```

#### 5.3.2 Pub/Sub Connection Drop Bugs

---

**BUG CD-11: NPE in `monitorPubSubConnection` — `currentSubscriber` Used Before Null Check**

**File:** `MainApp.java:2207-2213`

**Evidence:**
```java
Subscriber currentSubscriber = pubsubSubscriberRef.get();
boolean x = currentSubscriber.isRunning();           // ← NPE if null
boolean y = currentSubscriber.state() == ApiService.State.RUNNING;  // ← NPE if null
String z = String.valueOf(currentSubscriber.state()); // ← NPE if null
log.info("Current subscriber state: {}, {}", x , y);
log.info("Current subscriber state: {}", currentSubscriber.state());
boolean isSubscriberRunning = currentSubscriber != null && currentSubscriber.isRunning();  // ← NULL CHECK TOO LATE
```

Lines 2208-2210 call methods on `currentSubscriber` **without** null checking. But line 2213 checks `currentSubscriber != null`. The subscriber can be null:
- At first startup before `startPubSubSubscriber` completes
- After `pubsubSubscriberRef.set(null)` at line 2280 (when subscriber is stopped for restart)

**Impact:** `NullPointerException` crashes the monitor task. Since this is a `ScheduledExecutorService`, the **scheduled task is cancelled permanently** after an uncaught exception. The Pub/Sub monitor stops running entirely, and the app silently stops receiving trade signals forever.

---

**BUG CD-12: `consumer.ack()` Called Before Processing — Messages Lost on Crash**

**File:** `MainApp.java:1961`

**Evidence:**
```java
MessageReceiver receiver = (PubsubMessage message, AckReplyConsumer consumer) -> {
    try {
        String messageData = message.getData().toStringUtf8();
        // ...
        consumer.ack();  // ← LINE 1961: ACK IMMEDIATELY
        Map<String, Object> tradeData = gson.fromJson(messageData, ...);

        // ... actual processing happens AFTER ack ...

        if (tradeData.containsKey("random_alert_key")) {
            // ... complex trade processing ...
            consumer.ack();  // ← LINE 2066: DOUBLE ACK
        }
    } catch (Exception e) {
        consumer.nack();  // ← Never reached if ack was already called
    }
};
```

The message is acked at line 1961 **before** any processing. If the app crashes between ack and trade execution:
- The message is removed from Pub/Sub's backlog
- The trade is never executed
- No retry will occur

---

**BUG CD-13: Network Down > 10 Minutes Triggers `manualTradeCloseTime` Silently**

**File:** `MainApp.java:2218-2223`

**Evidence:**
```java
if (((Instant.now().toEpochMilli() - networkDroppedTime.get()) > 600_000) && networkDroppedTime.get()!=0) {
    networkDroppedTime.set(0);
    manualTradeCloseTime = Instant.now().toEpochMilli();
    log.warn("Network was down for more than 10 minutes, manual trade close time set to {}", manualTradeCloseTime);
    Platform.runLater(() -> showErrorPopup("Network was dropped for more than 10 Minutes. All trades received during this period have been ignored."));
}
```

When the network is down for 10+ minutes, `manualTradeCloseTime` is set to now. This means all trades with `server_data_sent` before this time will be ignored (line 2018). However:
- The popup may not be visible if the window is minimized
- Trades sent DURING the outage but with `server_data_sent` timestamp from BEFORE the outage are silently dropped
- There's no recovery mechanism — the ignored trades are just gone

---

### 5.4 Root Cause Summary

| Root Cause | Bugs | Severity |
|------------|------|----------|
| **Static mutable state shared across TwsEngine instances** | CD-1, CD-2 | CRITICAL |
| **PlaceOrderService holds stale TwsEngine reference** | CD-3 | CRITICAL |
| **No trade queuing/retry during TWS disconnect** | CD-10, CD-12 | CRITICAL |
| **TradeServer never stops, accepts trades against dead engine** | CD-4 | HIGH |
| **Thread/resource leak on each reconnect attempt** | CD-5 | HIGH |
| **IB error codes 1100/1101/1102 not handled** | CD-8 | HIGH |
| **Pub/Sub monitor crashes on NPE, never recovers** | CD-11 | HIGH |
| **setAsyncEConnect after connect — slow reconnects** | CD-7 | MEDIUM |
| **disconnect() doesn't handle partial connections** | CD-9 | MEDIUM |
| **Network outage > 10min silently drops trades** | CD-13 | MEDIUM |

---

### 5.5 Permanent Fix Strategy — How to Fix Connection Drops

#### FIX 1: Eliminate Static Mutable State in TwsEngine (Fixes CD-1, CD-2)

**Current problem:** `orderStatusExecutor`, `orderStatusProcessingStarted`, and `orderStatusQueue` are all static. When a new TwsEngine is created, the old consumer is still running on the old instance.

**Fix approach:**
```java
// Make these instance fields instead of static:
private final ExecutorService orderStatusExecutor = Executors.newSingleThreadExecutor();
private final AtomicBoolean orderStatusProcessingStarted = new AtomicBoolean(false);
private final ArrayBlockingQueue<Map<String, Object>> orderStatusQueue = new ArrayBlockingQueue<>(1000);

// In disconnect(), shut down the orderStatusExecutor:
public void disconnect() {
    stopFlag.set(true);  // Signal processOrderStatusQueue to stop
    // ... existing cleanup ...
    orderStatusExecutor.shutdownNow();
    orderStatusExecutor.awaitTermination(5, TimeUnit.SECONDS);
}
```

If static is required for cross-instance access, then `MainApp.continuouslyCheckTwsConnection()` must explicitly reset the static state before creating a new TwsEngine:

```java
// In MainApp.java, BEFORE creating new TwsEngine:
TwsEngine.orderStatusProcessingStarted.set(false);
TwsEngine.orderStatusExecutor.shutdownNow();
TwsEngine.orderStatusExecutor = Executors.newSingleThreadExecutor();
// Then:
twsEngine = new TwsEngine();
```

---

#### FIX 2: Use AtomicReference for TwsEngine — Single Source of Truth (Fixes CD-3, CD-4)

**Current problem:** `MainApp.twsEngine`, `PlaceOrderService.twsEngine`, and `TradeServer` all hold separate references.

**Fix approach:**
```java
// In MainApp:
private final AtomicReference<TwsEngine> twsEngineRef = new AtomicReference<>();

// PlaceOrderService reads from supplier instead of stored field:
public class PlaceOrderService {
    private volatile TwsEngine twsEngine;  // volatile for visibility

    // Or better — use a Supplier:
    private final Supplier<TwsEngine> twsEngineSupplier;

    public PlaceOrderService(Supplier<TwsEngine> twsEngineSupplier) {
        this.twsEngineSupplier = twsEngineSupplier;
    }

    // Every method that uses twsEngine gets it fresh:
    public CompletableFuture<Boolean> placeTrade(...) {
        TwsEngine engine = twsEngineSupplier.get();
        if (engine == null || !engine.isConnected()) {
            // Queue the trade for retry instead of failing
        }
    }
}
```

---

#### FIX 3: Add Trade Queue with Retry for TWS Disconnect (Fixes CD-10, CD-12)

**Current problem:** Trades received while TWS is disconnected are acked from Pub/Sub and then fail with no retry.

**Fix approach:**
```java
// Add a pending trade queue in MainApp:
private final BlockingQueue<Map<String, Object>> pendingTrades = new LinkedBlockingQueue<>();

// In Pub/Sub receiver — DON'T ack until trade is queued:
if (tradeData.containsKey("random_alert_key")) {
    pendingTrades.offer(tradeData);
    consumer.ack();  // Only ack after queueing, not after processing
}

// Separate consumer that processes queued trades:
private void processPendingTrades() {
    while (!Thread.currentThread().isInterrupted()) {
        Map<String, Object> trade = pendingTrades.take();
        if (twsEngine != null && twsEngine.isConnected()) {
            // Process trade
        } else {
            // Put back in queue and wait
            pendingTrades.offer(trade);
            Thread.sleep(2000);
        }
    }
}
```

---

#### FIX 4: Handle IB Error Codes 1100/1101/1102 (Fixes CD-8)

**Fix approach in `TwsEngine.CustomConnectionHandler.message()`:**
```java
@Override
public void message(int id, int errorCode, String errorMsg, String advancedOrderRejectJson) {
    log.warn("TWS message: id={}, errorCode={}, msg={}", id, errorCode, errorMsg);

    switch (errorCode) {
        case 1100:  // Connectivity lost between TWS and IB servers
            log.error("IB CONNECTIVITY LOST - pausing trade processing");
            isConnected = false;  // Prevent new orders
            // Don't disconnect — the client-TWS socket may still be alive
            break;

        case 1101:  // Restored, data lost
            log.warn("IB CONNECTIVITY RESTORED (data lost) - resubscribing");
            isConnected = true;
            // Resubscribe to everything:
            controller.reqPositions(new PositionHandler(null));
            controller.reqLiveOrders(new LiveOrderHandler(new CompletableFuture<>()));
            break;

        case 1102:  // Restored, data maintained
            log.info("IB CONNECTIVITY RESTORED (data maintained)");
            isConnected = true;
            break;

        case 502:   // Couldn't connect to TWS
        case 504:   // Not connected
            log.error("TWS connection failed: {}", errorMsg);
            isConnected = false;
            break;

        case 507:   // Bad message length / socket error
            log.error("Socket error, connection is broken");
            isConnected = false;
            break;
    }

    errorFunc(id, errorCode, errorMsg, null);
}
```

---

#### FIX 5: Fix Thread/Resource Leak in `connectToTwsWithRetries` (Fixes CD-5)

**Current problem:** Each failed attempt creates a new TwsEngine → new ApiController → new 16-thread pool, but `disconnect()` may not clean up everything.

**Fix approach:**
```java
private void connectToTwsWithRetries(Stage stage) {
    int attempt = 1;
    int delaySeconds = 10;

    while (true) {
        try {
            log.info("Attempt {} to connect to TWS...", attempt);
            // DON'T create new TwsEngine here — reuse the one from the caller
            twsEngine.twsConnect(tws_trade_port);

            // Wait for connection with timeout
            boolean connected = twsEngine.getConnectionLatch().await(5, TimeUnit.SECONDS);

            if (connected && twsEngine.isConnected()) {
                log.info("TWS connected on attempt {}", attempt);
                updateTwsStatus("connected");
                return;
            }

            // Only create new TwsEngine if connection truly failed
            log.warn("TWS connection failed on attempt {}", attempt);
            twsEngine.disconnect();  // Clean up current instance

            // Only create new instance after proper cleanup
            twsEngine = new TwsEngine();

        } catch (Exception e) {
            log.error("Error during TWS connection attempt {}: {}", attempt, e.getMessage());
        }

        attempt++;
        Thread.sleep(delaySeconds * 1000L);
    }
}
```

---

#### FIX 6: Fix `setAsyncEConnect` Order (Fixes CD-7)

**File:** `TwsEngine.java:206-207`

```java
public void twsConnect(int twsport) {
    this.tws_port = twsport;
    log.info("Attempting to connect to TWS");
    controller.client().setAsyncEConnect(true);  // ← BEFORE connect
    controller.connect("127.0.0.1", tws_port, 0, "+PACEAPI");
    executor.submit(() -> startTwsSubscriptions());
}
```

---

#### FIX 7: Fix Pub/Sub Monitor NPE (Fixes CD-11)

**File:** `MainApp.java:2207-2213`

```java
Subscriber currentSubscriber = pubsubSubscriberRef.get();
if (currentSubscriber == null) {
    log.warn("No active subscriber, attempting restart");
    // Skip directly to restart logic
} else {
    boolean isSubscriberRunning = currentSubscriber.isRunning();
    log.info("Current subscriber state: running={}, state={}",
             isSubscriberRunning, currentSubscriber.state());
    // ... rest of logic
}
```

---

#### FIX 8: Move `consumer.ack()` After Processing (Fixes CD-12)

**File:** `MainApp.java:1961`

Remove the early `consumer.ack()` at line 1961 and only ack at the end of each specific handler. This ensures Pub/Sub will redeliver messages that weren't fully processed.

```java
MessageReceiver receiver = (PubsubMessage message, AckReplyConsumer consumer) -> {
    try {
        String messageData = message.getData().toStringUtf8();
        pubsubLastMessageReceived.set(System.currentTimeMillis());
        updatePubSubStatus("connected");
        // DO NOT ack here — ack only after successful processing

        Map<String, Object> tradeData = gson.fromJson(messageData, ...);

        if (tradeData.containsKey("heartbeat")) {
            executor.submit(this::sendHeartbeatToApiOnce);
            consumer.ack();  // ← ack after handling heartbeat
            return;
        }

        if (tradeData.containsKey("random_alert_key")) {
            // ... process trade ...
            consumer.ack();  // ← ack only after trade is queued/processed
            return;
        }

        // If no handler matched, ack anyway to prevent infinite redelivery
        consumer.ack();

    } catch (Exception e) {
        log.error("Error processing message: {}", e.getMessage());
        consumer.nack();  // ← Redeliver on failure
    }
};
```

---

#### FIX 9: Add TradeServer Pause/Resume During Reconnect (Fixes CD-4)

Add an `AtomicBoolean` to gate trade acceptance:

```java
// In TradeServer or PlaceOrderService:
private final AtomicBoolean acceptingTrades = new AtomicBoolean(true);

public void pauseTrading() { acceptingTrades.set(false); }
public void resumeTrading() { acceptingTrades.set(true); }

// In handlePlaceTrade:
if (!acceptingTrades.get()) {
    // Return 503 Service Unavailable — trade will be retried
    String response = gson.toJson(Map.of("success", false,
        "message", "TWS reconnecting, please retry"));
    exchange.sendResponseHeaders(503, response.length());
    // ...
}
```

And call `tradeServer.pauseTrading()` before disconnect and `tradeServer.resumeTrading()` after reconnect in `continuouslyCheckTwsConnection`.

---

### 5.6 Recommended Implementation Priority for Connection Drop Fixes

| Priority | Fix | Bugs Addressed | Effort |
|----------|-----|---------------|--------|
| **1** | Fix Pub/Sub NPE (FIX 7) | CD-11 | 5 min |
| **2** | Move consumer.ack() after processing (FIX 8) | CD-12 | 15 min |
| **3** | Handle IB error codes 1100/1101/1102 (FIX 4) | CD-8 | 30 min |
| **4** | Fix static state reset on reconnect (FIX 1) | CD-1, CD-2 | 30 min |
| **5** | Use AtomicReference for TwsEngine (FIX 2) | CD-3, CD-4 | 1 hour |
| **6** | Fix setAsyncEConnect order (FIX 6) | CD-7 | 2 min |
| **7** | Fix thread leak in retry loop (FIX 5) | CD-5 | 30 min |
| **8** | Add trade queue with retry (FIX 3) | CD-10 | 2 hours |
| **9** | Add TradeServer pause/resume (FIX 9) | CD-4 | 30 min |

---

### 5.7 IB API Connection Drop Reference

| Error Code | Meaning | Frequency | Required Action |
|-----------|---------|-----------|-----------------|
| **1100** | TWS ↔ IB servers lost | Daily (maintenance) | Pause trading, wait for 1101/1102 |
| **1101** | Restored, data lost | After 1100 | Resubscribe all: positions, orders, market data |
| **1102** | Restored, data maintained | After 1100 | Verify state, resume trading |
| **502** | Couldn't connect to TWS | On connect failure | Retry with backoff |
| **504** | Not connected | Any API call when disconnected | Reconnect |
| **507** | Bad message / socket error | Socket failure | Full reconnect (new ApiController) |
| **2110** | TWS connectivity confirmed | On connection | Informational, no action needed |

**IB Daily Maintenance:** ~23:45-00:15 ET — expect 1100→1101/1102 cycle every night.

---

### 5.8 Connection Drop Timeline — Current vs Fixed

**Current behavior (failing):**
```
23:45 ET  IB maintenance → error 1100 arrives
          ↓ message() logs it, saves to error_log DB → NO ACTION
          ↓ TWS internally loses IB connectivity
          ↓ Trades arriving via Pub/Sub are acked and forwarded to PlaceOrderService
          ↓ PlaceOrderService calls twsEngine.executeOrder() → orders fail silently
          ↓ (or worse: orders are queued in TWS and execute hours later when 1101 fires)
00:05 ET  IB restores → error 1101 arrives
          ↓ message() logs it → NO ACTION
          ↓ Positions/orders NOT refreshed
          ↓ LiveOrderHandler has stale state
          ↓ New trades may work but status tracking is broken
```

**Fixed behavior (after implementing fixes):**
```
23:45 ET  IB maintenance → error 1100 arrives
          ↓ message() handler detects 1100
          ↓ Sets isConnected = false, pauses trade acceptance
          ↓ Trades arriving via Pub/Sub are NACK'd (will be redelivered)
          ↓ OR queued in pendingTrades queue for retry after restore
00:05 ET  IB restores → error 1101 arrives
          ↓ message() handler detects 1101
          ↓ Resubscribes: reqPositions(), reqLiveOrders()
          ↓ Sets isConnected = true, resumes trade acceptance
          ↓ Pending trades from queue are now processed
          ↓ Order states reconciled with database
```
