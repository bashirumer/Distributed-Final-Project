import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

// A simple window-based client for the restaurant server.
//
// It talks to the same Server using the same text commands
// as the terminal Client (ORDER, LIST, READY, SERVE, ...),
// so the server does not need any changes.
public class GuiClient {

    // Same order format the terminal client reads.
    private static final Pattern ORDER_PATTERN = Pattern.compile(
            "Order #(\\d+) \\| Food: (.*?) \\| Status: (\\w+) \\| Waiter: (\\w+)(?: \\| Kitchen: (\\w+))?");

    private static final Pattern ASSIGNED_PATTERN = Pattern.compile("ASSIGNED: Order #(\\d+)");

    private static final int PORT = 5000;

    // Colors for each order status.
    private static final Color QUEUED = new Color(120, 120, 120);
    private static final Color IN_PROGRESS = new Color(214, 125, 0);
    private static final Color READY = new Color(30, 150, 70);
    private static final Color SERVED = new Color(50, 100, 200);

    private final String role;
    private final String title;
    private final Socket socket;
    private final BufferedReader input;
    private final PrintWriter output;

    // The server answers commands in the order they were sent.
    // We remember what we asked for, so we know what each RESPONSE is.
    private final ConcurrentLinkedQueue<String> pending = new ConcurrentLinkedQueue<>();

    // Order currently assigned to this kitchen worker (-1 = none).
    private volatile int assignedOrderId = -1;

    // true = table shows served history, false = active orders.
    private boolean showingHistory = false;

    private boolean closed = false;
    private Timer refreshTimer;

    private JFrame frame;
    private DefaultTableModel tableModel;
    private JTable table;
    private JLabel tableTitle;
    private JTextArea log;
    private JLabel assignedLabel;
    private JButton doneButton;

    private GuiClient(String role, String title, Socket socket,
                      BufferedReader input, PrintWriter output) {
        this.role = role;
        this.title = title;
        this.socket = socket;
        this.input = input;
        this.output = output;
    }

    // How many windows are open. The program ends when the last one closes.
    private static int openWindows = 0;

    // Next suggested IDs, so the second waiter defaults to W2, etc.
    private static int nextWaiter = 1;
    private static int nextKitchen = 1;
    private static String lastHost = "localhost";

    public static void main(String[] args) {
        // Ask in the terminal how many users to open.
        Scanner scanner = new Scanner(System.in);
        int count = 0;
        while (count < 1) {
            System.out.print("How many users do you want to open? ");
            if (!scanner.hasNextLine()) return;
            try {
                count = Integer.parseInt(scanner.nextLine().trim());
            } catch (NumberFormatException e) {
                count = 0;
            }
            if (count < 1) System.out.println("Please enter a number of 1 or more.");
        }

        final int total = count;
        SwingUtilities.invokeLater(() -> {
            // Open the login boxes one after another.
            for (int i = 1; i <= total; i++) {
                if (!showLogin(i, total)) break;   // Cancel stops opening more
            }
            if (openWindows == 0) System.exit(0);
        });
    }

    // ---------------------------------------------------------------
    // Login screen: choose role, ID and server address.
    // Returns false if the user pressed Cancel.
    // ---------------------------------------------------------------
    private static boolean showLogin(int number, int total) {
        JComboBox<String> roleBox = new JComboBox<>(new String[]{"WAITER", "KITCHEN", "MANAGER"});
        JTextField idField = new JTextField("W" + nextWaiter, 10);
        JTextField hostField = new JTextField(lastHost, 10);

        // Suggest the next free-looking ID when the role changes.
        roleBox.addActionListener(e -> {
            String r = (String) roleBox.getSelectedItem();
            idField.setEnabled(!r.equals("MANAGER"));
            idField.setText(r.equals("WAITER") ? "W" + nextWaiter
                    : r.equals("KITCHEN") ? "K" + nextKitchen : "");
        });

        JPanel panel = new JPanel(new GridLayout(0, 2, 8, 8));
        panel.add(new JLabel("Role:"));
        panel.add(roleBox);
        panel.add(new JLabel("Employee ID:"));
        panel.add(idField);
        panel.add(new JLabel("Server host:"));
        panel.add(hostField);

        while (true) {
            int choice = JOptionPane.showConfirmDialog(null, panel,
                    "Login - User " + number + " of " + total,
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) return false;

            String role = (String) roleBox.getSelectedItem();
            String id = idField.getText().trim().toUpperCase();
            String host = hostField.getText().trim();

            // Check the ID before connecting, same rules as the server.
            if (role.equals("WAITER") && !id.matches("W[0-9]+")) {
                error("Waiter ID must look like W1, W2, ...");
                continue;
            }
            if (role.equals("KITCHEN") && !id.matches("K[0-9]+")) {
                error("Kitchen ID must look like K1, K2, ...");
                continue;
            }

            String title = role.equals("MANAGER") ? role : role + " " + id;
            if (host.isEmpty()) host = "localhost";
            String problem = connect(role, title, host);
            if (problem == null) {
                // Connected. Move the suggested IDs forward for the next user.
                lastHost = host;
                int num = role.equals("MANAGER") ? 0 : Integer.parseInt(id.substring(1));
                if (role.equals("WAITER")) nextWaiter = Math.max(nextWaiter, num + 1);
                if (role.equals("KITCHEN")) nextKitchen = Math.max(nextKitchen, num + 1);
                return true;
            }
            error(problem);
        }
    }

