# Restaurant Order System

A multi-client, client–server restaurant order management system written in plain Java (sockets + threads, no external dependencies). Waiters take orders, kitchen workers prepare them, and a manager monitors the restaurant — all connected to one central server in real time.


## Features

- **Central TCP server** (port `5000`) handling many concurrent clients, one thread per connection.
- **Three roles** with role-based access control: Waiter, Kitchen, Manager.
- **Automatic order dispatch**: queued orders go to free kitchen workers (oldest first, one active order per worker).
- **Real-time notifications** pushed to clients (new orders, READY alerts, served orders, kitchen disconnects).
- **Fault tolerance**: if a kitchen worker disconnects, their in-progress orders are returned to the queue and re-dispatched.
- **Thread safety**: all shared order state is guarded by `synchronized` methods; dispatch and READY handling are serialized to avoid races.
- **Concurrency test suite** that fires simultaneous requests to verify correctness.

## Project Structure

```
src/
├── Server.java           # Socket server, session handling, command routing, dispatch
├── Client.java           # Interactive console client for all three roles
├── GuiClient.java        # Optional Swing GUI client for Waiter, Kitchen, Manager
├── Order.java            # Order model (id, food, waiter, status, kitchen)
├── OrderManager.java     # Thread-safe order store and business rules
└── ConcurrencyTest.java  # Automated concurrency tests (run against a live server)
```

## Order Lifecycle

```
QUEUED  ->  IN_PROGRESS  ->  READY  ->  SERVED
(waiter     (assigned to     (kitchen    (waiter
 creates)    kitchen)         finishes)   delivers)
```

## Requirements

- Java JDK 8 or newer

## Build

```bash
cd src
javac *.java
```

## Run

Start the server first (from `src/`):

```bash
java Server
```

### Option A: Console clients

Then open one terminal per user and run:

```bash
java Client
```

### Option B: Graphical client (Swing)

With the server running, open another terminal in `src/` and run:

```bash
java GuiClient
```

The terminal asks **How many users do you want to open?** Enter a number such as `5`. For each user, select Waiter, Kitchen, or Manager and enter the requested ID (`W1`, `W2`, `K1`, `K2`) and the server host (`localhost` if on the same computer). This opens separate GUI windows for the selected users. You can also open clients in separate runs of `java GuiClient`.

The GUI uses the same server and order commands as the console client. Both client interfaces can connect to the same server at the same time. Only one connected automatic kitchen worker can use a particular kitchen ID at once.

The GUI needs a desktop/graphical environment. The server itself can run without a desktop interface.

**Alternative: Compile and run from the project root** (without moving into `src/`):

```bash
javac src/*.java
java -cp src Server
java -cp src Client
java -cp src GuiClient
```

Run the server once, and run either client command in a separate terminal. The lines above show the available commands, not four programs that must all be launched in sequence.

Choose a role and ID when prompted:

| Role    | ID format         | Example |
|---------|-------------------|---------|
| Waiter  | `W` + number      | `W1`    |
| Kitchen | `K` + number      | `K1`    |
| Manager | none              |         |

A kitchen ID (e.g. `K1`) can only be connected once at a time. Waiter IDs identify who owns each order, so a waiter can only see and serve their own orders.

## Commands

Commands are case-insensitive. Type `MENU` or `HELP` in the client to see them again.

**Waiter**

| Command   | Description                         |
|-----------|-------------------------------------|
| `NEW`     | Create a new order                  |
| `LIST`    | View my active orders               |
| `STATUS`  | Check one of my orders              |
| `SERVE`   | Serve an order that is READY        |
| `HISTORY` | View my served orders               |

**Kitchen**

| Command    | Description                              |
|------------|------------------------------------------|
| `LIST`     | View all active orders                   |
| `ASSIGNED` | View the order assigned to me            |
| `DONE`     | Mark my assigned order READY             |
| `STATUS`   | Check any order's status                 |

Orders are assigned automatically; there is no need to pick one.

**Manager**

| Command   | Description                                  |
|-----------|----------------------------------------------|
| `LIST`    | Active orders grouped by waiter              |
| `HISTORY` | Served orders grouped by waiter              |
| `SUMMARY` | Counts of queued / in-progress / ready / served |
| `STATUS`  | Check any order's status                     |

All roles can `EXIT` to disconnect.

## Wire Protocol

Text-based, newline-delimited.

1. Server sends a welcome line on connect.
2. Client replies with `ROLE WAITER W1`, `ROLE KITCHEN K1`, or `ROLE MANAGER`.
3. Server answers `Role accepted: <title>` (or an error and closes).
4. Raw commands sent by the client's console: `ORDER <food>`, `STATUS <id>`, `READY <id>`, `SERVE <id>`, `LIST`, `HISTORY`, `SUMMARY`, `EXIT`.
5. Server messages are prefixed `RESPONSE|...` (reply to a command) or `EVENT|...` (asynchronous notification).

A legacy mode is supported for testing: `ROLE KITCHEN` (no ID) enables manual `NEXT` / `READY <id>` commands instead of automatic assignment.

## Running the Concurrency Tests

Start a **fresh** server (the tests expect an empty order list), then in another terminal:

```bash
cd src
java ConcurrencyTest
```

The tests check:

1. **Unique Order IDs** — simultaneous waiters never receive duplicate IDs.
2. **Kitchen Synchronization** — simultaneous kitchen requests get different orders.
3. **Duplicate Serving Prevention** — an order cannot be served twice, even by simultaneous requests.

A PASS/FAIL summary is printed at the end.

## Design Notes

- `OrderManager` methods are `synchronized`, making each operation atomic.
- `Server.dispatchOrders()` and the READY flow lock on `Server.class` so a kitchen disconnect cannot reassign an order mid-command.
- Client sessions are stored in a `CopyOnWriteArrayList` so notifications can iterate safely while clients connect/disconnect.
- Per-session `send` is synchronized so messages from different threads don't interleave.
- Order state is in-memory only; restarting the server clears all orders.

## Limitations

- The **console client** connects to `localhost:5000` (hard-coded). The GUI can connect to a specified server host on port 5000.
- Order records are kept **in server memory only**. Restarting or losing the central server stops all connected clients and loses the stored orders. No database or backup server is provided.
- Closed kitchen connections are detected and unfinished orders are requeued. The system does **not** detect a silently frozen worker if its socket remains open; there are no heartbeats.
- There is no employee authentication or encrypted transport. Roles and employee IDs are self-declared and suitable only for this course simulation.
- Automatic orders are prepared manually by kitchen workers (READY/DONE); cooking durations are not simulated automatically.
- The concurrency suite tests selected scenarios, not all possible failure cases or performance under heavy load.
