import java.util.LinkedHashMap;
import java.util.Map;

public class OrderManager {
    // LinkedHashMap keeps orders in the order they arrived.
    private final LinkedHashMap<Integer, Order> orders = new LinkedHashMap<>();
    private int nextOrderId = 1;

    public synchronized Order createOrder(String food, String waiterId) {
        Order order = new Order(nextOrderId++, food, waiterId);
        orders.put(order.getOrderId(), order);
        return order;
    }

    public synchronized String getOrderStatus(int id, String role, String waiterId) {
        Order order = orders.get(id);
        if (order == null) return "Order not found.";
        if (role.equals("WAITER") && !order.getWaiterId().equals(waiterId))
            return "Access denied. This order belongs to another waiter.";
        return order.toString();
    }

    // An automatic worker receives the oldest waiting order.
    public synchronized Order assignNextOrder(String kitchenId) {
        for (Order order : orders.values()) {
            if (order.getStatus().equals("QUEUED")) {
                order.setKitchenId(kitchenId);
                order.setStatus("IN_PROGRESS");
                return order;
            }
        }
        return null;
    }

    // Keep the old NEXT command for manual kitchen testing.
    public synchronized String getNextOrder() {
        Order order = assignNextOrder("MANUAL");
        return order == null ? "No waiting orders." : "Kitchen started: " + order;
    }

    // A kitchen can only finish work assigned to it.
    public synchronized String markOrderReady(int id, String kitchenId) {
        Order order = orders.get(id);
        if (order == null) return "Order not found.";
        if (order.getStatus().equals("SERVED"))
            return "Order #" + id + " has already been served.";
        if (order.getStatus().equals("READY"))
            return "Order #" + id + " is already READY and waiting to be served.";
        if (order.getStatus().equals("QUEUED"))
            return "Order #" + id + " has not started preparation yet.";
        if (!order.getKitchenId().equals(kitchenId))
            return "Access denied. This order belongs to another kitchen worker.";
        order.setStatus("READY");
        return "Order ready: " + order;
    }

    public synchronized String serveOrder(int id, String waiterId) {
        Order order = orders.get(id);
        if (order == null) return "Order not found.";
        if (!order.getWaiterId().equals(waiterId))
            return "Access denied. This order belongs to another waiter.";
        if (order.getStatus().equals("SERVED"))
            return "Order #" + id + " has already been served.";
        if (!order.getStatus().equals("READY"))
            return "Order #" + id + " is not READY yet.";
        order.setStatus("SERVED");
        return "Order #" + id + " has been served.";
    }

    public synchronized String getOrderWaiterId(int id) {
        Order order = orders.get(id);
        return order == null ? null : order.getWaiterId();
    }

    // Find how many orders the worker is preparing right now.
    public synchronized int countActiveKitchenOrders(String kitchenId) {
        int count = 0;
        for (Order order : orders.values()) {
            if (order.getStatus().equals("IN_PROGRESS")
                    && order.getKitchenId().equals(kitchenId)) count++;
        }
        return count;
    }

    // Put unfinished work back into the queue when a worker leaves.
    public synchronized int requeueKitchenOrders(String kitchenId) {
        int count = 0;
        for (Order order : orders.values()) {
            if (order.getStatus().equals("IN_PROGRESS")
                    && order.getKitchenId().equals(kitchenId)) {
                order.setStatus("QUEUED");
                order.setKitchenId("");
                count++;
            }
        }
        return count;
    }

    public synchronized String listWaiterOrders(String waiterId) {
        StringBuilder result = new StringBuilder();
        for (Order order : orders.values()) {
            if (order.getWaiterId().equals(waiterId)
                    && !order.getStatus().equals("SERVED")) addOrder(result, order);
        }
        return result.length() == 0 ? "No active orders for Waiter " + waiterId + "." : result.toString();
    }

    public synchronized String listOrders() {
        StringBuilder result = new StringBuilder();
        for (Order order : orders.values()) {
            if (!order.getStatus().equals("SERVED")) addOrder(result, order);
        }
        return result.length() == 0 ? "No active orders." : result.toString();
    }

    public synchronized String getWaiterHistory(String waiterId) {
        StringBuilder result = new StringBuilder();
        for (Order order : orders.values()) {
            if (order.getWaiterId().equals(waiterId)
                    && order.getStatus().equals("SERVED")) addOrder(result, order);
        }
        return result.length() == 0 ? "No served orders for Waiter " + waiterId + "." : result.toString();
    }

    // Show manager orders in separate groups for each waiter.
    public synchronized String listManagerOrders(boolean servedOnly) {
        LinkedHashMap<String, StringBuilder> groups = new LinkedHashMap<>();
        for (Order order : orders.values()) {
            if (order.getStatus().equals("SERVED") != servedOnly) continue;
            String waiterId = order.getWaiterId();
            if (!groups.containsKey(waiterId)) groups.put(waiterId, new StringBuilder());
            addOrder(groups.get(waiterId), order);
        }
        if (groups.isEmpty()) return servedOnly ? "No served orders yet." : "No active orders.";
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, StringBuilder> entry : groups.entrySet()) {
            if (result.length() > 0) result.append(" || ");
            result.append("WAITER ").append(entry.getKey()).append(": ").append(entry.getValue());
        }
        return result.toString();
    }

    // Give the manager a quick view of the whole restaurant.
    public synchronized String getSummary() {
        int queued = 0, working = 0, ready = 0, served = 0;
        for (Order order : orders.values()) {
            switch (order.getStatus()) {
                case "QUEUED": queued++; break;
                case "IN_PROGRESS": working++; break;
                case "READY": ready++; break;
                case "SERVED": served++; break;
            }
        }
        return "SUMMARY | QUEUED: " + queued + " | IN_PROGRESS: " + working
                + " | READY: " + ready + " | SERVED: " + served
                + " | TOTAL: " + orders.size();
    }

    private void addOrder(StringBuilder result, Order order) {
        if (result.length() > 0) result.append(" ; ");
        result.append(order);
    }
}