    // Connect and send the ROLE line. Returns an error message, or null on success.
    private static String connect(String role, String title, String host) {
        try {
            Socket socket = new Socket(host, PORT);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);

            String welcome = in.readLine();
            if (welcome == null) { socket.close(); return "Server closed the connection."; }

            out.println("ROLE " + title);
            String confirmation = in.readLine();
            if (!("Role accepted: " + title).equals(confirmation)) {
                socket.close();
                return confirmation == null ? "Connection rejected." : confirmation;
            }

            GuiClient client = new GuiClient(role, title, socket, in, out);
            client.buildWindow();
            client.startReceiver();
            client.refresh();
            return null;
        } catch (IOException e) {
            return "Could not connect to " + host + ":" + PORT
                    + "\nIs the server running?\n\n" + e.getMessage();
        }
    }

    private static void error(String message) {
        JOptionPane.showMessageDialog(null, message, "Error", JOptionPane.ERROR_MESSAGE);
    }

    // ---------------------------------------------------------------
    // Main window
    // ---------------------------------------------------------------
    private void buildWindow() {
        frame = new JFrame("Restaurant Order System - " + title);
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { disconnect(); }
        });

        // Header bar
        JLabel header = new JLabel("  " + title);
        header.setFont(header.getFont().deriveFont(Font.BOLD, 18f));
        header.setOpaque(true);
        header.setBackground(roleColor());
        header.setForeground(Color.WHITE);
        header.setBorder(new EmptyBorder(10, 6, 10, 6));

        // Orders table
        tableModel = new DefaultTableModel(new String[]{"Order #", "Food", "Status", "Waiter", "Kitchen"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        table = new JTable(tableModel);
        table.setRowHeight(24);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setMaxWidth(70);
        table.getColumnModel().getColumn(2).setCellRenderer(new StatusRenderer());

        tableTitle = new JLabel("Active orders");
        tableTitle.setFont(tableTitle.getFont().deriveFont(Font.BOLD, 14f));
        tableTitle.setBorder(new EmptyBorder(6, 2, 6, 2));

        JPanel tablePanel = new JPanel(new BorderLayout());
        tablePanel.add(tableTitle, BorderLayout.NORTH);
        tablePanel.add(new JScrollPane(table), BorderLayout.CENTER);

        // Activity log (responses and notifications)
        log = new JTextArea(8, 40);
        log.setEditable(false);
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(log);
        logScroll.setBorder(BorderFactory.createTitledBorder("Activity"));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tablePanel, logScroll);
        split.setResizeWeight(0.65);
        split.setBorder(new EmptyBorder(8, 8, 0, 8));

        frame.setLayout(new BorderLayout());
        frame.add(header, BorderLayout.NORTH);
        frame.add(split, BorderLayout.CENTER);
        frame.add(buildActions(), BorderLayout.SOUTH);

        // Spread windows out so they do not open on top of each other.
        frame.setSize(760, 560);
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        int offset = (openWindows * 60) % Math.max(60, screen.height - 560);
        frame.setLocation(screen.x + 40 + offset, screen.y + 40 + offset);
        openWindows++;
        frame.setVisible(true);

        // The server does not announce every status change to every role
        // (e.g. a waiter is not told when cooking starts), so reload the
        // table every few seconds. Skip if we are still waiting on answers.
        refreshTimer = new Timer(3000, e -> {
            if (pending.isEmpty()) refresh();
        });
        refreshTimer.start();
    }

    // Buttons depend on the employee's role.
    private JPanel buildActions() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));

        if (role.equals("WAITER")) {
            JTextField food = new JTextField(18);
            JButton place = new JButton("Place Order");
            ActionListener order = e -> {
                String text = food.getText().trim();
                if (text.isEmpty()) { addLog("Enter food items first."); return; }
                send("ORDER " + text, "ACTION");
                food.setText("");
            };
            place.addActionListener(order);
            food.addActionListener(order);   // Enter key also places the order

            JButton serve = new JButton("Serve Selected");
            serve.addActionListener(e -> {
                Integer id = selectedOrderId();
                if (id == null) { addLog("Select a READY order in the table first."); return; }
                send("SERVE " + id, "ACTION");
            });

            panel.add(new JLabel("Food:"));
            panel.add(food);
            panel.add(place);
            panel.add(serve);
            panel.add(viewButton("Active", false));
            panel.add(viewButton("History", true));

        } else if (role.equals("KITCHEN")) {
            assignedLabel = new JLabel("No order assigned");
            assignedLabel.setFont(assignedLabel.getFont().deriveFont(Font.BOLD));

            doneButton = new JButton("Mark My Order Ready");
            doneButton.setEnabled(false);
            doneButton.addActionListener(e -> {
                if (assignedOrderId != -1) send("READY " + assignedOrderId, "ACTION");
            });

            JButton refresh = new JButton("Refresh");
            refresh.addActionListener(e -> refresh());

            panel.add(assignedLabel);
            panel.add(doneButton);
            panel.add(refresh);

        } else { // MANAGER
            JButton summary = new JButton("Summary");
            summary.addActionListener(e -> send("SUMMARY", "SUMMARY"));

            panel.add(viewButton("Active", false));
            panel.add(viewButton("History", true));
            panel.add(summary);
        }

        JButton exit = new JButton("Exit");
        exit.addActionListener(e -> disconnect());
        panel.add(exit);

        // Light tint of the role color behind the buttons.
        Color c = roleColor();
        panel.setBackground(new Color(
                c.getRed() + (255 - c.getRed()) * 4 / 5,
                c.getGreen() + (255 - c.getGreen()) * 4 / 5,
                c.getBlue() + (255 - c.getBlue()) * 4 / 5));
        return panel;
    }

    // Each role has its own color so windows are easy to tell apart.
    // Waiter = teal, Kitchen = red, Manager = purple.
    private Color roleColor() {
        if (role.equals("WAITER")) return new Color(0, 121, 121);
        if (role.equals("KITCHEN")) return new Color(176, 38, 38);
        return new Color(103, 58, 160);
    }

    // Button that switches the table between active orders and history.
    private JButton viewButton(String label, boolean history) {
        JButton button = new JButton(label);
        button.addActionListener(e -> {
            showingHistory = history;
            refresh();
        });
        return button;
    }

    private Integer selectedOrderId() {
        int row = table.getSelectedRow();
        return row == -1 ? null : Integer.parseInt(tableModel.getValueAt(row, 0).toString());
    }

    // ---------------------------------------------------------------
    // Talking to the server
    // ---------------------------------------------------------------

    // Send a command and remember what kind of answer to expect.
    private void send(String command, String kind) {
        pending.add(kind);
        output.println(command);
        if (output.checkError()) addLog("Could not send command. Server may be down.");
    }

    // Ask the server for the latest orders for the current view.
    private void refresh() {
        if (showingHistory) send("HISTORY", "TABLE");
        else send("LIST", "TABLE");
    }

    // Background thread that reads everything the server sends.
    private void startReceiver() {
        Thread receiver = new Thread(() -> {
            try {
                String line;
                while ((line = input.readLine()) != null) {
                    final String message = line;
                    SwingUtilities.invokeLater(() -> handle(message));
                }
                SwingUtilities.invokeLater(() -> lostConnection("Server closed the connection."));
            } catch (IOException e) {
                if (!socket.isClosed())
                    SwingUtilities.invokeLater(() -> lostConnection(e.getMessage()));
            }
        });
        receiver.setDaemon(true);
        receiver.start();
    }

    // Runs on the Swing thread for every line from the server.
    private void handle(String line) {
        if (line.startsWith("EVENT|")) {
            String event = line.substring(6);
            addLog("[NOTIFICATION] " + event);

            Matcher match = ASSIGNED_PATTERN.matcher(event);
            if (role.equals("KITCHEN") && match.find()) {
                setAssigned(Integer.parseInt(match.group(1)));
            }

            // Something changed on the server, so reload the table.
            refresh();
            return;
        }

        if (!line.startsWith("RESPONSE|")) {
            addLog(line);
            return;
        }

        String response = line.substring(9);
        String kind = pending.poll();
        if (kind == null) kind = "ACTION";

        switch (kind) {
            case "TABLE":
                fillTable(response);
                break;
            case "SUMMARY":
                showSummary(response);
                break;
            default:
                addLog(response);
                if (role.equals("KITCHEN") && response.startsWith("Order ready:")) {
                    setAssigned(-1);
                }
                // An action may have changed orders; reload the table.
                refresh();
        }
    }

    // Put every order found in the server text into the table.
    private void fillTable(String response) {
        tableTitle.setText(showingHistory ? "Served history" : "Active orders");
        tableModel.setRowCount(0);

        // A kitchen worker sees its own orders plus the shared queue.
        // Orders being cooked by a different kitchen are hidden.
        String myKitchen = role.equals("KITCHEN") ? title.substring("KITCHEN ".length()) : null;

        List<Object[]> rows = new ArrayList<>();
        Matcher match = ORDER_PATTERN.matcher(response);
        while (match.find()) {
            String kitchen = match.group(5) == null ? "" : match.group(5);
            if (myKitchen != null && !kitchen.isEmpty() && !kitchen.equals(myKitchen)) continue;
            rows.add(new Object[]{
                    match.group(1), match.group(2), match.group(3), match.group(4), kitchen});
        }
        for (Object[] row : rows) tableModel.addRow(row);

        if (myKitchen != null) {
            tableTitle.setText("My orders (" + myKitchen + ") + queue"
                    + (rows.isEmpty() ? "  -  no orders" : ""));
        } else if (rows.isEmpty()) {
            tableTitle.setText(tableTitle.getText() + "  -  " + response);
        }
    }

    private void showSummary(String response) {
        String text = response.startsWith("SUMMARY | ")
                ? response.substring(10).replace(" | ", "\n")
                : response;
        addLog("[SUMMARY] " + text.replace("\n", ", "));
        JOptionPane.showMessageDialog(frame, text, "Restaurant Summary", JOptionPane.INFORMATION_MESSAGE);
    }

    private void setAssigned(int id) {
        assignedOrderId = id;
        if (assignedLabel == null) return;
        assignedLabel.setText(id == -1 ? "No order assigned" : "Cooking: Order #" + id);
        doneButton.setEnabled(id != -1);
    }

    private void addLog(String text) {
        log.append(text + "\n");
        log.setCaretPosition(log.getDocument().getLength());
    }

    private void lostConnection(String reason) {
        if (closed) return;
        addLog("Disconnected: " + reason);
        JOptionPane.showMessageDialog(frame, title + " lost connection to server.\n" + reason,
                "Disconnected", JOptionPane.WARNING_MESSAGE);
        closeWindow();
    }

    private void disconnect() {
        if (closed) return;
        try {
            output.println("EXIT");
            socket.close();
        } catch (IOException ignored) {
        }
        closeWindow();
    }

    // Close only this user's window. Quit when every window is closed.
    private void closeWindow() {
        closed = true;
        refreshTimer.stop();
        frame.dispose();
        openWindows--;
        if (openWindows <= 0) System.exit(0);
    }

    // Colors the Status column.
    private static class StatusRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected,
                                                       boolean focus, int row, int col) {
            Component c = super.getTableCellRendererComponent(t, value, selected, focus, row, col);
            setFont(getFont().deriveFont(Font.BOLD));
            if (!selected) {
                String s = String.valueOf(value);
                c.setForeground(s.equals("QUEUED") ? QUEUED
                        : s.equals("IN_PROGRESS") ? IN_PROGRESS
                        : s.equals("READY") ? READY : SERVED);
            }
            return c;
        }
    }
}
