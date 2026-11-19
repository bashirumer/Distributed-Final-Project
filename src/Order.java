public class Order {
    private final int orderId;
    private final String foodItem;
    private final String waiterId;
    private String status;
    private String kitchenId;

    // Each order starts in the waiting queue.
    public Order(int orderId, String foodItem, String waiterId) {
        this.orderId = orderId;
        this.foodItem = foodItem;
        this.waiterId = waiterId;
        this.status = "QUEUED";
        this.kitchenId = "";
    }

    public int getOrderId() { return orderId; }
    public String getFoodItem() { return foodItem; }
    public String getWaiterId() { return waiterId; }
    public String getStatus() { return status; }
    public String getKitchenId() { return kitchenId; }
    public void setStatus(String status) { this.status = status; }
    public void setKitchenId(String kitchenId) { this.kitchenId = kitchenId; }

    @Override
    public String toString() {
        String text = "Order #" + orderId + " | Food: " + foodItem
                + " | Status: " + status + " | Waiter: " + waiterId;
        if (!kitchenId.isEmpty()) text += " | Kitchen: " + kitchenId;
        return text;
    }
}
