import java.io.*;
import java.net.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class Server {
    private static final OrderManager orderManager = new OrderManager();
    private static final CopyOnWriteArrayList<ClientSession> clients = new CopyOnWriteArrayList<>();

    private static class ClientSession {
        final String role;
        final String waiterId;
        final String kitchenId;
        final PrintWriter output;

        ClientSession(String role, String waiterId, String kitchenId, PrintWriter output) {
            this.role = role;
            this.waiterId = waiterId;
            this.kitchenId = kitchenId;
            this.output = output;
        }

        synchronized void send(String message) {
            output.println(message);
        }
    }

    public static void main(String[] args) {
        try (ServerSocket server = new ServerSocket(5000)) {
            System.out.println("Restaurant Order Server started on port 5000");
            System.out.println("Waiting for clients...");
            while (true) {
                Socket socket = server.accept();
                new Thread(() -> handleClient(socket)).start();
            }
        } catch (IOException e) {
            System.out.println("Server error: " + e.getMessage());
        }
    }

    // Send news to everyone with the selected role.
    private static void notifyRole(String role, String message) {
        for (ClientSession client : clients) {
            if (client.role.equals(role)) client.send("EVENT|" + message);
        }
    }

    // Only the waiter who owns an order gets its READY message.
    private static void notifyWaiter(String waiterId, String message) {
        for (ClientSession client : clients) {
            if (client.role.equals("WAITER") && client.waiterId.equals(waiterId))
                client.send("EVENT|" + message);
        }
    }

    // Give waiting orders to free kitchen workers.
    // One automatic worker gets one unfinished order at a time.
    private static synchronized void dispatchOrders() {
        while (true) {
            ClientSession available = null;
            for (ClientSession client : clients) {
                if (client.role.equals("KITCHEN") && !client.kitchenId.isEmpty()
                        && orderManager.countActiveKitchenOrders(client.kitchenId) == 0) {
                    available = client;
                    break;
                }
            }
            if (available == null) return;
            Order order = orderManager.assignNextOrder(available.kitchenId);
            if (order == null) return;
            available.send("EVENT|ASSIGNED: " + order);
            System.out.println("Assigned Order #" + order.getOrderId()
                    + " to Kitchen " + available.kitchenId);
        }
    }

    private static void handleClient(Socket socket) {
        String role = "";
        String waiterId = "";
        String kitchenId = "";
        ClientSession session = null;

        try (Socket client = socket;
             BufferedReader input = new BufferedReader(
                     new InputStreamReader(client.getInputStream()));
             PrintWriter output = new PrintWriter(client.getOutputStream(), true)) {

            output.println("Connected to Restaurant Order Server!");
            String firstLine = input.readLine();
            if (firstLine == null) return;
            String[] parts = firstLine.trim().toUpperCase().split("\\s+");
            if (parts.length < 2 || !parts[0].equals("ROLE")) {
                output.println("Invalid role selection.");
                return;
            }
            role = parts[1];
            if (!role.equals("WAITER") && !role.equals("KITCHEN") && !role.equals("MANAGER")) {
                output.println("Invalid role selection.");
                return;
            }
            if (role.equals("WAITER")) {
                if (parts.length != 3 || !parts[2].matches("W[0-9]+")) {
                    output.println("Invalid Waiter ID.");
                    return;
                }
                waiterId = parts[2];
            } else if (role.equals("KITCHEN")) {
                // ROLE KITCHEN K1 means automatic work assignments.
                // ROLE KITCHEN keeps old manual NEXT commands for tests.
                if (parts.length == 3 && parts[2].matches("K[0-9]+")) {
                    kitchenId = parts[2];
                } else if (parts.length != 2) {
                    output.println("Invalid Kitchen ID.");
                    return;
                }
            } else if (parts.length != 2) {
                output.println("Invalid role selection.");
                return;
            }

            String title = role.equals("WAITER") ? "WAITER " + waiterId
                    : role.equals("KITCHEN") && !kitchenId.isEmpty()
                    ? "KITCHEN " + kitchenId : role;

            // Do not allow two automatic workers to use the same ID.
            synchronized (Server.class) {
                if (!kitchenId.isEmpty()) {
                    for (ClientSession other : clients) {
                        if (other.role.equals("KITCHEN")
                                && other.kitchenId.equals(kitchenId)) {
                            output.println("Kitchen ID is already connected.");
                            return;
                        }
                    }
                }
                session = new ClientSession(role, waiterId, kitchenId, output);
                clients.add(session);
            }
            output.println("Role accepted: " + title);
            System.out.println("Connected: " + title);
            dispatchOrders();

            String message;
            while ((message = input.readLine()) != null) {
                message = message.trim();
                String upper = message.toUpperCase();
                System.out.println("[" + title + "] " + message);

                if (upper.equals("EXIT")) {
                    session.send("RESPONSE|Goodbye!");
                    break;
                } else if (upper.startsWith("ORDER ")) {
                    if (!role.equals("WAITER")) {
                        session.send("RESPONSE|Access denied. Waiter only.");
                        continue;
                    }
                    String food = message.substring(6).trim();
                    if (food.isEmpty()) {
                        session.send("RESPONSE|Please enter a food item.");
                        continue;
                    }
                    Order order = orderManager.createOrder(food, waiterId);
                    session.send("RESPONSE|Order created: " + order);
                    notifyRole("KITCHEN", "New Order #" + order.getOrderId()
                            + ": " + food + " (Waiter " + waiterId + ")");
                    dispatchOrders();
                } else if (upper.startsWith("STATUS ")) {
                    try {
                        int id = Integer.parseInt(message.substring(7).trim());
                        session.send("RESPONSE|" + orderManager.getOrderStatus(id, role, waiterId));
                    } catch (NumberFormatException e) {
                        session.send("RESPONSE|Invalid order ID.");
                    }
                } else if (upper.equals("NEXT")) {
                    if (!role.equals("KITCHEN")) {
                        session.send("RESPONSE|Access denied. Kitchen only.");
                    } else if (!kitchenId.isEmpty()) {
                        session.send("RESPONSE|Orders are assigned automatically to " + kitchenId + ".");
                    } else {
                        session.send("RESPONSE|" + orderManager.getNextOrder());
                    }
                } else if (upper.startsWith("READY ")) {
                    if (!role.equals("KITCHEN")) {
                        session.send("RESPONSE|Access denied. Kitchen only.");
                        continue;
                    }
                    try {
                        int id = Integer.parseInt(message.substring(6).trim());
                        String worker = kitchenId.isEmpty() ? "MANUAL" : kitchenId;
                        String result;
                        // Keep READY and dispatch together, so a disconnect cannot
                        // change the order owner in the middle of this command.
                        synchronized (Server.class) {
                            result = orderManager.markOrderReady(id, worker);
                            session.send("RESPONSE|" + result);
                            if (result.startsWith("Order ready:")) {
                                String owner = orderManager.getOrderWaiterId(id);
                                if (owner != null) notifyWaiter(owner,
                                        "Order #" + id + " is READY to be served.");
                                dispatchOrders();
                            }
                        }
                    } catch (NumberFormatException e) {
                        session.send("RESPONSE|Invalid order ID.");
                    }
                } else if (upper.startsWith("SERVE ")) {
                    if (!role.equals("WAITER")) {
                        session.send("RESPONSE|Access denied. Waiter only.");
                        continue;
                    }
                    try {
                        int id = Integer.parseInt(message.substring(6).trim());
                        String result = orderManager.serveOrder(id, waiterId);
                        session.send("RESPONSE|" + result);
                        if (result.equals("Order #" + id + " has been served."))
                            notifyRole("MANAGER", "Order #" + id
                                    + " has been served by Waiter " + waiterId + ".");
                    } catch (NumberFormatException e) {
                        session.send("RESPONSE|Invalid order ID.");
                    }
                } else if (upper.equals("LIST")) {
                    String result = role.equals("WAITER") ? orderManager.listWaiterOrders(waiterId)
                            : role.equals("KITCHEN") ? orderManager.listOrders()
                            : orderManager.listManagerOrders(false);
                    session.send("RESPONSE|" + result);
                } else if (upper.equals("HISTORY")) {
                    if (role.equals("KITCHEN")) {
                        session.send("RESPONSE|Access denied. Waiter or Manager only.");
                    } else {
                        String result = role.equals("WAITER")
                                ? orderManager.getWaiterHistory(waiterId)
                                : orderManager.listManagerOrders(true);
                        session.send("RESPONSE|" + result);
                    }
                } else if (upper.equals("SUMMARY")) {
                    if (!role.equals("MANAGER")) {
                        session.send("RESPONSE|Access denied. Manager only.");
                    } else {
                        session.send("RESPONSE|" + orderManager.getSummary());
                    }
                } else {
                    session.send("RESPONSE|Unknown command for this system.");
                }
            }
        } catch (IOException e) {
            System.out.println("Connection error: " + e.getMessage());
        } finally {
            if (session != null) {
                synchronized (Server.class) {
                    clients.remove(session);
                    if (!session.kitchenId.isEmpty()) {
                        int count = orderManager.requeueKitchenOrders(session.kitchenId);
                        if (count > 0) {
                            System.out.println("Kitchen " + session.kitchenId
                                    + " disconnected. Requeued " + count + " order(s).");
                            notifyRole("MANAGER", "Kitchen " + session.kitchenId
                                    + " disconnected; " + count + " order(s) returned to queue.");
                        }
                        dispatchOrders();
                    }
                }
            }
            System.out.println("Disconnected: " + role + " " + waiterId + " " + kitchenId);
        }
    }
}
