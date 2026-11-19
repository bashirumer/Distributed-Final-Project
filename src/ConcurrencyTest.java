
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

public class ConcurrencyTest {

    // Server address and port
    private static final String HOST = "localhost";
    private static final int PORT = 5000;

    // Number of waiters for the first test
    private static final int TEST_COUNT = 5;

    // Find an order ID in a server response
    private static final Pattern ID_PATTERN =
            Pattern.compile("Order #(\\d+)");

    // Send one command using a new client connection
    private static String sendCommand(String role, String command)
            throws IOException {

        try (
            Socket socket = new Socket(HOST, PORT);

            BufferedReader input = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()));

            PrintWriter output = new PrintWriter(
                    socket.getOutputStream(), true)
        ) {

            // Stop waiting if the server does not respond
            socket.setSoTimeout(5000);

            // Read the welcome message
            String welcome = input.readLine();

            if (welcome == null) {
                throw new IOException("Server did not connect.");
            }

            // Tell the server which role to use
            output.println("ROLE " + role);

            // Check if the server accepted the role
            String confirmation = input.readLine();

            if (confirmation == null
                    || !confirmation.startsWith("Role accepted:")) {

                throw new IOException("Role was not accepted: " + role);
            }

            // Send the command
            output.println(command);

            // Read the server's response
            String response;

            while ((response = input.readLine()) != null) {

                // Skip automatic notifications
                if (response.startsWith("EVENT|")) {
                    continue;
                }

                // Return the command result
                if (response.startsWith("RESPONSE|")) {
                    return response.substring(9);
                }
            }

            throw new IOException("Server did not send a response.");
        }
    }

    // Run several commands at nearly the same time
    private static List<String> runTogether(
            List<String> roles, List<String> commands)
            throws Exception {

        int count = commands.size();

        // Create threads for the test clients
        ExecutorService pool =
                Executors.newFixedThreadPool(count);

        // Make the threads start together
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<String>> results = new ArrayList<>();

        try {

            // Create one task for each client
            for (int i = 0; i < count; i++) {

                final String role = roles.get(i);
                final String command = commands.get(i);

                results.add(pool.submit(() -> {

                    // Tell the main thread this client is ready
                    ready.countDown();

                    // Wait for the start signal
                    start.await();

                    // Send the command to the server
                    return sendCommand(role, command);
                }));
            }

            // Wait until all test clients are ready
            ready.await();

            // Start all test clients
            start.countDown();

            List<String> responses = new ArrayList<>();

            // Collect the responses
            for (Future<String> result : results) {
                responses.add(result.get());
            }

            return responses;

        } finally {

            // Stop the test threads
            pool.shutdownNow();
        }
    }

    // Get the order ID from a response
    private static int getOrderId(String response) {

        Matcher matcher = ID_PATTERN.matcher(response);

        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }

        return -1;
    }

    public static void main(String[] args) {

        try {

            System.out.println("================================");
            System.out.println("       CONCURRENCY TEST");
            System.out.println("================================");

            // TEST 1: Waiters submit orders together
            System.out.println();
            System.out.println("TEST 1: Simultaneous Orders");
            System.out.println("--------------------------------");

            List<String> roles = new ArrayList<>();
            List<String> commands = new ArrayList<>();

            // Create five different waiter requests
            for (int i = 1; i <= TEST_COUNT; i++) {

                roles.add("WAITER W" + i);
                commands.add("ORDER TestFood" + i);
            }

            // Send all five orders
            List<String> responses = runTogether(roles, commands);

            Set<Integer> orderIds = new HashSet<>();
            Map<Integer, String> orderOwners = new HashMap<>();

            boolean allCreated = true;

            // Check each created order
            for (int i = 0; i < responses.size(); i++) {

                String response = responses.get(i);

                System.out.println(response);

                int orderId = getOrderId(response);

                if (response.startsWith("Order created:")
                        && orderId != -1) {

                    orderIds.add(orderId);
                    orderOwners.put(orderId, "W" + (i + 1));

                } else {
                    allCreated = false;
                }
            }

            // All five orders must have different IDs
            boolean test1Passed =
                    allCreated && orderIds.size() == TEST_COUNT;

            System.out.println();
            System.out.println("Unique Order IDs: "
                    + (test1Passed ? "PASS" : "FAIL"));

            if (!test1Passed) {
                System.out.println("Test stopped because order creation failed.");
                return;
            }

            // TEST 2: Two kitchen clients take orders together
            System.out.println();
            System.out.println("TEST 2: Simultaneous Kitchen Requests");
            System.out.println("--------------------------------");

            responses = runTogether(
                    Arrays.asList("KITCHEN", "KITCHEN"),
                    Arrays.asList("NEXT", "NEXT")
            );

            Set<Integer> kitchenOrderIds = new HashSet<>();

            boolean bothStarted = true;

            // Check which orders were assigned
            for (String response : responses) {

                System.out.println(response);

                int orderId = getOrderId(response);

                if (response.startsWith("Kitchen started:")
                        && orderId != -1) {

                    kitchenOrderIds.add(orderId);

                } else {
                    bothStarted = false;
                }
            }

            // Kitchen clients must receive different orders
            boolean test2Passed =
                    bothStarted && kitchenOrderIds.size() == 2;

            System.out.println();
            System.out.println("Different Orders Assigned: "
                    + (test2Passed ? "PASS" : "FAIL"));

            if (!test2Passed) {
                System.out.println("Test stopped because kitchen test failed.");
                return;
            }

            // TEST 3: Two clients try serving the same order
            System.out.println();
            System.out.println("TEST 3: Duplicate Serving");
            System.out.println("--------------------------------");

            // Choose one order started by the kitchen
            int targetId = Collections.min(kitchenOrderIds);

            // Find the waiter who owns this order
            String owner = orderOwners.get(targetId);

            if (owner == null) {
                throw new IOException(
                        "Could not find the owner of Order #" + targetId
                        + ". Run this test with a fresh server.");
            }

            // Make the order ready before serving
            String readyResponse =
                    sendCommand("KITCHEN", "READY " + targetId);

            System.out.println(readyResponse);

            if (!readyResponse.startsWith("Order ready:")) {
                System.out.println("FAIL: Order was not marked READY.");
                return;
            }

            // Both clients use the same waiter ID
            responses = runTogether(
                    Arrays.asList("WAITER " + owner, "WAITER " + owner),
                    Arrays.asList("SERVE " + targetId,
                                  "SERVE " + targetId)
            );

            int servedCount = 0;
            int alreadyServedCount = 0;

            // Count successful and rejected requests
            for (String response : responses) {

                System.out.println(response);

                if (response.equals(
                        "Order #" + targetId + " has been served.")) {

                    servedCount++;
                }

                if (response.equals(
                        "Order #" + targetId
                        + " has already been served.")) {

                    alreadyServedCount++;
                }
            }

            // Only one client should serve the order
            boolean test3Passed =
                    servedCount == 1 && alreadyServedCount == 1;

            System.out.println();
            System.out.println("Duplicate Serving Prevented: "
                    + (test3Passed ? "PASS" : "FAIL"));

            // Show the final results
            System.out.println();
            System.out.println("================================");
            System.out.println("          TEST SUMMARY");
            System.out.println("================================");

            System.out.println("Unique Order IDs: "
                    + (test1Passed ? "PASS" : "FAIL"));

            System.out.println("Kitchen Synchronization: "
                    + (test2Passed ? "PASS" : "FAIL"));

            System.out.println("Duplicate Serving Prevention: "
                    + (test3Passed ? "PASS" : "FAIL"));

            System.out.println("================================");

        } catch (Exception e) {

            // Show any test errors
            System.out.println();
            System.out.println("Test error: " + e.getMessage());
        }
    }
}
