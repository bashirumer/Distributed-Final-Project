
import java.io.*;
import java.net.*;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Client {

    // Read order details sent by the server.
    // A kitchen name is optional because waiting orders have no kitchen.
    private static final Pattern ORDER_PATTERN = Pattern.compile(
            "Order #(\\d+) \\| Food: (.*?) \\| Status: (\\w+) \\| Waiter: (\\w+)(?: \\| Kitchen: (\\w+))?");

    // Find the ID in a new kitchen assignment notification.
    private static final Pattern ASSIGNED_PATTERN = Pattern.compile(
            "ASSIGNED: Order #(\\d+)");

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        // Choose the type of employee.
        System.out.println("\n================================");
        System.out.println("     RESTAURANT ORDER SYSTEM");
        System.out.println("================================");
        System.out.println("1. Waiter");
        System.out.println("2. Kitchen");
        System.out.println("3. Manager");

        String role = "";

        // Keep asking until the user chooses a valid role.
        while (role.isEmpty()) {
            System.out.print("Select your role (1-3): ");

            if (!scanner.hasNextLine()) {
                return;
            }

            String choice = scanner.nextLine().trim();

            if (choice.equals("1")) {
                role = "WAITER";
            } else if (choice.equals("2")) {
                role = "KITCHEN";
            } else if (choice.equals("3")) {
                role = "MANAGER";
            } else {
                System.out.println("Invalid choice. Please try again.");
            }
        }

        // Waiters and kitchen workers use their own IDs.
        // Examples: W1, W2, K1, and K2.
        String workerId = "";

        if (role.equals("WAITER") || role.equals("KITCHEN")) {
            String prefix = role.equals("WAITER") ? "W" : "K";

            while (workerId.isEmpty()) {
                System.out.print(
                        "Enter " + role + " ID (example " + prefix + "1): ");

                if (!scanner.hasNextLine()) {
                    return;
                }

                String value = scanner.nextLine()
                        .trim().toUpperCase();

                // Only accept the correct letter followed by numbers.
                if (value.matches(prefix + "[0-9]+")) {
                    workerId = value;
                } else {
                    System.out.println(
                            "Invalid ID. Use " + prefix
                                    + "1, " + prefix + "2, etc.");
                }
            }
        }

        // This name appears beside the command prompt.
        // Examples: WAITER W1, KITCHEN K1, MANAGER.
        final String terminalName = workerId.isEmpty()
                ? role
                : role + " " + workerId;

        final String selectedRole = role;

        // Remember which order is assigned to this kitchen.
        // -1 means there is no known assigned order.
        //
        // Two threads may read or change this value,
        // so AtomicInteger keeps those changes safe.
        AtomicInteger assignedOrderId = new AtomicInteger(-1);

        try (
            // Connect to the central restaurant server.
            Socket socket = new Socket("localhost", 5000);

            // Read messages from the server.
            BufferedReader input = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()));

            // Send commands to the server.
            PrintWriter output = new PrintWriter(
                    socket.getOutputStream(), true)
        ) {

            // Read the first message from the server.
            String welcome = input.readLine();

            if (welcome == null) {
                System.out.println("Server disconnected.");
                return;
            }

            System.out.println(welcome);

            // Tell the server which employee is connecting.
            output.println("ROLE " + terminalName);

            // Check that the server accepted the role.
            String confirmation = input.readLine();

            if (!("Role accepted: " + terminalName)
                    .equals(confirmation)) {
                System.out.println(
                        confirmation == null
                                ? "Connection rejected."
                                : confirmation);
                return;
            }

            System.out.println(confirmation);

            // Show the commands available to this employee.
            showMenu(role, terminalName);

            System.out.println(
                    "Type a command shown in the menu. "
                    + "Commands are not case-sensitive.");
            System.out.println("Type MENU to see the options again.");

            // This thread listens for messages from the server.
            //
            // It runs separately from keyboard input.
            // This allows orders and notifications to arrive
            // without the employee entering another command.
            Thread receiver = new Thread(() -> {
                try {
                    String line;

                    // Keep reading until the connection closes.
                    while ((line = input.readLine()) != null) {

                        synchronized (System.out) {

                            // Handle automatic notifications.
                            if (line.startsWith("EVENT|")) {
                                String event = line.substring(6);

                                // Remember new kitchen assignments.
                                // This is used by ASSIGNED and DONE.
                                if (selectedRole.equals("KITCHEN")) {
                                    Matcher match =
                                            ASSIGNED_PATTERN.matcher(event);

                                    if (match.find()) {
                                        assignedOrderId.set(
                                                Integer.parseInt(
                                                        match.group(1)));
                                    }
                                }

                                // Display the notification separately.
                                System.out.println();
                                System.out.println("--------------------------------");
                                System.out.println("[NOTIFICATION]");

                                printFormatted(event);

                                System.out.println("--------------------------------");
                            }

                            // Handle responses to commands.
                            else if (line.startsWith("RESPONSE|")) {
                                String response = line.substring(9);

                                // When a kitchen finishes an order,
                                // clear that assignment.
                                //
                                // The server can then assign another
                                // waiting order to the free kitchen.
                                if (selectedRole.equals("KITCHEN")
                                        && response.startsWith("Order ready:")) {

                                    Matcher match =
                                            ORDER_PATTERN.matcher(response);

                                    if (match.find()) {
                                        int finishedId =
                                                Integer.parseInt(
                                                        match.group(1));

                                        assignedOrderId.compareAndSet(
                                                finishedId, -1);
                                    }
                                }

                                printFormatted(response);
                            }

                            // Display any other server message.
                            else {
                                System.out.println();
                                System.out.println(line);
                            }

                            // Show which terminal is receiving input.
                            showPrompt(terminalName);
                        }
                    }

                    System.out.println();
                    System.out.println("Disconnected from server.");

                } catch (IOException e) {

                    // Report any connection problem.
                    if (!socket.isClosed()) {
                        System.out.println();
                        System.out.println(
                                "Server connection lost: "
                                        + e.getMessage());
                    }
                }
            });

            // Stop this thread when the application closes.
            receiver.setDaemon(true);
            receiver.start();

            // Show the first input prompt.
            showPrompt(terminalName);

            // Read commands typed by the employee.
            while (scanner.hasNextLine()) {
                String choice = scanner.nextLine().trim();

                // Ignore an empty input.
                if (choice.isEmpty()) {
                    showPrompt(terminalName);
                    continue;
                }

                // MENU and HELP display the command list.
                // These commands do not need the server.
                if (choice.equalsIgnoreCase("MENU")
                        || choice.equalsIgnoreCase("HELP")) {

                    showMenu(role, terminalName);
                    showPrompt(terminalName);
                    continue;
                }

                // Turn simple keywords into commands
                // that our server already understands.
                String command = prepareCommand(
                        role,
                        choice,
                        scanner,
                        assignedOrderId.get());

                if (command == null) {
                    System.out.println(
                            "Unknown command. Type MENU to see your commands.");
                    showPrompt(terminalName);
                    continue;
                }

                // Do not send an empty command.
                if (command.isEmpty()) {
                    showPrompt(terminalName);
                    continue;
                }

                // Send the command through the TCP connection.
                //
                // The server still checks permissions,
                // so a waiter cannot use kitchen-only commands.
                output.println(command);

                if (output.checkError()) {
                    System.out.println("Could not send command.");
                    break;
                }

                // Close the client when the employee exits.
                if (command.equalsIgnoreCase("EXIT")) {
                    break;
                }
            }

        } catch (IOException e) {
            System.out.println(
                    "Connection error: " + e.getMessage());

        } finally {
            scanner.close();
        }
    }

    // Change simple keywords into the original server commands.
    //
    // The employee can type commands in uppercase or lowercase.
    //
    // We also keep original full commands working:
    // ORDER Burger, READY 2, STATUS 3, SERVE 1.
    private static String prepareCommand(
            String role,
            String choice,
            Scanner scanner,
            int assignedOrderId) {

        String upper = choice.toUpperCase();

        // All roles can disconnect.
        if (upper.equals("EXIT")
                || upper.equals("QUIT")
                || upper.equals("0")) {
            return "EXIT";
        }

        // Commands for waiters.
        if (role.equals("WAITER")) {

            // NEW creates a new order.
            // ORDER without food does the same thing.
            if (upper.equals("NEW") || upper.equals("ORDER")) {
                return askForFood(scanner);
            }

            // Allow typing a full order directly.
            if (upper.startsWith("ORDER ")) {
                return choice;
            }

            // Show this waiter's active or served orders.
            if (upper.equals("LIST") || upper.equals("HISTORY")) {
                return upper;
            }

            // Ask which order the waiter wants to check.
            if (upper.equals("STATUS") || upper.equals("CHECK")) {
                return askForOrderId(scanner, "STATUS ");
            }

            if (upper.startsWith("STATUS ")) {
                return choice;
            }

            // Ask which ready order the waiter wants to serve.
            if (upper.equals("SERVE")) {
                return askForOrderId(scanner, "SERVE ");
            }

            if (upper.startsWith("SERVE ")) {
                return choice;
            }
        }

        // Commands for kitchen workers.
        if (role.equals("KITCHEN")) {

            // Show all active restaurant orders.
            if (upper.equals("LIST")) {
                return "LIST";
            }

            // View the order currently assigned to this worker.
            if (upper.equals("ASSIGNED")
                    || upper.equals("MYORDER")) {

                if (assignedOrderId == -1) {
                    System.out.println(
                            "No order is assigned to this kitchen right now.");
                    return "";
                }

                return "STATUS " + assignedOrderId;
            }

            // Finish the order currently assigned to this worker.
            //
            // The user does not need to remember the order ID.
            if (upper.equals("DONE")) {

                if (assignedOrderId == -1) {
                    System.out.println(
                            "No order is assigned to this kitchen right now.");
                    return "";
                }

                return "READY " + assignedOrderId;
            }

            // READY without a number asks for an order ID.
            if (upper.equals("READY")) {
                return askForOrderId(scanner, "READY ");
            }

            if (upper.startsWith("READY ")) {
                return choice;
            }

            // Check the status of any order.
            if (upper.equals("STATUS") || upper.equals("CHECK")) {
                return askForOrderId(scanner, "STATUS ");
            }

            if (upper.startsWith("STATUS ")) {
                return choice;
            }
        }

        // Commands for managers.
        if (role.equals("MANAGER")) {

            // Show all orders, history, or restaurant totals.
            if (upper.equals("LIST")
                    || upper.equals("HISTORY")
                    || upper.equals("SUMMARY")) {
                return upper;
            }

            // Check an order using its ID.
            if (upper.equals("STATUS") || upper.equals("CHECK")) {
                return askForOrderId(scanner, "STATUS ");
            }

            if (upper.startsWith("STATUS ")) {
                return choice;
            }
        }

        // Keep other text commands available for server testing.
        // The server will reject invalid or unauthorized commands.
        //
        // Numbers are no longer used for menu commands.
        if (upper.matches("[0-9]+")) {
            return null;
        }

        return choice;
    }

    // Ask the waiter which food items to order.
    //
    // For example:
    // Employee types: NEW
    // Employee enters: Burger, Fries
    // Client sends: ORDER Burger, Fries
    private static String askForFood(Scanner scanner) {

        System.out.print("Enter food items: ");

        if (!scanner.hasNextLine()) {
            return "";
        }

        String food = scanner.nextLine().trim();

        if (food.isEmpty()) {
            System.out.println("Food items cannot be empty.");
            return "";
        }

        return "ORDER " + food;
    }

    // Ask for an order ID.
    //
    // STATUS and SERVE use this method.
    private static String askForOrderId(
            Scanner scanner,
            String command) {

        System.out.print("Enter order ID: ");

        if (!scanner.hasNextLine()) {
            return "";
        }

        String id = scanner.nextLine().trim();

        // An order ID must be a number.
        if (!id.matches("[0-9]+")) {
            System.out.println(
                    "Please enter an order number, such as 1.");
            return "";
        }

        return command + id;
    }

    // Show which employee terminal is waiting for input.
    //
    // Examples:
    // [WAITER W1] >
    // [KITCHEN K1] >
    // [MANAGER] >
    private static void showPrompt(String terminalName) {

        // Separate the prompt from previous output.
        System.out.println();

        System.out.print("[" + terminalName + "] > ");
        System.out.flush();
    }

    // Print server messages in a readable layout.
    //
    // The server sends order information on one line.
    // This method displays each order on separate lines.
    private static void printFormatted(String message) {

        System.out.println();

        // Show the restaurant summary.
        if (message.startsWith("SUMMARY | ")) {

            System.out.println("RESTAURANT SUMMARY");
            System.out.println("--------------------------------");

            // Print each status count on its own line.
            for (String part :
                    message.substring(10).split(" \\| ")) {
                System.out.println(part);
            }

            return;
        }

        // Normal messages do not always include an order.
        // Examples: errors and access-denied messages.
        if (!message.contains(" | Food: ")) {
            System.out.println(message);
            return;
        }

        // The manager receives orders grouped by waiter.
        // Groups are separated using ||.
        String[] groups = message.split(" \\|\\| ");

        for (String group : groups) {
            String text = group.trim();

            // Display a heading for each waiter.
            if (text.matches("^WAITER W[0-9]+:.*")) {

                int colon = text.indexOf(':');

                System.out.println("--------------------------------");
                System.out.println(text.substring(0, colon));
                System.out.println("--------------------------------");

                text = text.substring(colon + 1).trim();
            }

            // Separate the individual orders.
            for (String entry : text.split(" ; ")) {

                Matcher match = ORDER_PATTERN.matcher(entry);

                if (match.find()) {

                    // Keep text such as "Order ready:".
                    String prefix =
                            entry.substring(0, match.start()).trim();

                    if (!prefix.isEmpty()) {
                        System.out.println(prefix);
                    }

                    // Show the order number and food.
                    System.out.println(
                            "Order #" + match.group(1)
                                    + " - " + match.group(2));

                    // Show the status and employee IDs.
                    System.out.println(
                            "  Status: " + match.group(3)
                                    + "    Waiter: " + match.group(4)
                                    + (match.group(5) == null
                                    ? ""
                                    : "    Kitchen: " + match.group(5)));

                    System.out.println();

                } else {
                    System.out.println(entry);
                }
            }
        }
    }

    // Show the keywords available to each employee.
    //
    // Every role has different responsibilities.
    // All keywords work in uppercase or lowercase.
    private static void showMenu(
            String role,
            String terminalName) {

        System.out.println("\n================================");
        System.out.println("        " + terminalName + " MENU");
        System.out.println("================================");

        if (role.equals("WAITER")) {

            System.out.println("NEW       - Create a new order");
            System.out.println("LIST      - View my active orders");
            System.out.println("STATUS    - Check an order's status");
            System.out.println("SERVE     - Serve a ready order");
            System.out.println("HISTORY   - View my served orders");
        }

        else if (role.equals("KITCHEN")) {

            System.out.println("Orders are assigned automatically.");
            System.out.println("LIST      - View all active orders");
            System.out.println("ASSIGNED  - View my assigned order");
            System.out.println("DONE      - Mark my assigned order READY");
            System.out.println("STATUS    - Check an order's status");
        }

        else {

            System.out.println("LIST      - View active orders by waiter");
            System.out.println("HISTORY   - View served order history");
            System.out.println("SUMMARY   - View restaurant summary");
            System.out.println("STATUS    - Check an order's status");
        }

        System.out.println("EXIT      - Disconnect");
        System.out.println("--------------------------------");
        System.out.println("MENU / HELP - Show this menu again");
        System.out.println(
                "Original commands like ORDER Burger or READY 1 still work.");
        System.out.println("================================");
    }
}
