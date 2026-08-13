package com.remoteboxjava;

import com.formdev.flatlaf.FlatPropertiesLaf;
import com.remoteboxjava.VBoxManageClient.VBoxException;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.SpinnerNumberModel;
import javax.swing.JTree;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Taskbar;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Java Swing RemoteBox-style VirtualBox client.
 *
 * <p>The client uses VBoxManage as its transport. Set the connection command
 * to a local executable (the default) or a remote prefix such as
 * {@code ssh user@example.com VBoxManage}.</p>
 */
public final class RemoteBoxApplication extends JFrame {
    private static final String APP_NAME = "RemoteBox Java";
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String[] TRANSPORT_LABELS = {"RemoteBox Web Service", "Local / SSH VBoxManage"};
    private static final int MAX_LOG_LINES = 2_000;

    private final ApplicationSettings preferences = ApplicationSettings.shared();
    private final ConnectionProfiles profiles = new ConnectionProfiles(preferences);
    private final GuestTreeModel machineModel = new GuestTreeModel();
    private final JTree machineTree = new JTree(machineModel);
    private final JTextArea detailsArea = new JTextArea();
    private final JTextArea logArea = new JTextArea();
    private final JTextArea snapshotsArea = new JTextArea();
    private final JCheckBox extendedDetails = new JCheckBox("Show Extended Info");
    private final JLabel connectionStatus = new JLabel("Disconnected");
    private final JLabel machineCountLabel = new JLabel("No guests");
    private final JProgressBar serverMemoryBar = new JProgressBar(0, 100);
    private final Timer refreshTimer;

    private JButton startButton;
    private JButton stopButton;
    private JButton pauseButton;
    private JButton resetButton;
    private JButton displayButton;
    private JButton settingsButton;
    private JMenu machineMenu;
    private VirtualBoxClient client;
    private boolean busy;
    private long connectionGeneration;
    private long snapshotPreviewGeneration;
    private long hostMemoryGeneration;
    private long machineRefreshGeneration;
    private boolean machineRefreshRunning;

    public static void main(String[] args) {
        // The first run may probe WSL for a RemoteBox installation, so load before the UI starts.
        ApplicationSettings.shared();
        applyTaskbarIcon();
        SwingUtilities.invokeLater(() -> {
            installLookAndFeel();
            RemoteBoxApplication app = new RemoteBoxApplication();
            app.setVisible(true);
        });
    }

    /** macOS and most Linux desktops take the dock icon from the Taskbar API, not from the window. */
    private static void applyTaskbarIcon() {
        try {
            if (Taskbar.isTaskbarSupported()) {
                Taskbar taskbar = Taskbar.getTaskbar();
                if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                    taskbar.setIconImage(AppIcon.render(256));
                }
            }
        } catch (UnsupportedOperationException | SecurityException ignored) {
            // The window icon remains the fallback.
        }
    }

    public RemoteBoxApplication() {
        super("RemoteBox");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setIconImages(AppIcon.windowIcons());
        setMinimumSize(new Dimension(940, 630));
        setSize(1100, 740);
        setLocationByPlatform(true);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                disconnect();
            }

            @Override
            public void windowClosed(WindowEvent event) {
                disconnect();
            }

            @Override
            public void windowGainedFocus(WindowEvent event) {
                refreshAll();
            }
        });

        setJMenuBar(createMenuBar());
        add(createToolBar(), BorderLayout.NORTH);
        add(createContent(), BorderLayout.CENTER);
        add(createStatusBar(), BorderLayout.SOUTH);
        profiles.migrateSingleConnection();
        MstscSecurityPrompt.setLogger(message -> SwingUtilities.invokeLater(() -> appendLog(message)));

        machineTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        machineTree.addTreeSelectionListener(event -> updateSelection());
        machineTree.setCellRenderer(new GuestTreeRenderer());
        machineTree.setRootVisible(false);
        machineTree.setShowsRootHandles(true);
        machineTree.setRowHeight(42);
        machineTree.setBackground(MiraDarkTheme.BACKGROUND);
        machineTree.setOpaque(true);
        machineTree.setToggleClickCount(2);

        detailsArea.setEditable(false);
        detailsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        detailsArea.setBorder(new EmptyBorder(8, 10, 8, 10));
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setRows(6);

        refreshTimer = new Timer(preferences.getInt("refresh.seconds", 30) * 1_000, event -> refreshAll());
        appendLog("Welcome to RemoteBox.");
        appendLog("Settings: " + preferences.location());
        if (!preferences.importReport().summary().isBlank()) {
            appendLog(preferences.importReport().summary());
        }
        updateActionState();
        connectStartupProfile();
        SwingUtilities.invokeLater(this::showImportNotice);
    }

    /**
     * Tells the user once that the settings of the forked RemoteBox client were
     * adopted, so the pre-filled connection details are not a surprise.
     */
    private void showImportNotice() {
        ApplicationSettings.ImportReport report = preferences.importReport();
        if (!report.fromRemoteBox()) {
            return;
        }
        StringBuilder message = new StringBuilder("<html><body style='width:420px'><b>Settings imported</b><br><br>")
                .append("No settings existed yet, so they were imported from your RemoteBox installation at<br>")
                .append("<code>").append(escapeHtml(report.origin())).append("</code>.<br><br>");
        if (!report.details().isEmpty()) {
            message.append("Imported values:<ul>");
            for (String detail : report.details()) {
                message.append("<li>").append(escapeHtml(detail)).append("</li>");
            }
            message.append("</ul>");
        }
        message.append("They are now stored in<br><code>").append(escapeHtml(preferences.location().toString()))
                .append("</code><br><br>and can be changed in File → Preferences. RemoteBox itself is not modified.")
                .append("</body></html>");
        JOptionPane.showMessageDialog(this, new JLabel(message.toString()),
                "Settings Imported — " + APP_NAME, JOptionPane.INFORMATION_MESSAGE);
    }

    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        JMenu file = new JMenu("File");
        file.add(menuItem("Connect…", event -> showConnectionDialog()));
        file.add(menuItem("Connection Profiles…", event -> showProfilesDialog()));
        file.add(menuItem("Preferences…", event -> showPreferencesDialog()));
        file.add(menuItem("Save Message Log…", event -> saveMessageLog()));
        file.addSeparator();
        file.add(menuItem("Import Appliance…", event -> importAppliance()));
        file.add(menuItem("Export Appliance…", event -> exportAppliance()));
        file.add(menuItem("Virtual Media Manager…", event -> showMediaManager()));
        file.add(menuItem("Host Network Manager…", event -> showHostNetworkManager()));
        file.addSeparator();
        file.add(menuItem("Disconnect", event -> disconnect()));
        file.add(menuItem("Exit", event -> dispose()));
        menuBar.add(file);

        JMenu guest = new JMenu("Machine");
        guest.add(menuItem("New guest…", event -> showNewGuestDialog()));
        guest.add(menuItem("Settings…", event -> showMachineSettingsDialog()));
        guest.add(menuItem("Clone guest…", event -> cloneSelectedMachine()));
        guest.add(menuItem("Set group…", event -> setMachineGroup()));
        guest.add(menuItem("Guest logs…", event -> showGuestLogs()));
        guest.add(menuItem("Remove guest…", event -> removeSelectedMachine()));
        guest.addSeparator();
        guest.add(menuItem("Start", event -> runMachineAction("Starting", VirtualBoxClient::start)));
        guest.add(menuItem("Power off", event -> powerOffSelected()));
        guest.add(menuItem("ACPI shutdown", event -> runMachineAction("Sending ACPI shutdown signal", VirtualBoxClient::acpiShutdown)));
        guest.add(menuItem("Save state", event -> runMachineAction("Saving state for", VirtualBoxClient::saveState)));
        guest.add(menuItem("Discard saved state", event -> runMachineAction("Discarding saved state for", VirtualBoxClient::discardSavedState)));
        guest.addSeparator();
        guest.add(menuItem("Pause/Resume", event -> pauseOrResume()));
        guest.add(menuItem("Reset", event -> resetSelected()));
        guest.addSeparator();
        guest.add(menuItem("Open display", event -> openDisplay()));
        machineMenu = guest;
        menuBar.add(guest);

        JMenu action = new JMenu("Action");
        action.add(menuItem("Refresh", event -> refreshAllReportingErrors()));
        action.add(menuItem("Server information…", event -> showServerInformation()));
        action.addSeparator();
        action.add(menuItem("Take snapshot…", event -> takeSnapshot()));
        action.add(menuItem("Snapshot details…", event -> showSnapshotDetails()));
        action.add(menuItem("Restore snapshot…", event -> manageSnapshot(false)));
        action.add(menuItem("Delete snapshot…", event -> manageSnapshot(true)));
        menuBar.add(action);

        JMenu devices = new JMenu("Devices");
        devices.add(menuItem("Guest Display", event -> openDisplay()));
        devices.add(menuItem("Send Ctrl-Alt-Del", event -> sendCtrlAltDelete()));
        devices.add(menuItem("Save Screenshot…", event -> saveScreenshot()));
        menuBar.add(devices);

        JMenu help = new JMenu("Help");
        help.add(menuItem("About", event -> showAbout()));
        menuBar.add(help);
        return menuBar;
    }

    private JToolBar createToolBar() {
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setBorder(new EmptyBorder(4, 10, 3, 10));
        toolBar.setBackground(MiraDarkTheme.PANEL_BACKGROUND);

        toolBar.add(remoteBoxToolButton("Connect", "connect_32px.png", event -> showConnectionDialog()));
        toolBar.add(remoteBoxToolButton("New", "vm_new_32px.png", event -> showNewGuestDialog()));
        settingsButton = remoteBoxToolButton("Settings", "vm_settings_32px.png", event -> showMachineSettingsDialog());
        toolBar.add(settingsButton);
        toolBar.addSeparator(new Dimension(18, 38));

        startButton = remoteBoxToolButton("Start", "vm_start_32px.png",
                event -> runMachineAction("Starting", VirtualBoxClient::start));
        stopButton = remoteBoxToolButton("Stop", "stop_32px.png", event -> powerOffSelected());
        pauseButton = remoteBoxToolButton("Pause", "keyboard_32px.png", event -> pauseOrResume());
        JButton discardButton = remoteBoxToolButton("Discard", "vm_discard_32px.png",
                event -> runMachineAction("Discarding saved state for", VirtualBoxClient::discardSavedState));
        resetButton = remoteBoxToolButton("Reset", "reset_32px.png", event -> resetSelected());
        toolBar.add(startButton);
        toolBar.add(stopButton);
        toolBar.add(pauseButton);
        toolBar.add(discardButton);
        toolBar.add(resetButton);
        toolBar.addSeparator(new Dimension(18, 38));

        displayButton = remoteBoxToolButton("Guest Display", "vrdp_32px.png", event -> openDisplay());
        toolBar.add(displayButton);
        toolBar.add(remoteBoxToolButton("Ctrl-Alt-Del", "keyboard_32px.png",
                event -> sendCtrlAltDelete()));
        toolBar.addSeparator(new Dimension(18, 38));
        toolBar.add(remoteBoxToolButton("Refresh", "refresh_32px.png", event -> refreshAllReportingErrors()));
        return toolBar;
    }

    private JPanel createContent() {
        JScrollPane guestScrollPane = darkScrollPane(machineTree);
        JTabbedPane leftTabs = darkTabbedPane();
        leftTabs.addTab("Guests", icon("machine_16px.png", 16), guestScrollPane);
        leftTabs.addTab("Message Log", icon("show_logs_16px.png", 16), darkScrollPane(logArea));

        JPanel infoPanel = new JPanel(new BorderLayout(0, 5));
        infoPanel.setBorder(new EmptyBorder(8, 10, 8, 10));
        extendedDetails.setSelected(preferences.getBoolean("details.extended", false));
        extendedDetails.addActionListener(event -> {
            preferences.putBoolean("details.extended", extendedDetails.isSelected());
            updateSelection();
        });
        infoPanel.add(extendedDetails, BorderLayout.NORTH);
        infoPanel.add(darkScrollPane(detailsArea), BorderLayout.CENTER);

        JTabbedPane rightTabs = darkTabbedPane();
        rightTabs.addTab("Guest Info", icon("rb_settings_16px.png", 16), infoPanel);
        snapshotsArea.setEditable(false);
        snapshotsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        snapshotsArea.setText("Select a guest to view its snapshots.");
        snapshotsArea.setBorder(new EmptyBorder(10, 10, 10, 10));
        rightTabs.addTab("Snapshots", icon("snapshot_manager_16px.png", 16), darkScrollPane(snapshotsArea));

        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftTabs, rightTabs);
        mainSplit.setResizeWeight(0.50);
        mainSplit.setDividerLocation(570);
        mainSplit.setBorder(BorderFactory.createEmptyBorder());

        JPanel root = new JPanel(new BorderLayout());
        serverMemoryBar.setStringPainted(true);
        serverMemoryBar.setBackground(Color.BLACK);
        // Square corners and no border let the track run edge to edge.
        serverMemoryBar.putClientProperty("JProgressBar.square", true);
        serverMemoryBar.setBorder(BorderFactory.createEmptyBorder());
        serverMemoryBar.setPreferredSize(new Dimension(0, 22));
        showHostMemory(null);
        root.add(serverMemoryBar, BorderLayout.NORTH);
        root.add(mainSplit, BorderLayout.CENTER);
        return root;
    }

    private JPanel createStatusBar() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, MiraDarkTheme.BORDER_COLOR),
                new EmptyBorder(5, 10, 5, 10)
        ));
        panel.add(connectionStatus, BorderLayout.WEST);
        panel.add(machineCountLabel, BorderLayout.EAST);
        return panel;
    }

    private void connectStartupProfile() {
        Optional<ConnectionProfile> autoConnect = profiles.autoConnectProfile();
        if (autoConnect.isEmpty()) {
            appendLog("No profile is set to connect at startup.");
            connectionStatus.setText("Disconnected — choose Connection ▸ Connect");
            return;
        }

        ConnectionProfile profile = autoConnect.get();
        profiles.rememberSelection(profile);
        if (!profile.webService()) {
            appendLog("Connecting to profile '" + profile.name() + "'.");
            connectUsing(profile, new char[0], false);
            return;
        }

        // Web-service profiles need a password; reuse RemoteBox's stored one when it fits.
        connectionStatus.setText("Connecting to '" + profile.name() + "'…");
        runBackground("Loading the RemoteBox password", () -> {
            try {
                return RemoteBoxProfileReader.loadAutoConnectProfile();
            } catch (VBoxException exception) {
                return Optional.<RemoteBoxProfileReader.Profile>empty();
            }
        }, stored -> {
            if (stored.isEmpty() || !stored.get().endpoint().equals(profile.endpoint())) {
                appendLog("No saved password for '" + profile.name() + "'.");
                connectionStatus.setText("Profile '" + profile.name() + "' — click Connect and enter the password");
                return;
            }
            RemoteBoxProfileReader.Profile remoteBoxProfile = stored.get();
            char[] password = remoteBoxProfile.password();
            remoteBoxProfile.clearPassword();
            appendLog("Connecting to profile '" + profile.name() + "' with the RemoteBox password.");
            SwingUtilities.invokeLater(() -> connectUsing(profile, password, false));
        });
    }

    private void connect(String transport, String command, String endpoint, String username, char[] password,
                         boolean showSuccessMessage) {
        long generation = ++connectionGeneration;
        runBackground("Connecting to VirtualBox", () -> {
            VirtualBoxClient connectedClient = null;
            try {
                connectedClient = "web-service".equals(transport)
                        ? new VirtualBoxWebServiceClient(endpoint, username, password)
                        : new VBoxManageClient(command);
                return new ConnectedClient(connectedClient, connectedClient.version(), transport, endpoint, command);
            } catch (Exception exception) {
                if (connectedClient != null) {
                    try {
                        connectedClient.close();
                    } catch (Exception ignored) {
                        // Preserve the original connection failure.
                    }
                }
                throw exception;
            } finally {
                Arrays.fill(password, '\0');
            }
        }, connection -> {
            if (generation != connectionGeneration) {
                try {
                    connection.client().close();
                } catch (VBoxException ignored) {
                    // A cancelled connection must not replace a newer one.
                }
                return;
            }
            client = connection.client();
            connectionStatus.setText("Connected — VirtualBox " + connection.version());
            refreshTimer.start();
            appendLog("Connected using: " + connection.description());
            if (showSuccessMessage) {
                JOptionPane.showMessageDialog(this, "Connected to VirtualBox " + connection.version() + ".", APP_NAME,
                        JOptionPane.INFORMATION_MESSAGE);
            }
            refreshMachines();
            refreshHostMemory();
        });
    }
    private void disconnect() {
        refreshTimer.stop();
        connectionGeneration++;
        snapshotPreviewGeneration++;
        hostMemoryGeneration++;
        machineRefreshGeneration++;
        VirtualBoxClient previousClient = client;
        client = null;
        if (previousClient == null) {
            showHostMemory(null);
            updateActionState();
            return;
        }

        /*
         * Closing logs off the web service, which can block for the full request
         * timeout when the server is gone. Never do that on the event thread.
         */
        Thread closer = new Thread(() -> {
            try {
                previousClient.close();
            } catch (Exception exception) {
                SwingUtilities.invokeLater(
                        () -> appendLog("Disconnect cleanup failed: " + exception.getMessage()));
            }
        }, "RemoteBox-disconnect");
        closer.setDaemon(true);
        closer.start();

        appendLog("Disconnected.");
        machineModel.setMachines(List.of());
        machineTree.clearSelection();
        machineTree.expandRow(0);
        detailsArea.setText("");
        snapshotsArea.setText("Select a guest to view its snapshots.");
        connectionStatus.setText("Disconnected");
        machineCountLabel.setText("No guests");
        showHostMemory(null);
        updateActionState();
    }

    /**
     * Reads the host memory on its own worker so the periodic update is not
     * suppressed by a long-running guest operation.
     */
    private void refreshHostMemory() {
        VirtualBoxClient memoryClient = client;
        if (memoryClient == null) {
            showHostMemory(null);
            return;
        }
        long generation = ++hostMemoryGeneration;
        new SwingWorker<HostMemory, Void>() {
            @Override
            protected HostMemory doInBackground() throws Exception {
                return memoryClient.hostMemory();
            }

            @Override
            protected void done() {
                if (generation != hostMemoryGeneration || client != memoryClient) {
                    return;
                }
                try {
                    showHostMemory(get());
                } catch (Exception exception) {
                    serverMemoryBar.setValue(0);
                    serverMemoryBar.setString("Server Memory — unavailable");
                    serverMemoryBar.setToolTipText(errorMessage(exception));
                }
            }
        }.execute();
    }

    private void showHostMemory(HostMemory memory) {
        if (memory == null || memory.totalMb() <= 0) {
            serverMemoryBar.setValue(0);
            serverMemoryBar.setString("Server Memory — not connected");
            serverMemoryBar.setToolTipText(null);
            return;
        }
        serverMemoryBar.setValue(memory.usedPercent());
        serverMemoryBar.setString(memory.describe());
        serverMemoryBar.setToolTipText(memory.describeExactly());
    }

    private void refreshAll() {
        refreshMachines(false);
        refreshHostMemory();
    }

    private void refreshAllReportingErrors() {
        if (!requireConnection()) {
            return;
        }
        refreshMachines(true);
        refreshHostMemory();
    }

    /**
     * Reloads the guest list on its own worker. It deliberately does not take the
     * {@code busy} lock used by user-initiated actions: the periodic refresh must
     * never disable the toolbar or swallow a click, and a slow server must never
     * make the window feel frozen.
     *
     * @param reportErrors whether a failure is worth a dialog, which the periodic
     *                     refresh must not raise every interval
     */
    private void refreshMachines(boolean reportErrors) {
        VirtualBoxClient refreshingClient = client;
        if (refreshingClient == null || machineRefreshRunning) {
            return;
        }
        String selectedId = selectedMachine() == null ? null : selectedMachine().id();
        long generation = ++machineRefreshGeneration;
        machineRefreshRunning = true;

        // Only announce the refresh when the server is slow enough to notice.
        Timer indicator = new Timer(300, event -> machineCountLabel.setText("Refreshing…"));
        indicator.setRepeats(false);
        indicator.start();

        new SwingWorker<List<VirtualMachine>, Void>() {
            @Override
            protected List<VirtualMachine> doInBackground() throws Exception {
                return refreshingClient.listMachines();
            }

            @Override
            protected void done() {
                indicator.stop();
                machineRefreshRunning = false;
                if (generation != machineRefreshGeneration || client != refreshingClient) {
                    return;
                }
                try {
                    List<VirtualMachine> machines = get();
                    machineModel.setMachines(machines);
                    expandGuestFolders();
                    restoreSelection(selectedId);
                    machineCountLabel.setText(machines.size() + (machines.size() == 1 ? " guest" : " guests"));
                    updateSelection();
                    if (reportErrors) {
                        appendLog("Guest list refreshed (" + machines.size() + " guests).");
                    }
                } catch (Exception exception) {
                    Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                    machineCountLabel.setText("Refresh failed");
                    appendLog("Refreshing the guest list failed: " + cause.getMessage());
                    if (reportErrors) {
                        showError("Refreshing the guest list failed.\n\n" + cause.getMessage());
                    }
                }
            }
        }.execute();
    }

    private void refreshMachines() {
        refreshMachines(false);
    }

    private void showConnectionDialog() {
        List<ConnectionProfile> saved = profiles.all();
        ConnectionProfile initial = profiles.selected();

        JComboBox<ConnectionProfile> profileBox = new JComboBox<>(saved.toArray(ConnectionProfile[]::new));
        profileBox.setEnabled(!saved.isEmpty());
        JComboBox<String> transport = new JComboBox<>(TRANSPORT_LABELS);
        JLabel addressLabel = new JLabel();
        JTextField address = new JTextField(32);
        JTextField username = new JTextField(20);
        JPasswordField password = new JPasswordField(20);
        JButton manage = new JButton("Manage Profiles…");

        ConnectionEditor editor = new ConnectionEditor(transport, addressLabel, address, username);
        editor.show(initial);
        saved.stream()
                .filter(profile -> profile.name().equals(initial.name()))
                .findFirst()
                .ifPresent(profileBox::setSelectedItem);
        profileBox.addActionListener(event -> {
            if (profileBox.getSelectedItem() instanceof ConnectionProfile profile) {
                editor.show(profile);
            }
        });

        JPanel content = formPanel(
                new JLabel[]{new JLabel("Profile:"), new JLabel("Transport:"), addressLabel, new JLabel("Username:"),
                        new JLabel("Password:"), new JLabel(), new JLabel()},
                new Component[]{profileBox, transport, address, username, password,
                        formHelpText("The password is used for this connection only and is never saved."), manage}
        );

        JOptionPane pane = new JOptionPane(content, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        JDialog dialog = pane.createDialog(this, "Connect to VirtualBox");
        manage.addActionListener(event -> {
            dialog.dispose();
            SwingUtilities.invokeLater(this::showProfilesDialog);
        });
        try {
            dialog.setVisible(true);
        } finally {
            dialog.dispose();
        }
        if (!Objects.equals(pane.getValue(), JOptionPane.OK_OPTION)) {
            return;
        }

        ConnectionProfile connection = editor.current();
        if (connection.address().isBlank() || (connection.webService() && connection.username().isBlank())) {
            showError(connection.webService()
                    ? "Enter the server URL and username."
                    : "Enter a VBoxManage command.");
            return;
        }
        profiles.rememberSelection(connection);
        connectUsing(connection, password.getPassword(), true);
    }

    private void connectUsing(ConnectionProfile profile, char[] password, boolean showSuccessMessage) {
        disconnect();
        connect(profile.transport(), profile.command(), profile.endpoint(), profile.username(), password,
                showSuccessMessage);
    }

    /**
     * Keeps the transport selector and the single address row in sync: each
     * transport has its own address, so switching back and forth is lossless.
     */
    private static final class ConnectionEditor {
        private final JComboBox<String> transport;
        private final JLabel addressLabel;
        private final JTextField address;
        private final JTextField username;
        private ConnectionProfile profile;
        private boolean populating;

        private ConnectionEditor(JComboBox<String> transport, JLabel addressLabel, JTextField address,
                                 JTextField username) {
            this.transport = transport;
            this.addressLabel = addressLabel;
            this.address = address;
            this.username = username;
            transport.addActionListener(event -> {
                if (!populating) {
                    capture();
                    profile = profile.withTransport(selectedTransport());
                    show(profile);
                }
            });
        }

        private void show(ConnectionProfile shown) {
            populating = true;
            try {
                profile = shown;
                transport.setSelectedIndex(shown.webService() ? 0 : 1);
                addressLabel.setText(shown.addressLabel() + ":");
                address.setText(shown.address());
                username.setText(shown.username());
                username.setEnabled(shown.webService());
            } finally {
                populating = false;
            }
        }

        private void capture() {
            profile = profile.withAddress(address.getText().trim()).withUsername(username.getText().trim());
        }

        private ConnectionProfile current() {
            capture();
            return profile;
        }

        private String selectedTransport() {
            return transport.getSelectedIndex() == 0 ? ConnectionProfile.WEB_SERVICE : ConnectionProfile.COMMAND;
        }
    }

    private void showProfilesDialog() {
        DefaultListModel<ConnectionProfile> model = new DefaultListModel<>();
        profiles.all().forEach(model::addElement);
        JList<ConnectionProfile> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(10);

        JTextField name = new JTextField(20);
        JComboBox<String> transport = new JComboBox<>(TRANSPORT_LABELS);
        JLabel addressLabel = new JLabel();
        JTextField address = new JTextField(30);
        JTextField username = new JTextField(20);
        JCheckBox autoConnect = new JCheckBox("Connect to this profile when RemoteBox starts");

        ProfileListEditor editor = new ProfileListEditor(model, list, name, transport, addressLabel, address,
                username, autoConnect, profiles.autoConnectName());

        JButton add = new JButton("New");
        JButton duplicate = new JButton("Duplicate");
        JButton remove = new JButton("Delete");
        add.addActionListener(event -> editor.add());
        duplicate.addActionListener(event -> editor.duplicate());
        remove.addActionListener(event -> editor.remove());

        // A grid keeps every button visible; a flow row would clip Delete off the panel.
        JPanel listButtons = new JPanel(new GridLayout(1, 3, 4, 0));
        listButtons.add(add);
        listButtons.add(duplicate);
        listButtons.add(remove);

        JScrollPane listScroller = darkScrollPane(list);
        listScroller.setPreferredSize(new Dimension(240, 260));

        JPanel listPanel = new JPanel(new BorderLayout(0, 8));
        listPanel.setBorder(BorderFactory.createTitledBorder("Profiles"));
        listPanel.add(listScroller, BorderLayout.CENTER);
        listPanel.add(listButtons, BorderLayout.SOUTH);

        JPanel details = formPanel(
                new JLabel[]{new JLabel("Name:"), new JLabel("Transport:"), addressLabel, new JLabel("Username:"),
                        new JLabel()},
                new Component[]{name, transport, address, username, autoConnect});
        details.setBorder(BorderFactory.createTitledBorder("Details"));

        JPanel content = new JPanel(new BorderLayout(12, 0));
        content.setBorder(new EmptyBorder(10, 10, 10, 10));
        content.add(listPanel, BorderLayout.WEST);
        content.add(details, BorderLayout.CENTER);

        Object[] options = {"Save", "Save and Connect", "Cancel"};
        int choice = JOptionPane.showOptionDialog(this, content, "Connection Profiles",
                JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
        if (choice != 0 && choice != 1) {
            return;
        }

        List<ConnectionProfile> updated = editor.profiles();
        try {
            profiles.replaceAll(updated, editor.autoConnectName());
        } catch (IllegalArgumentException exception) {
            showError(exception.getMessage());
            return;
        }
        appendLog("Saved " + updated.size() + " connection profile(s).");

        ConnectionProfile selected = editor.selectedProfile();
        if (selected != null) {
            profiles.rememberSelection(selected);
        }
        if (choice == 1) {
            if (selected == null) {
                showError("Select the profile to connect to.");
                return;
            }
            connectWithPasswordPrompt(selected);
        }
    }

    /**
     * Web-service profiles need a password, which is deliberately not stored.
     */
    private void connectWithPasswordPrompt(ConnectionProfile profile) {
        if (!profile.webService()) {
            connectUsing(profile, new char[0], true);
            return;
        }
        JPasswordField password = new JPasswordField(20);
        JPanel content = formPanel(new String[]{"Profile", "Server URL", "Username", "Password"},
                new Component[]{new JLabel(profile.name()), new JLabel(profile.endpoint()),
                        new JLabel(profile.username()), password});
        if (JOptionPane.showConfirmDialog(this, content, "Connect to " + profile.name(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        connectUsing(profile, password.getPassword(), true);
    }

    /**
     * Edits the profile list in place; the form always shows the selected entry
     * and writes back before the selection moves elsewhere.
     */
    private static final class ProfileListEditor {
        private final DefaultListModel<ConnectionProfile> model;
        private final JList<ConnectionProfile> list;
        private final JTextField name;
        private final JComboBox<String> transport;
        private final JLabel addressLabel;
        private final JTextField address;
        private final JTextField username;
        private final JCheckBox autoConnect;
        private String autoConnectName;
        private int shownIndex = -1;
        private boolean populating;

        private ProfileListEditor(DefaultListModel<ConnectionProfile> model, JList<ConnectionProfile> list,
                                  JTextField name, JComboBox<String> transport, JLabel addressLabel,
                                  JTextField address, JTextField username, JCheckBox autoConnect,
                                  String initialAutoConnectName) {
            this.model = model;
            this.list = list;
            this.name = name;
            this.transport = transport;
            this.addressLabel = addressLabel;
            this.address = address;
            this.username = username;
            this.autoConnect = autoConnect;
            this.autoConnectName = initialAutoConnectName;

            list.addListSelectionListener(event -> {
                if (!event.getValueIsAdjusting()) {
                    showSelection();
                }
            });
            transport.addActionListener(event -> {
                if (!populating && shownIndex >= 0) {
                    capture();
                    model.set(shownIndex, model.get(shownIndex).withTransport(selectedTransport()));
                    show(shownIndex);
                }
            });
            autoConnect.addActionListener(event -> {
                if (!populating && shownIndex >= 0) {
                    this.autoConnectName = autoConnect.isSelected() ? model.get(shownIndex).name() : "";
                }
            });
            if (!model.isEmpty()) {
                list.setSelectedIndex(0);
            } else {
                showSelection();
            }
        }

        private void add() {
            capture();
            model.addElement(ConnectionProfile.empty(uniqueName("New profile")));
            list.setSelectedIndex(model.size() - 1);
            name.requestFocusInWindow();
            name.selectAll();
        }

        private void duplicate() {
            capture();
            if (shownIndex < 0) {
                return;
            }
            ConnectionProfile source = model.get(shownIndex);
            model.addElement(source.withName(uniqueName(source.name() + " copy")));
            list.setSelectedIndex(model.size() - 1);
        }

        private void remove() {
            int index = list.getSelectedIndex();
            if (index < 0) {
                return;
            }
            if (model.get(index).name().equals(autoConnectName)) {
                autoConnectName = "";
            }
            shownIndex = -1;
            model.remove(index);
            list.setSelectedIndex(Math.min(index, model.size() - 1));
            showSelection();
        }

        private void showSelection() {
            capture();
            show(list.getSelectedIndex());
        }

        private void show(int index) {
            populating = true;
            try {
                shownIndex = index;
                boolean present = index >= 0 && index < model.size();
                ConnectionProfile profile = present ? model.get(index) : null;
                name.setText(present ? profile.name() : "");
                transport.setSelectedIndex(present && !profile.webService() ? 1 : 0);
                addressLabel.setText((present ? profile.addressLabel() : "Server URL") + ":");
                address.setText(present ? profile.address() : "");
                username.setText(present ? profile.username() : "");
                autoConnect.setSelected(present && profile.name().equals(autoConnectName));
                name.setEnabled(present);
                transport.setEnabled(present);
                address.setEnabled(present);
                username.setEnabled(present && profile.webService());
                autoConnect.setEnabled(present);
            } finally {
                populating = false;
            }
        }

        private void capture() {
            if (populating || shownIndex < 0 || shownIndex >= model.size()) {
                return;
            }
            ConnectionProfile original = model.get(shownIndex);
            try {
                ConnectionProfile updated = original
                        .withName(name.getText())
                        .withAddress(address.getText().trim())
                        .withUsername(username.getText().trim());
                if (original.name().equals(autoConnectName)) {
                    autoConnectName = updated.name();
                }
                model.set(shownIndex, updated);
            } catch (IllegalArgumentException ignored) {
                // Keep the stored profile while the entered name is still blank.
            }
        }

        private String uniqueName(String preferred) {
            String candidate = preferred;
            for (int suffix = 2; contains(candidate); suffix++) {
                candidate = preferred + " " + suffix;
            }
            return candidate;
        }

        private boolean contains(String profileName) {
            return java.util.Collections.list(model.elements()).stream()
                    .anyMatch(profile -> profile.name().equals(profileName));
        }

        private String selectedTransport() {
            return transport.getSelectedIndex() == 0 ? ConnectionProfile.WEB_SERVICE : ConnectionProfile.COMMAND;
        }

        private List<ConnectionProfile> profiles() {
            capture();
            return java.util.Collections.list(model.elements());
        }

        private ConnectionProfile selectedProfile() {
            capture();
            return shownIndex >= 0 && shownIndex < model.size() ? model.get(shownIndex) : null;
        }

        private String autoConnectName() {
            capture();
            return autoConnectName;
        }
    }

    private void saveMessageLog() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save RemoteBox Message Log");
        chooser.setSelectedFile(new File("remotebox-message-log.txt"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        Path destination = chooser.getSelectedFile().toPath().toAbsolutePath();
        if (Files.exists(destination)
                && JOptionPane.showConfirmDialog(this, "Replace existing file?\n" + destination,
                "Confirm Log Replacement", JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }

        try {
            Files.writeString(destination, logArea.getText(), java.nio.charset.StandardCharsets.UTF_8);
            appendLog("Saved message log to " + destination + ".");
        } catch (IOException exception) {
            showError("Could not save the message log to " + destination + ".\n\n" + exception.getMessage());
        }
    }

    private void showPreferencesDialog() {
        RemoteBoxProfileReader.DisplaySettings display = preferences.displaySettings();
        JTextField refreshSeconds = new JTextField(Integer.toString(preferences.getInt("refresh.seconds", 30)), 8);
        JCheckBox confirmActions = new JCheckBox("Confirm destructive guest actions",
                preferences.getBoolean("confirm.actions", true));
        String autoConnectName = profiles.autoConnectName();

        JPanel general = formPanel(
                new String[]{"Refresh interval (seconds)", "", "Startup profile", "Settings file"},
                new Component[]{refreshSeconds, confirmActions,
                        formHelpText(autoConnectName.isBlank()
                                ? "None — choose one in File ▸ Connection Profiles."
                                : autoConnectName),
                        formHelpText(preferences.location().toString())}
        );

        JRadioButton useMstsc = new JRadioButton("Microsoft Remote Desktop (mstsc)", display.useMstsc());
        JRadioButton useCommand = new JRadioButton("Custom command line", !display.useMstsc());
        ButtonGroup rdpMode = new ButtonGroup();
        rdpMode.add(useMstsc);
        rdpMode.add(useCommand);

        int screenScale = RdpConnectionFile.primaryScreenScalePercent();
        JCheckBox autoScale = new JCheckBox("Match the scale factor to the primary screen"
                + (screenScale > 0 ? " (currently " + screenScale + "%)" : ""), display.autoScale());
        JCheckBox shareClipboard = new JCheckBox("Share the clipboard with the guest — Windows then asks to "
                + "confirm the connection", display.shareClipboard());
        JTextField rdpClient = new JTextField(display.rdpClient(), 34);
        JTextField vncClient = new JTextField(display.vncClient(), 34);

        // mstsc is driven by a generated .rdp file, so its command line is unused.
        Runnable syncRdpMode = () -> {
            rdpClient.setEnabled(useCommand.isSelected());
            autoScale.setEnabled(useMstsc.isSelected());
            shareClipboard.setEnabled(useMstsc.isSelected());
        };
        useMstsc.addActionListener(event -> syncRdpMode.run());
        useCommand.addActionListener(event -> syncRdpMode.run());
        syncRdpMode.run();

        JPanel remoteDisplay = formPanel(
                new String[]{"RDP client", "", "", "", "RDP client command", "VNC client command", ""},
                new Component[]{useMstsc, indented(autoScale), indented(shareClipboard), useCommand,
                        rdpClient, vncClient,
                        formHelpText("Placeholders: %h host, %p port, %n guest name, %U user, %P password, "
                                + "%X/%Y/%D width, height, colour depth.")}
        );

        JTabbedPane tabs = darkTabbedPane();
        general.setBorder(new EmptyBorder(12, 12, 12, 12));
        remoteDisplay.setBorder(new EmptyBorder(12, 12, 12, 12));
        tabs.addTab("General", icon("rb_settings_16px.png", 16), general);
        tabs.addTab("Remote Display", icon("machine_16px.png", 16), remoteDisplay);

        if (!showDialog("RemoteBox Preferences", tabs)) {
            return;
        }
        try {
            int seconds = Integer.parseInt(refreshSeconds.getText().trim());
            if (seconds < 5) {
                throw new NumberFormatException();
            }
            preferences.putInt("refresh.seconds", seconds);
            preferences.putBoolean("confirm.actions", confirmActions.isSelected());
            preferences.putBoolean("display.useMstsc", useMstsc.isSelected());
            preferences.putBoolean("display.autoScale", autoScale.isSelected());
            preferences.putBoolean("display.shareClipboard", shareClipboard.isSelected());
            preferences.put("display.rdpClient", rdpClient.getText().trim());
            preferences.put("display.vncClient", vncClient.getText().trim());
            refreshTimer.setDelay(seconds * 1_000);
            appendLog("Preferences saved to " + preferences.location() + ".");
        } catch (NumberFormatException exception) {
            showError("Enter a refresh interval of at least five seconds.");
        }
    }

    /** Marks a control as belonging to the radio button above it. */
    private static JPanel indented(Component control) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.setBorder(new EmptyBorder(0, 20, 0, 0));
        panel.add(control, BorderLayout.WEST);
        return panel;
    }

    /**
     * A dialog whose content reaches the window edges, which {@link JOptionPane}
     * insets. Tabs then sit flush under the title bar.
     *
     * @return whether the user confirmed
     */
    private boolean showDialog(String title, Component content) {
        JDialog dialog = new JDialog(this, title, true);
        boolean[] accepted = {false};

        JButton ok = new JButton("OK");
        ok.addActionListener(event -> {
            accepted[0] = true;
            dialog.dispose();
        });
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(event -> dialog.dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setBorder(new EmptyBorder(8, 10, 10, 10));
        buttons.add(ok);
        buttons.add(cancel);

        dialog.getRootPane().setDefaultButton(ok);
        dialog.setLayout(new BorderLayout());
        dialog.add(content, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        try {
            dialog.setVisible(true);
        } finally {
            dialog.dispose();
        }
        return accepted[0];
    }

    private void showMediaManager() {
        if (!requireConnection()) {
            return;
        }
        if (!(client instanceof VBoxManageClient)) {
            runBackground("Loading virtual media", client::mediaInformation,
                    information -> showTextDialog("Virtual Media Manager — read-only SOAP view", information));
            return;
        }
        runBackground("Loading virtual media", client::virtualMedia, this::showMediaManagerEditor);
    }

    private void showMediaManagerEditor(List<VirtualMedia> media) {
        DefaultTableModel model = new DefaultTableModel(
                new String[]{"Type", "Location", "Format", "Capacity (MB)", "UUID"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        media.forEach(item -> model.addRow(new Object[]{item.type(), item.location(), item.format(),
                item.capacityMb(), item.id()}));
        JTable table = new JTable(model);
        table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);

        JButton create = new JButton("Create disk...");
        JButton resize = new JButton("Resize");
        JButton compact = new JButton("Compact");
        JButton release = new JButton("Release");
        JButton delete = new JButton("Delete");
        JButton register = new JButton("Register ISO/floppy...");
        JButton unregister = new JButton("Unregister ISO/floppy");
        JPanel actions = new JPanel();
        actions.add(create);
        actions.add(resize);
        actions.add(compact);
        actions.add(release);
        actions.add(delete);
        actions.add(register);
        actions.add(unregister);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(new EmptyBorder(16, 20, 16, 20));
        content.add(new JScrollPane(table), BorderLayout.CENTER);
        content.add(actions, BorderLayout.SOUTH);

        create.addActionListener(event -> showCreateVirtualDiskDialog());
        resize.addActionListener(event -> runSelectedMediaAction(table, model, "Resizing virtual disk", item -> {
            if (item.type() != VirtualMedia.Type.HARD_DISK) {
                throw new VBoxException("Only virtual hard disks can be resized.");
            }
            String value = JOptionPane.showInputDialog(this, "New disk capacity (MB):", item.capacityMb());
            if (value == null) {
                return;
            }
            int size = Integer.parseInt(value.trim());
            client.resizeVirtualDisk(item.location(), size);
        }));
        compact.addActionListener(event -> runSelectedMediaAction(table, model, "Compacting virtual disk", item -> {
            if (item.type() != VirtualMedia.Type.HARD_DISK) {
                throw new VBoxException("Only virtual hard disks can be compacted.");
            }
            client.compactVirtualDisk(item.location());
        }));
        release.addActionListener(event -> runSelectedMediaAction(table, model, "Releasing virtual disk", item -> {
            if (item.type() != VirtualMedia.Type.HARD_DISK) {
                throw new VBoxException("Only virtual hard disks can be released.");
            }
            client.releaseVirtualDisk(item.location());
        }));
        delete.addActionListener(event -> runSelectedMediaAction(table, model, "Deleting virtual disk", item -> {
            if (item.type() != VirtualMedia.Type.HARD_DISK) {
                throw new VBoxException("Only virtual hard disks can be deleted.");
            }
            if (!confirmDestructiveAction("Delete virtual disk '" + item.location() + "'?", "Confirm Disk Deletion")) {
                return;
            }
            client.closeVirtualDisk(item.location(), true);
        }));
        register.addActionListener(event -> showRegisterRemovableMediumDialog());
        unregister.addActionListener(event -> runSelectedMediaAction(table, model, "Unregistering removable medium", item -> {
            if (item.type() == VirtualMedia.Type.OPTICAL) {
                client.unregisterOpticalMedium(item.location());
            } else if (item.type() == VirtualMedia.Type.FLOPPY) {
                client.unregisterFloppyMedium(item.location());
            } else {
                throw new VBoxException("Select an ISO or floppy medium to unregister.");
            }
        }));
        JOptionPane.showMessageDialog(this, content, "Virtual Media Manager", JOptionPane.PLAIN_MESSAGE);
    }

    private void runSelectedMediaAction(JTable table, DefaultTableModel model, String task,
                                        MediaOperation operation) {
        int row = table.getSelectedRow();
        if (row < 0) {
            showError("Select a virtual medium first.");
            return;
        }
        VirtualMedia item = new VirtualMedia(Objects.toString(model.getValueAt(row, 4)),
                Objects.toString(model.getValueAt(row, 1)),
                VirtualMedia.Type.valueOf(Objects.toString(model.getValueAt(row, 0))),
                Objects.toString(model.getValueAt(row, 2)),
                ((Number) model.getValueAt(row, 3)).longValue(), List.of());
        runBackground(task, () -> {
            operation.run(item);
            return null;
        }, ignored -> showMediaManager());
    }

    private void showCreateVirtualDiskDialog() {
        JTextField path = new JTextField("disk.vdi", 28);
        JTextField size = new JTextField("20480", 8);
        JComboBox<String> format = new JComboBox<>(new String[]{"VDI", "VHD", "VMDK"});
        JCheckBox fixed = new JCheckBox("Allocate full size immediately");
        JPanel content = formPanel(new String[]{"Disk file", "Size (MB)", "Format", ""},
                new Component[]{path, size, format, fixed});
        if (JOptionPane.showConfirmDialog(this, content, "Create Virtual Disk",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            runBackground("Creating virtual disk", () -> client.createVirtualDisk(path.getText(),
                    Integer.parseInt(size.getText().trim()), Objects.toString(format.getSelectedItem()), fixed.isSelected()),
                    created -> showMediaManager());
        } catch (NumberFormatException exception) {
            showError("Disk size must be a whole number.");
        }
    }

    private void showRegisterRemovableMediumDialog() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Register ISO or Floppy Medium");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        boolean floppy = file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".img")
                || file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".ima");
        runBackground("Registering removable medium", () -> {
            if (floppy) {
                client.registerFloppyMedium(file.getAbsolutePath());
            } else {
                client.registerOpticalMedium(file.getAbsolutePath());
            }
            return null;
        }, ignored -> showMediaManager());
    }

    private void showHostNetworkManager() {
        if (!requireConnection()) {
            return;
        }
        if (!(client instanceof VBoxManageClient)) {
            runBackground("Loading host networks", client::hostNetworkInformation,
                    information -> showTextDialog("Host Network Manager — read-only SOAP view", information));
            return;
        }
        runBackground("Loading host networks", client::hostNetworkInterfaces, this::showHostNetworkManagerEditor);
    }

    private void showHostNetworkManagerEditor(List<HostNetworkInterface> interfaces) {
        DefaultTableModel model = new DefaultTableModel(
                new String[]{"Name", "Type", "IPv4 Address", "IPv4 Mask", "IPv6 Address", "DHCP"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        interfaces.forEach(network -> model.addRow(new Object[]{network.name(), network.type(), network.ipv4Address(),
                network.ipv4Mask(), network.ipv6Address(), network.dhcpEnabled() ? "Enabled" : "Disabled"}));
        JTable table = new JTable(model);
        table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        JButton create = new JButton("Create host-only");
        JButton configureIp = new JButton("Configure IP...");
        JButton configureDhcp = new JButton("Configure DHCP...");
        JButton remove = new JButton("Remove");
        JPanel actions = new JPanel();
        actions.add(create);
        actions.add(configureIp);
        actions.add(configureDhcp);
        actions.add(remove);
        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(new EmptyBorder(16, 20, 16, 20));
        content.add(new JScrollPane(table), BorderLayout.CENTER);
        content.add(actions, BorderLayout.SOUTH);

        create.addActionListener(event -> runBackground("Creating host-only interface", () -> {
            client.createHostOnlyInterface();
            return null;
        }, ignored -> showHostNetworkManager()));
        configureIp.addActionListener(event -> withSelectedHostOnlyNetwork(table, model,
                this::showConfigureHostOnlyInterfaceDialog));
        configureDhcp.addActionListener(event -> withSelectedHostOnlyNetwork(table, model,
                this::showConfigureHostOnlyDhcpDialog));
        remove.addActionListener(event -> withSelectedHostOnlyNetwork(table, model, name -> {
            if (!confirmDestructiveAction("Remove host-only interface '" + name + "'?", "Confirm Interface Removal")) {
                return;
            }
            runBackground("Removing host-only interface", () -> {
                client.removeHostOnlyInterface(name);
                return null;
            }, ignored -> showHostNetworkManager());
        }));
        JOptionPane.showMessageDialog(this, content, "Host Network Manager", JOptionPane.PLAIN_MESSAGE);
    }

    private void withSelectedHostOnlyNetwork(JTable table, DefaultTableModel model,
                                             java.util.function.Consumer<String> action) {
        int row = table.getSelectedRow();
        if (row < 0) {
            showError("Select a host-only interface first.");
            return;
        }
        if (!"host-only".equals(model.getValueAt(row, 1))) {
            showError("Only host-only interfaces can be configured or removed.");
            return;
        }
        action.accept(Objects.toString(model.getValueAt(row, 0)));
    }

    private void showRemoveHostOnlyInterfaceDialog() {
        String name = JOptionPane.showInputDialog(this, "Host-only interface name:", "Remove Host-only Interface",
                JOptionPane.WARNING_MESSAGE);
        if (name == null || name.isBlank()) {
            return;
        }
        runBackground("Removing host-only interface", () -> {
            client.removeHostOnlyInterface(name);
            return null;
        }, ignored -> appendLog("Removed host-only interface " + name.trim() + "."));
    }

    private void showConfigureHostOnlyInterfaceDialog(String interfaceName) {
        JTextField name = new JTextField(interfaceName, 20);
        name.setEditable(false);
        JTextField address = new JTextField("192.168.56.1", 16);
        JTextField mask = new JTextField("255.255.255.0", 16);
        JTextField ipv6 = new JTextField(20);
        JTextField prefix = new JTextField("64", 5);
        JPanel panel = formPanel(new String[]{"Interface", "IPv4 address", "IPv4 mask", "IPv6 address (optional)",
                "IPv6 prefix"}, new Component[]{name, address, mask, ipv6, prefix});
        if (JOptionPane.showConfirmDialog(this, panel, "Configure Host-only Interface",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            int ipv6Prefix = Integer.parseInt(prefix.getText().trim());
            runBackground("Configuring host-only interface", () -> {
                client.configureHostOnlyInterface(name.getText(), address.getText(), mask.getText(), ipv6.getText(), ipv6Prefix);
                return null;
            }, ignored -> appendLog("Configured host-only interface " + name.getText().trim() + "."));
        } catch (NumberFormatException exception) {
            showError("IPv6 prefix length must be a whole number.");
        }
    }

    private void showConfigureHostOnlyDhcpDialog(String interfaceName) {
        JTextField name = new JTextField(interfaceName, 20);
        name.setEditable(false);
        JCheckBox enabled = new JCheckBox("Enable DHCP server", true);
        JTextField server = new JTextField("192.168.56.100", 16);
        JTextField lower = new JTextField("192.168.56.101", 16);
        JTextField upper = new JTextField("192.168.56.254", 16);
        JTextField mask = new JTextField("255.255.255.0", 16);
        JPanel panel = formPanel(new String[]{"Interface", "", "Server address", "Lower address", "Upper address", "Mask"},
                new Component[]{name, enabled, server, lower, upper, mask});
        if (JOptionPane.showConfirmDialog(this, panel, "Configure Host-only DHCP",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        runBackground("Configuring host-only DHCP", () -> {
            client.configureHostOnlyDhcp(name.getText(), enabled.isSelected(), server.getText(), lower.getText(),
                    upper.getText(), mask.getText());
            return null;
        }, ignored -> appendLog("Configured DHCP for host-only interface " + name.getText().trim() + "."));
    }

    private void showServerInformation() {
        if (!requireConnection()) {
            return;
        }
        runBackground("Loading server information", client::serverInformation, information ->
                showTextDialog("Server Information", information));
    }

    private void showGuestLogs() {
        VirtualMachine machine = selectedMachine();
        if (machine == null || !requireConnection()) {
            return;
        }
        JComboBox<String> logSelector = new JComboBox<>(new String[]{"VBox.log", "VBox.log.1", "VBox.log.2", "VBox.log.3"});
        if (JOptionPane.showConfirmDialog(this, logSelector, "Guest Logs — " + machine.name(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        int index = logSelector.getSelectedIndex();
        runBackground("Loading guest log", () -> client.guestLog(machine, index),
                log -> showTextDialog("Guest Log — " + machine.name() + " (" + logSelector.getSelectedItem() + ")", log));
    }

    private void cloneSelectedMachine() {
        VirtualMachine machine = selectedMachine();
        if (machine == null || !requireConnection()) {
            return;
        }
        JTextField name = new JTextField(machine.name() + " Clone", 28);
        JCheckBox linked = new JCheckBox("Create a linked clone", false);
        JPanel content = formPanel(new String[]{"Name", ""}, new Component[]{name, linked});
        if (JOptionPane.showConfirmDialog(this, content, "Clone Guest — " + machine.name(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION || name.getText().isBlank()) {
            return;
        }
        runBackground("Cloning guest", () -> {
            client.cloneMachine(machine, name.getText().trim(), linked.isSelected());
            return name.getText().trim();
        }, clone -> {
            appendLog("Created clone '" + clone + "' from " + machine.name() + ".");
            refreshMachines();
        });
    }

    private void setMachineGroup() {
        VirtualMachine machine = selectedMachine();
        if (machine == null || !requireConnection()) {
            return;
        }
        JTextField group = new JTextField(machine.groups(), 28);
        if (JOptionPane.showConfirmDialog(this, formPanel(new String[]{"Group"}, new Component[]{group}),
                "Set Group — " + machine.name(), JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        runBackground("Updating guest group", () -> {
            MachineSettings settings = client.machineSettings(machine);
            client.updateMachineSettings(machine, new MachineSettings(
                    settings.name(), settings.description(), normalizeGroup(group.getText()), settings.osType(),
                    settings.memoryMb(), settings.cpuCount(), settings.videoMemoryMb(),
                    settings.vrdeEnabled(), settings.vrdePort()));
            return null;
        }, ignored -> {
            appendLog("Updated group for " + machine.name() + ".");
            refreshMachines();
        });
    }

    private void importAppliance() {
        if (!requireConnection()) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select Virtual Appliance to Inspect");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File appliance = chooser.getSelectedFile();
        String details = "Appliance file: " + appliance.getAbsolutePath() + System.lineSeparator()
                + "Size: " + appliance.length() + " bytes" + System.lineSeparator()
                + "The configured VirtualBox transport will inspect and import this appliance. "
                + "Per-machine import overrides require VirtualBox's appliance-specific API and are not "
                + "available through the common RemoteBox SOAP contract.";
        if (JOptionPane.showConfirmDialog(this, details, "Inspect Appliance Before Import",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.INFORMATION_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        runBackground("Importing appliance", () -> {
            client.importAppliance(appliance.getAbsolutePath());
            return appliance.getName();
        }, name -> {
            appendLog("Imported appliance: " + name);
            refreshMachines();
        });
    }

    private void exportAppliance() {
        if (!requireConnection()) {
            return;
        }
        List<VirtualMachine> machines = machineModel.machines();
        if (machines.isEmpty()) {
            showError("There are no guests available to export.");
            return;
        }
        JList<VirtualMachine> selector = new JList<>(machines.toArray(VirtualMachine[]::new));
        selector.setSelectionMode(javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        VirtualMachine selected = selectedMachine();
        if (selected != null) {
            selector.setSelectedValue(selected, true);
        }
        JScrollPane list = new JScrollPane(selector);
        list.setPreferredSize(new Dimension(410, 220));
        if (JOptionPane.showConfirmDialog(this, list, "Select Guests to Export",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        List<VirtualMachine> selection = selector.getSelectedValuesList();
        if (selection.isEmpty()) {
            showError("Select at least one guest to export.");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export Virtual Appliance");
        chooser.setSelectedFile(new File(selection.size() == 1
                ? selection.get(0).name() + ".ova" : "virtual-machines.ova"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File appliance = chooser.getSelectedFile();
        runBackground("Exporting appliance", () -> {
            client.exportAppliance(selection, appliance.getAbsolutePath());
            return appliance.getName();
        }, name -> appendLog("Exported " + selection.size() + " guest(s) to appliance: " + name));
    }

    private void showMachineSettingsDialog() {
        VirtualMachine machine = selectedMachine();
        if (machine == null || !requireConnection()) {
            return;
        }
        runBackground("Loading guest settings", () -> {
            Map<String, String> loadErrors = new LinkedHashMap<>();
            MachineSettings settings = loadSettingsPage("General", () -> client.machineSettings(machine),
                    () -> new MachineSettings(machine.name(), machine.description(), machine.groups(), machine.osType(),
                            machine.memoryMb(), machine.cpuCount(), 16, false, machine.vrdePort()), loadErrors);
            List<NetworkAdapterSettings> adapters = new ArrayList<>(8);
            for (int index = 1; index <= 8; index++) {
                int adapterIndex = index;
                adapters.add(loadSettingsPage("Network", () -> client.networkAdapterSettings(machine, adapterIndex),
                        () -> new NetworkAdapterSettings(false, "none", ""), loadErrors));
            }
            List<SerialPortSettings> serialPorts = List.of(
                    loadSettingsPage("Ports", () -> client.serialPortSettings(machine, 1),
                            () -> new SerialPortSettings(false, "0x3f8", 4, "disconnected"), loadErrors),
                    loadSettingsPage("Ports", () -> client.serialPortSettings(machine, 2),
                            () -> new SerialPortSettings(false, "0x2f8", 3, "disconnected"), loadErrors));
            boolean commandTransport = client instanceof VBoxManageClient;
            return new SettingsPageData(settings, adapters,
                    loadSettingsPage("Audio", () -> client.audioSettings(machine),
                            () -> new AudioSettings(false, "ac97", "default"), loadErrors),
                    loadSettingsPage("General", () -> client.guestIntegrationSettings(machine),
                            () -> new GuestIntegrationSettings("disabled", "disabled"), loadErrors),
                    serialPorts,
                    loadSettingsPage("Ports", () -> client.parallelPortSettings(machine),
                            () -> new ParallelPortSettings(false, "0x378", 7), loadErrors),
                    loadSettingsPage("USB", () -> client.usbControllerSettings(machine),
                            () -> new UsbControllerSettings(true, "ohci"), loadErrors),
                    loadSettingsPage("Storage", () -> client.storageLayout(machine), List::of, loadErrors),
                    commandTransport ? loadSettingsPage("System", () -> client.motherboardSettings(machine),
                            RemoteBoxApplication::defaultMotherboardSettings, loadErrors) : defaultMotherboardSettings(),
                    commandTransport ? loadSettingsPage("Display", () -> client.displaySettings(machine),
                            RemoteBoxApplication::defaultDisplaySettings, loadErrors) : defaultDisplaySettings(),
                    commandTransport, loadErrors);
        }, pageData -> showMachineSettingsEditor(machine, pageData));
    }

    private static <T> T loadSettingsPage(String page, SettingsLoader<T> loader,
                                          java.util.function.Supplier<T> fallback, Map<String, String> errors) {
        try {
            return loader.load();
        } catch (Exception exception) {
            errors.putIfAbsent(page, errorMessage(exception));
            return fallback.get();
        }
    }

    private static String errorMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static MotherboardSettings defaultMotherboardSettings() {
        return new MotherboardSettings("disk,dvd,none,none", "piix3", "ps2mouse", "bios",
                false, false, 100, true, true, true);
    }

    private static DisplaySettings defaultDisplaySettings() {
        return new DisplaySettings("vmsvga", 1, 100, false, false, "");
    }

    private void showMachineSettingsEditor(VirtualMachine machine, SettingsPageData pageData) {
        MachineSettings settings = pageData.settings();
        List<NetworkAdapterSettings> networkAdapters = pageData.networkAdapters();
        JTextField name = new JTextField(settings.name(), 28);
        JTextArea description = new JTextArea(settings.description(), 5, 28);
        description.setLineWrap(true);
        description.setWrapStyleWord(true);
        JTextField group = new JTextField(settings.groups(), 28);
        JComboBox<String> osType = new JComboBox<>(new String[]{
                settings.osType(), "Windows11_64", "Windows10_64", "Ubuntu_64", "Debian_64", "Fedora_64", "Other_64"
        });
        JTextField memory = new JTextField(Integer.toString(settings.memoryMb()), 10);
        JTextField cpus = new JTextField(Integer.toString(settings.cpuCount()), 10);
        JTextField videoMemory = new JTextField(Integer.toString(settings.videoMemoryMb()), 10);
        JCheckBox vrdeEnabled = new JCheckBox("Enable remote display server", settings.vrdeEnabled());
        JTextField vrdePort = new JTextField(settings.vrdePort(), 12);

        MotherboardSettings motherboardSettings = pageData.motherboardSettings();
        JComboBox<String> bootOne = new JComboBox<>(new String[]{"disk", "dvd", "floppy", "net", "none"});
        JComboBox<String> bootTwo = new JComboBox<>(new String[]{"disk", "dvd", "floppy", "net", "none"});
        JComboBox<String> bootThree = new JComboBox<>(new String[]{"disk", "dvd", "floppy", "net", "none"});
        JComboBox<String> bootFour = new JComboBox<>(new String[]{"disk", "dvd", "floppy", "net", "none"});
        String[] bootOrder = motherboardSettings.bootOrder().split(",", -1);
        bootOne.setSelectedItem(bootOrder[0]);
        bootTwo.setSelectedItem(bootOrder[1]);
        bootThree.setSelectedItem(bootOrder[2]);
        bootFour.setSelectedItem(bootOrder[3]);
        JComboBox<String> chipset = new JComboBox<>(new String[]{"piix3", "ich9"});
        chipset.setSelectedItem(motherboardSettings.chipset());
        JComboBox<String> pointingDevice = new JComboBox<>(new String[]{"ps2mouse", "usbtablet", "usbmultitouch", "usbmouse"});
        pointingDevice.setSelectedItem(motherboardSettings.pointingDevice());
        JComboBox<String> firmware = new JComboBox<>(new String[]{"bios", "efi", "efi32", "efi64", "efidual"});
        firmware.setSelectedItem(motherboardSettings.firmware());
        JCheckBox efiEnabled = new JCheckBox("Enable EFI", motherboardSettings.efiEnabled());
        JCheckBox rtcUtc = new JCheckBox("Use UTC hardware clock", motherboardSettings.rtcUsesUtc());
        JTextField executionCap = new JTextField(Integer.toString(motherboardSettings.executionCap()), 6);
        JCheckBox pae = new JCheckBox("Enable PAE/NX", motherboardSettings.paeEnabled());
        JCheckBox hardwareVirtualization = new JCheckBox("Enable hardware virtualization", motherboardSettings.hardwareVirtualizationEnabled());
        JCheckBox nestedPaging = new JCheckBox("Enable nested paging", motherboardSettings.nestedPagingEnabled());

        DisplaySettings displaySettings = pageData.displaySettings();
        JComboBox<String> graphicsController = new JComboBox<>(new String[]{"vboxvga", "vmsvga", "vboxsvga"});
        graphicsController.setSelectedItem(displaySettings.graphicsController());
        JTextField monitorCount = new JTextField(Integer.toString(displaySettings.monitorCount()), 6);
        JTextField scaleFactor = new JTextField(Integer.toString(displaySettings.scaleFactor()), 6);
        JCheckBox acceleration3d = new JCheckBox("Enable 3D acceleration", displaySettings.acceleration3dEnabled());
        JCheckBox recordingEnabled = new JCheckBox("Enable video recording", displaySettings.recordingEnabled());
        JTextField recordingFile = new JTextField(displaySettings.recordingFile(), 28);

        JCheckBox audioEnabled = new JCheckBox("Enable audio", pageData.audioSettings().enabled());
        JComboBox<String> audioController = new JComboBox<>(new String[]{"ac97", "hda", "sb16"});
        audioController.setSelectedItem(pageData.audioSettings().controller());
        JComboBox<String> audioDriver = new JComboBox<>(new String[]{
                "default", "none", "null", "directsound", "was", "oss", "alsa", "pulse", "coreaudio"});
        audioDriver.setSelectedItem(pageData.audioSettings().driver());

        String[] integrationModes = {"disabled", "hosttoguest", "guesttohost", "bidirectional"};
        JComboBox<String> clipboard = new JComboBox<>(integrationModes);
        clipboard.setSelectedItem(pageData.integrationSettings().clipboardMode());
        JComboBox<String> dragAndDrop = new JComboBox<>(integrationModes);
        dragAndDrop.setSelectedItem(pageData.integrationSettings().dragAndDropMode());

        PortEditor serialOne = new PortEditor("Enable serial port", pageData.serialPorts().get(0),
                new String[]{"0x3f8", "0x2f8", "0x3e8", "0x2e8"});
        PortEditor serialTwo = new PortEditor("Enable serial port", pageData.serialPorts().get(1),
                new String[]{"0x3f8", "0x2f8", "0x3e8", "0x2e8"});
        PortEditor parallel = new PortEditor("Enable parallel port", pageData.parallelPortSettings(),
                new String[]{"0x378", "0x278", "0x3bc"});

        JCheckBox usbEnabled = new JCheckBox("Enable USB controller", pageData.usbControllerSettings().enabled());
        JComboBox<String> usbController = new JComboBox<>(new String[]{"ohci", "ehci", "xhci"});
        usbController.setSelectedItem(pageData.usbControllerSettings().controller());
        usbEnabled.addActionListener(event -> usbController.setEnabled(usbEnabled.isSelected()));
        usbController.setEnabled(usbEnabled.isSelected());

        List<NetworkAdapterEditor> networkEditors = new ArrayList<>(8);
        JTabbedPane adapterTabs = new JTabbedPane();
        for (int index = 1; index <= 8; index++) {
            NetworkAdapterEditor editor = new NetworkAdapterEditor(machine, index, networkAdapters.get(index - 1));
            networkEditors.add(editor);
            adapterTabs.addTab("Adapter " + index, editor.panel());
        }
        adapterTabs.setPreferredSize(new Dimension(650, 380));

        JPanel networkPanel = new JPanel(new BorderLayout(0, 10));
        networkPanel.setBorder(new EmptyBorder(10, 12, 8, 12));
        JLabel networkGuide = formHelpText("<html><b>Common layout:</b> use Adapter 1 = NAT for Internet access and "
                + "Adapter 2 = Host-only Adapter for stable host/guest communication. "
                + "Use NAT port forwarding to expose specific guest services through Adapter 1.</html>");
        networkPanel.add(networkGuide, BorderLayout.NORTH);
        networkPanel.add(adapterTabs, BorderLayout.CENTER);

        JTabbedPane tabs = new JTabbedPane(JTabbedPane.LEFT);
        tabs.setBorder(new EmptyBorder(4, 4, 4, 4));
        tabs.addTab("General", icon("rb_settings_16px.png", 16),
                formPanel(new String[]{"Name", "Description", "Group", "Operating system", "Shared clipboard",
                                "Drag and drop", ""},
                        new Component[]{name, new JScrollPane(description), group, osType, clipboard, dragAndDrop,
                                formHelpText("Guest Additions are required for clipboard and drag-and-drop support.")}));
        tabs.setToolTipTextAt(0, "Guest identity and advanced host/guest integration settings");
        tabs.addTab("System", icon("machine_16px.png", 16), pageData.commandTransport()
                ? formPanel(new String[]{"Base memory (MB)", "Processors", "Boot device 1", "Boot device 2",
                        "Boot device 3", "Boot device 4", "Chipset", "Pointing device", "Firmware", "",
                        "", "Processor cap (%)", "", "", ""},
                new Component[]{memory, cpus, bootOne, bootTwo, bootThree, bootFour, chipset, pointingDevice, firmware,
                        efiEnabled, rtcUtc, executionCap, pae, hardwareVirtualization, nestedPaging})
                : formPanel(new String[]{"Base memory (MB)", "Processors", ""},
                new Component[]{memory, cpus, formHelpText("Motherboard controls require a Local / SSH VBoxManage connection.")}));
        tabs.setToolTipTextAt(1, "Guest memory, processor allocation, and motherboard settings");
        tabs.addTab("Display", icon("vrdp_32px.png", 16), pageData.commandTransport()
                ? formPanel(new String[]{"Video memory (MB)", "Graphics controller", "Monitor count", "Scale factor (%)",
                        "", "Remote display", "TCP port(s)", "Recording", "Recording file"},
                new Component[]{videoMemory, graphicsController, monitorCount, scaleFactor, acceleration3d,
                        vrdeEnabled, vrdePort, recordingEnabled, recordingFile})
                : formPanel(new String[]{"Video memory (MB)", "Remote display", "TCP port(s)", ""},
                new Component[]{videoMemory, vrdeEnabled, vrdePort,
                        formHelpText("Graphics and recording controls require a Local / SSH VBoxManage connection.")}));
        tabs.setToolTipTextAt(2, "Video, graphics, recording, and remote-display server");
        StorageSettingsPanel storage = new StorageSettingsPanel(machine, pageData.storageControllers());
        tabs.addTab("Storage", icon("snapshot_manager_16px.png", 16), storage.panel());
        tabs.setToolTipTextAt(3, "Storage controllers, virtual disks, and optical media");
        tabs.addTab("Audio", icon("keyboard_32px.png", 16),
                formPanel(new String[]{"", "Controller", "Host driver", ""},
                        new Component[]{audioEnabled, audioController, audioDriver,
                                formHelpText("Only drivers supported by the VirtualBox host are accepted.")}));
        tabs.setToolTipTextAt(4, "Guest audio controller and host audio driver");
        tabs.addTab("Network", icon("connect_32px.png", 16), networkPanel);
        tabs.setToolTipTextAt(5, "NAT, bridged, host-only, and internal guest networks");
        JTabbedPane portTabs = new JTabbedPane();
        portTabs.addTab("Serial Port 1", serialOne.panel());
        portTabs.addTab("Serial Port 2", serialTwo.panel());
        portTabs.addTab("Parallel Port", parallel.panel());
        tabs.addTab("Ports", icon("keyboard_32px.png", 16), portTabs);
        tabs.setToolTipTextAt(6, "Serial and parallel hardware ports");
        tabs.addTab("USB", icon("connect_32px.png", 16),
                formPanel(new String[]{"", "Controller", ""},
                        new Component[]{usbEnabled, usbController,
                                formHelpText("OHCI = USB 1.1, EHCI = USB 2.0, xHCI = USB 3.0. "
                                        + "Use the dedicated USB filter manager for device filters.")}));
        tabs.setToolTipTextAt(7, "USB controller and guest USB device filters");
        tabs.addTab("Shared Folders", icon("machine_16px.png", 16), actionSettingsPanel(
                "Shared Folders",
                "Add, inspect, or remove machine shared folders. Guest Additions are required in the guest to mount them.",
                "Manage shared folders…",
                event -> showSharedFoldersDialog(machine)
        ));
        tabs.setToolTipTextAt(8, "Host folders exposed to the guest");
        markUnavailableTab(tabs, "General", 0, pageData);
        markUnavailableTab(tabs, "System", 1, pageData);
        markUnavailableTab(tabs, "Display", 2, pageData);
        markUnavailableTab(tabs, "Storage", 3, pageData);
        markUnavailableTab(tabs, "Audio", 4, pageData);
        markUnavailableTab(tabs, "Network", 5, pageData);
        markUnavailableTab(tabs, "Ports", 6, pageData);
        markUnavailableTab(tabs, "USB", 7, pageData);
        tabs.setPreferredSize(new Dimension(760, 420));

        if (machine.isRunning() || machine.isPaused()) {
            name.setEnabled(false);
            osType.setEnabled(false);
            memory.setEnabled(false);
            cpus.setEnabled(false);
            videoMemory.setEnabled(false);
            group.setEnabled(false);
            tabs.setToolTipTextAt(0, "Online settings: description and remote display only.");
        }

        if (JOptionPane.showConfirmDialog(this, tabs, "Settings — " + machine.name(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            MachineSettings updated = new MachineSettings(
                    name.getText().trim(),
                    description.getText().trim(),
                    normalizeGroup(group.getText()),
                    Objects.toString(osType.getSelectedItem()),
                    Integer.parseInt(memory.getText().trim()),
                    Integer.parseInt(cpus.getText().trim()),
                    Integer.parseInt(videoMemory.getText().trim()),
                    vrdeEnabled.isSelected(),
                    vrdePort.getText().trim()
            );
            if (updated.name().isBlank() || updated.memoryMb() < 4 || updated.cpuCount() < 1
                    || updated.videoMemoryMb() < 1 || !isValidVrdePortSpecification(updated.vrdePort())) {
                throw new NumberFormatException();
            }
            List<NetworkAdapterSettings> updatedNetworkAdapters = networkEditors.stream()
                    .map(NetworkAdapterEditor::settings)
                    .toList();
            AudioSettings updatedAudio = new AudioSettings(audioEnabled.isSelected(),
                    Objects.toString(audioController.getSelectedItem()), Objects.toString(audioDriver.getSelectedItem()));
            GuestIntegrationSettings updatedIntegration = new GuestIntegrationSettings(
                    Objects.toString(clipboard.getSelectedItem()), Objects.toString(dragAndDrop.getSelectedItem()));
            SerialPortSettings updatedSerialOne = serialOne.serialSettings();
            SerialPortSettings updatedSerialTwo = serialTwo.serialSettings();
            ParallelPortSettings updatedParallel = parallel.parallelSettings();
            UsbControllerSettings updatedUsb = new UsbControllerSettings(usbEnabled.isSelected(),
                    Objects.toString(usbController.getSelectedItem()));
            MotherboardSettings updatedMotherboard = new MotherboardSettings(String.join(",",
                    Objects.toString(bootOne.getSelectedItem()), Objects.toString(bootTwo.getSelectedItem()),
                    Objects.toString(bootThree.getSelectedItem()), Objects.toString(bootFour.getSelectedItem())),
                    Objects.toString(chipset.getSelectedItem()), Objects.toString(pointingDevice.getSelectedItem()),
                    Objects.toString(firmware.getSelectedItem()), efiEnabled.isSelected(), rtcUtc.isSelected(),
                    Integer.parseInt(executionCap.getText().trim()), pae.isSelected(), hardwareVirtualization.isSelected(),
                    nestedPaging.isSelected());
            DisplaySettings updatedDisplay = new DisplaySettings(Objects.toString(graphicsController.getSelectedItem()),
                    Integer.parseInt(monitorCount.getText().trim()), Integer.parseInt(scaleFactor.getText().trim()),
                    acceleration3d.isSelected(), recordingEnabled.isSelected(), recordingFile.getText());
            List<Map.Entry<String, StorageController>> updatedControllers = storage.pendingUpdates();
            runBackground("Saving guest settings", () -> {
                if (!updated.equals(settings)) {
                    client.updateMachineSettings(machine, updated);
                }
                for (int index = 1; index <= updatedNetworkAdapters.size(); index++) {
                    if (!updatedNetworkAdapters.get(index - 1).equals(networkAdapters.get(index - 1))) {
                        client.updateNetworkAdapterSettings(machine, index, updatedNetworkAdapters.get(index - 1));
                    }
                }
                if (!updatedAudio.equals(pageData.audioSettings())) {
                    client.updateAudioSettings(machine, updatedAudio);
                }
                if (!updatedIntegration.equals(pageData.integrationSettings())) {
                    client.updateGuestIntegrationSettings(machine, updatedIntegration);
                }
                if (!updatedSerialOne.equals(pageData.serialPorts().get(0))) {
                    client.updateSerialPortSettings(machine, 1, updatedSerialOne);
                }
                if (!updatedSerialTwo.equals(pageData.serialPorts().get(1))) {
                    client.updateSerialPortSettings(machine, 2, updatedSerialTwo);
                }
                if (!updatedParallel.equals(pageData.parallelPortSettings())) {
                    client.updateParallelPortSettings(machine, updatedParallel);
                }
                if (pageData.hasLoadError("USB") || !updatedUsb.equals(pageData.usbControllerSettings())) {
                    client.updateUsbControllerSettings(machine, updatedUsb);
                }
                if (pageData.commandTransport() && !updatedMotherboard.equals(pageData.motherboardSettings())) {
                    client.updateMotherboardSettings(machine, updatedMotherboard);
                }
                if (pageData.commandTransport() && !updatedDisplay.equals(pageData.displaySettings())) {
                    client.updateDisplaySettings(machine, updatedDisplay);
                }
                for (Map.Entry<String, StorageController> controller : updatedControllers) {
                    client.updateStorageController(machine, controller.getKey(), controller.getValue());
                }
                return updated.name();
            }, savedName -> {
                appendLog("Saved settings for " + savedName + ".");
                refreshMachines();
            });
        } catch (IllegalArgumentException exception) {
            showError("Enter valid settings. " + exception.getMessage());
        }
    }

    private static void markUnavailableTab(JTabbedPane tabs, String page, int tabIndex, SettingsPageData pageData) {
        if (!pageData.hasLoadError(page)) {
            return;
        }
        String message = "Could not load " + page + " settings: " + pageData.loadError(page);
        tabs.setForegroundAt(tabIndex, Color.decode("#E55353"));
        tabs.setToolTipTextAt(tabIndex, message);
        disableAndMark(tabs.getComponentAt(tabIndex), message);
    }

    private static void disableAndMark(Component component, String message) {
        if (component instanceof JComponent field) {
            field.setToolTipText(message);
            if (field instanceof JTextField || field instanceof JComboBox<?> || field instanceof JCheckBox) {
                field.setBorder(BorderFactory.createLineBorder(Color.decode("#E55353"), 2));
            }
        }
        if (component instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                disableAndMark(child, message);
            }
        }
    }

    /**
     * VirtualBox accepts a single TCP port, a range, or a comma-separated list
     * of either. Validate it before sending the value to either transport.
     */
    private static boolean isValidVrdePortSpecification(String ports) {
        String value = ports == null ? "" : ports.trim();
        if (value.isBlank()) {
            return true;
        }
        for (String range : value.split(",", -1)) {
            String[] bounds = range.trim().split("-", -1);
            if (bounds.length == 0 || bounds.length > 2 || !isTcpPort(bounds[0])
                    || (bounds.length == 2 && (!isTcpPort(bounds[1])
                    || Integer.parseInt(bounds[0]) > Integer.parseInt(bounds[1])))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTcpPort(String value) {
        try {
            int port = Integer.parseInt(value);
            return port >= 1 && port <= 65_535;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private JPanel actionSettingsPanel(String headingText, String message, String buttonText,
                                       java.awt.event.ActionListener action) {
        JLabel heading = new JLabel(headingText);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        JLabel detail = new JLabel("<html><body style='width:420px'>" + escapeHtml(message) + "</body></html>");
        detail.setForeground(MiraDarkTheme.DISABLED_FOREGROUND);
        JButton button = new JButton(buttonText);
        button.addActionListener(action);

        JPanel content = new JPanel(new GridBagLayout());
        content.setBorder(new EmptyBorder(26, 28, 26, 28));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.anchor = GridBagConstraints.LINE_START;
        constraints.insets = new Insets(0, 0, 12, 0);
        content.add(heading, constraints);
        constraints.gridy = 1;
        content.add(detail, constraints);
        constraints.gridy = 2;
        constraints.insets = new Insets(20, 0, 0, 0);
        content.add(button, constraints);
        content.add(Box.createVerticalGlue(), fillerConstraints(3));
        return content;
    }

    private void showAudioSettingsDialog(VirtualMachine machine) {
        runBackground("Loading audio settings", () -> client.audioSettings(machine),
                settings -> showAudioSettingsEditor(machine, settings));
    }

    private void showAudioSettingsEditor(VirtualMachine machine, AudioSettings settings) {
        JCheckBox enabled = new JCheckBox("Enable audio", settings.enabled());
        JComboBox<String> controller = new JComboBox<>(new String[]{"ac97", "hda", "sb16"});
        controller.setSelectedItem(settings.controller());
        JComboBox<String> driver = new JComboBox<>(new String[]{
                "default", "none", "null", "directsound", "was", "oss", "alsa", "pulse", "coreaudio"
        });
        driver.setSelectedItem(settings.driver());
        JLabel hint = formHelpText("Only drivers supported by the VirtualBox host are accepted.");
        JPanel content = formPanel(new String[]{"", "Controller", "Host driver", ""},
                new Component[]{enabled, controller, driver, hint});
        if (JOptionPane.showConfirmDialog(this, content, "Audio — " + machine.name(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            AudioSettings updated = new AudioSettings(enabled.isSelected(),
                    Objects.toString(controller.getSelectedItem()), Objects.toString(driver.getSelectedItem()));
            runBackground("Saving audio settings", () -> {
                client.updateAudioSettings(machine, updated);
                return null;
            }, ignored -> appendLog("Updated audio settings for " + machine.name() + "."));
        } catch (IllegalArgumentException exception) {
            showError(exception.getMessage());
        }
    }

    private static final class PortEditor {
        private final JCheckBox enabled;
        private final JComboBox<String> ioBase;
        private final JTextField irq;
        private final JPanel panel;
        private final String serialMode;

        private PortEditor(String enableLabel, SerialPortSettings settings, String[] ioAddresses) {
            this(enableLabel, settings.enabled(), settings.ioBase(), settings.irq(), ioAddresses, settings.mode());
        }

        private PortEditor(String enableLabel, ParallelPortSettings settings, String[] ioAddresses) {
            this(enableLabel, settings.enabled(), settings.ioBase(), settings.irq(), ioAddresses, null);
        }

        private PortEditor(String enableLabel, boolean initiallyEnabled, String initialAddress, int initialIrq,
                           String[] ioAddresses, String serialMode) {
            this.serialMode = serialMode;
            enabled = new JCheckBox(enableLabel, initiallyEnabled);
            ioBase = new JComboBox<>(ioAddresses);
            ioBase.setEditable(true);
            ioBase.setSelectedItem(initialAddress);
            irq = new JTextField(Integer.toString(initialIrq), 5);
            panel = formPanel(new String[]{"", "I/O base", "IRQ"},
                    new Component[]{enabled, ioBase, irq});
            enabled.addActionListener(event -> updateEnabledState());
            updateEnabledState();
        }

        private void updateEnabledState() {
            ioBase.setEnabled(enabled.isSelected());
            irq.setEnabled(enabled.isSelected());
        }

        private JPanel panel() {
            return panel;
        }

        private SerialPortSettings serialSettings() {
            if (serialMode == null) {
                throw new IllegalStateException("This editor does not represent a serial port.");
            }
            return new SerialPortSettings(enabled.isSelected(), Objects.toString(ioBase.getSelectedItem()),
                    Integer.parseInt(irq.getText().trim()), serialMode);
        }

        private ParallelPortSettings parallelSettings() {
            if (serialMode != null) {
                throw new IllegalStateException("This editor does not represent a parallel port.");
            }
            return new ParallelPortSettings(enabled.isSelected(), Objects.toString(ioBase.getSelectedItem()),
                    Integer.parseInt(irq.getText().trim()));
        }
    }

    private final class NetworkAdapterEditor {
        private final VirtualMachine machine;
        private final int adapterIndex;
        private final JCheckBox enabled;
        private final JComboBox<String> attachment;
        private final JTextField adapterName;
        private final JLabel explanation;
        private final JButton forwarding;
        private final JPanel panel;

        private NetworkAdapterEditor(VirtualMachine machine, int adapterIndex, NetworkAdapterSettings settings) {
            this.machine = machine;
            this.adapterIndex = adapterIndex;
            enabled = new JCheckBox("Enable network adapter", settings.enabled());
            attachment = new JComboBox<>(new String[]{"nat", "bridged", "hostonly", "intnet", "none"});
            attachment.setSelectedItem(settings.attachmentType());
            adapterName = new JTextField(settings.adapterName(), 28);
            explanation = formHelpText("");

            forwarding = new JButton("Port Forwarding…");
            forwarding.setToolTipText("Configure services exposed through NAT on Adapter 1.");
            forwarding.addActionListener(event -> showNatPortForwardingDialog(machine));
            JPanel actions = new JPanel(new BorderLayout());
            actions.add(forwarding, BorderLayout.WEST);

            panel = formPanel(new String[]{"", "Attached to", "", "Adapter / network name", "", ""},
                    new Component[]{enabled, attachment, topologySummary(), adapterName, explanation, actions});
            attachment.addActionListener(event -> updateAttachmentPresentation());
            enabled.addActionListener(event -> updateAttachmentPresentation());
            updateAttachmentPresentation();
        }

        private JLabel topologySummary() {
            JLabel summary = formHelpText(adapterIndex == 1
                    ? "Adapter 1 is typically NAT, which gives the guest outbound Internet access."
                    : adapterIndex == 2
                    ? "Adapter 2 is commonly Host-only Adapter for direct host-to-guest access."
                    : "Additional adapters are useful for isolated or multi-network guest topologies.");
            return summary;
        }

        private void updateAttachmentPresentation() {
            String type = Objects.toString(attachment.getSelectedItem(), "nat");
            boolean needsName = "bridged".equals(type) || "hostonly".equals(type) || "intnet".equals(type);
            adapterName.setEnabled(enabled.isSelected() && needsName);
            forwarding.setEnabled(adapterIndex == 1 && enabled.isSelected() && "nat".equals(type));
            explanation.setText(attachmentExplanation(type));
        }

        private NetworkAdapterSettings settings() {
            return new NetworkAdapterSettings(enabled.isSelected(),
                    Objects.toString(attachment.getSelectedItem()), adapterName.getText());
        }

        private JPanel panel() {
            return panel;
        }
    }

    /**
     * Storage page laid out like the RemoteBox client: a device tree listing the
     * controllers with their attached media on the left, and the attributes of the
     * selected controller or attachment on the right.
     */
    private final class StorageSettingsPanel {
        private static final String NO_SELECTION_CARD = "none";
        private static final String CONTROLLER_CARD = "controller";
        private static final String ATTACHMENT_CARD = "attachment";

        private final VirtualMachine machine;
        private final boolean online;
        private final JPanel panel = new JPanel(new BorderLayout(12, 0));
        private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("Storage");
        private final DefaultTreeModel treeModel = new DefaultTreeModel(root);
        private final JTree tree = new JTree(treeModel);
        private final CardLayout cards = new CardLayout();
        private final JPanel attributes = new JPanel(cards);

        private final JTextField controllerName = new JTextField(16);
        private final JComboBox<String> controllerType = new JComboBox<>();
        private final JSpinner portCount = new JSpinner(new SpinnerNumberModel(1, 1, 30, 1));
        private final JCheckBox hostIoCache = new JCheckBox("Use Host I/O Cache");

        private final JLabel attachmentDevice = new JLabel();
        private final JLabel attachmentSlot = new JLabel();
        private final JLabel attachmentMedium = new JLabel();
        private final JButton chooseImage = new JButton("Choose Disk Image…");
        private final JButton eject = new JButton("Eject");

        private final JButton addController = new JButton("＋ Controller");
        private final JButton removeController = new JButton("－ Controller");
        private final JButton addAttachment = new JButton("＋ Medium");
        private final JButton removeAttachment = new JButton("－ Medium");

        private final Map<String, StorageController> edited = new LinkedHashMap<>();
        private List<StorageController> controllers;
        private String shownController;
        private boolean populating;
        private boolean hostIoCacheTouched;

        private StorageSettingsPanel(VirtualMachine machine, List<StorageController> controllers) {
            this.machine = machine;
            this.online = machine.isRunning() || machine.isPaused();
            this.controllers = List.copyOf(controllers);

            tree.setRootVisible(false);
            tree.setShowsRootHandles(true);
            tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
            tree.setCellRenderer(new StorageTreeRenderer());
            tree.addTreeSelectionListener(event -> showSelection());

            JPanel devices = new JPanel(new BorderLayout(0, 8));
            devices.setBorder(BorderFactory.createTitledBorder("Storage Devices"));
            devices.add(darkScrollPane(tree), BorderLayout.CENTER);
            devices.add(deviceToolBar(), BorderLayout.SOUTH);
            devices.setPreferredSize(new Dimension(330, 320));

            attributes.setBorder(BorderFactory.createTitledBorder("Attributes"));
            attributes.add(noSelectionCard(), NO_SELECTION_CARD);
            attributes.add(controllerCard(), CONTROLLER_CARD);
            attributes.add(attachmentCard(), ATTACHMENT_CARD);

            panel.setBorder(new EmptyBorder(12, 12, 12, 12));
            panel.add(devices, BorderLayout.WEST);
            panel.add(attributes, BorderLayout.CENTER);
            rebuildTree();
        }

        private JPanel panel() {
            return panel;
        }

        private JPanel deviceToolBar() {
            addController.setToolTipText("Add a storage controller");
            removeController.setToolTipText("Remove the selected controller and everything attached to it");
            addAttachment.setToolTipText("Attach a hard disk or optical drive to the selected controller");
            removeAttachment.setToolTipText("Detach the selected medium");
            addController.addActionListener(event -> addController());
            removeController.addActionListener(event -> removeSelectedController());
            addAttachment.addActionListener(event -> addSelectedAttachment());
            removeAttachment.addActionListener(event -> removeSelectedAttachment());

            JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            bar.add(addController);
            bar.add(removeController);
            bar.add(addAttachment);
            bar.add(removeAttachment);
            return bar;
        }

        private JPanel noSelectionCard() {
            JPanel card = new JPanel(new BorderLayout());
            card.setBorder(new EmptyBorder(16, 16, 16, 16));
            card.add(formHelpText("<html><body style='width:300px'>Select a controller to edit its hardware "
                    + "attributes, or a medium to change the image it exposes to the guest.</body></html>"),
                    BorderLayout.NORTH);
            return card;
        }

        private JPanel controllerCard() {
            controllerName.setEnabled(!online);
            controllerType.setEnabled(!online);
            portCount.setEnabled(!online);
            hostIoCache.addActionListener(event -> hostIoCacheTouched = !populating);
            return formPanel(new String[]{"Name", "Type", "Port Count", ""},
                    new Component[]{controllerName, controllerType, portCount, hostIoCache});
        }

        private JPanel attachmentCard() {
            chooseImage.addActionListener(event -> mountSelectedImage(true));
            eject.addActionListener(event -> mountSelectedImage(false));
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            actions.add(chooseImage);
            actions.add(eject);
            return formPanel(new String[]{"Device", "Slot", "Medium", ""},
                    new Component[]{attachmentDevice, attachmentSlot, attachmentMedium, actions});
        }

        private void rebuildTree() {
            shownController = null;
            root.removeAllChildren();
            for (StorageController controller : controllers) {
                DefaultMutableTreeNode node = new DefaultMutableTreeNode(controller);
                for (StorageAttachment attachment : controller.attachments()) {
                    node.add(new DefaultMutableTreeNode(new AttachedMedium(controller.name(), attachment)));
                }
                root.add(node);
            }
            treeModel.reload();
            for (int row = 0; row < tree.getRowCount(); row++) {
                tree.expandRow(row);
            }
            showSelection();
        }

        private void reload() {
            VirtualBoxClient loadingClient = client;
            if (loadingClient == null) {
                return;
            }
            runBackground("Reloading storage layout", () -> loadingClient.storageLayout(machine), layout -> {
                controllers = List.copyOf(layout);
                edited.clear();
                rebuildTree();
            });
        }

        private void showSelection() {
            captureControllerEdit();
            Object value = selectedUserObject();
            if (value instanceof StorageController controller) {
                showController(controller);
            } else if (value instanceof AttachedMedium attached) {
                showAttachment(attached);
            } else {
                shownController = null;
                cards.show(attributes, NO_SELECTION_CARD);
            }
            updateToolBarState();
        }

        private void showController(StorageController controller) {
            StorageController shown = edited.getOrDefault(controller.name(), controller);
            populating = true;
            try {
                shownController = controller.name();
                hostIoCacheTouched = shown.useHostIoCache() != null;
                controllerName.setText(shown.name());
                applyControllerTypes(shown);
                portCount.setModel(new SpinnerNumberModel(Math.max(1, shown.portCount()), 1,
                        Math.max(1, controller.maxPortCount()), 1));
                portCount.setEnabled(!online);
                boolean cacheKnown = shown.useHostIoCache() != null;
                hostIoCache.setSelected(cacheKnown && shown.useHostIoCache());
                hostIoCache.setEnabled(!online);
                hostIoCache.setToolTipText(cacheKnown ? null
                        : "This transport cannot read the current value; ticking the box applies it on save.");
            } finally {
                populating = false;
            }
            cards.show(attributes, CONTROLLER_CARD);
        }

        private void applyControllerTypes(StorageController shown) {
            List<String> items = new ArrayList<>(List.of(StorageController.controllerTypesFor(shown.bus())));
            String current = shown.controllerType();
            if (!current.isBlank() && items.stream().noneMatch(item -> item.equalsIgnoreCase(current))) {
                items.add(0, current);
            }
            controllerType.setModel(new DefaultComboBoxModel<>(items.toArray(String[]::new)));
            items.stream()
                    .filter(item -> item.equalsIgnoreCase(current))
                    .findFirst()
                    .ifPresent(controllerType::setSelectedItem);
        }

        private void showAttachment(AttachedMedium attached) {
            shownController = null;
            StorageAttachment attachment = attached.attachment();
            attachmentDevice.setText(attachment.deviceDescription());
            attachmentSlot.setText(attachment.slot());
            attachmentMedium.setText(attachment.empty() ? "(empty)"
                    : "<html><body style='width:300px'>" + escapeHtml(attachment.medium()) + "</body></html>");
            boolean optical = "dvd".equals(attachment.deviceType());
            chooseImage.setEnabled(optical);
            eject.setEnabled(optical && !attachment.empty());
            cards.show(attributes, ATTACHMENT_CARD);
        }

        /**
         * Controller attributes are applied together with the rest of the settings
         * dialog, so the form values are folded back into the edit map whenever the
         * form stops showing a controller.
         */
        private void captureControllerEdit() {
            if (shownController == null || populating) {
                return;
            }
            StorageController original = controllerByName(shownController);
            if (original == null) {
                return;
            }
            try {
                edited.put(shownController, new StorageController(
                        controllerName.getText(),
                        original.bus(),
                        Objects.toString(controllerType.getSelectedItem(), original.controllerType()),
                        (Integer) portCount.getValue(),
                        hostIoCacheTouched ? hostIoCache.isSelected() : null,
                        original.bootable(),
                        original.attachments()));
            } catch (IllegalArgumentException ignored) {
                // Keep the previous value while the entered name is still invalid.
            }
        }

        private List<Map.Entry<String, StorageController>> pendingUpdates() {
            captureControllerEdit();
            List<Map.Entry<String, StorageController>> updates = new ArrayList<>();
            for (Map.Entry<String, StorageController> entry : edited.entrySet()) {
                StorageController original = controllerByName(entry.getKey());
                if (original != null && !isUnchanged(original, entry.getValue())) {
                    updates.add(Map.entry(entry.getKey(), entry.getValue()));
                }
            }
            return updates;
        }

        private static boolean isUnchanged(StorageController original, StorageController updated) {
            return original.name().equals(updated.name())
                    && original.controllerType().equalsIgnoreCase(updated.controllerType())
                    && original.portCount() == updated.portCount()
                    && Objects.equals(original.useHostIoCache(), updated.useHostIoCache());
        }

        private void updateToolBarState() {
            Object value = selectedUserObject();
            boolean controllerSelected = value instanceof StorageController || value instanceof AttachedMedium;
            addController.setEnabled(!online);
            removeController.setEnabled(!online && controllerSelected);
            addAttachment.setEnabled(!online && controllerSelected);
            removeAttachment.setEnabled(!online && value instanceof AttachedMedium);
        }

        private Object selectedUserObject() {
            TreePath selection = tree.getSelectionPath();
            return selection != null && selection.getLastPathComponent() instanceof DefaultMutableTreeNode node
                    ? node.getUserObject()
                    : null;
        }

        private StorageController selectedController() {
            Object value = selectedUserObject();
            if (value instanceof StorageController controller) {
                return controller;
            }
            return value instanceof AttachedMedium attached ? controllerByName(attached.controllerName()) : null;
        }

        private StorageController controllerByName(String name) {
            return controllers.stream()
                    .filter(controller -> controller.name().equals(name))
                    .findFirst()
                    .orElse(null);
        }

        private void addController() {
            JTextField name = new JTextField(suggestedControllerName(), 18);
            JComboBox<String> bus = new JComboBox<>(new String[]{
                    "sata", "ide", "scsi", "sas", "pcie", "floppy", "usb", "virtio"});
            JPanel content = formPanel(new String[]{"Controller name", "Controller bus"},
                    new Component[]{name, bus});
            if (JOptionPane.showConfirmDialog(panel, content, "Add Storage Controller — " + machine.name(),
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
                return;
            }
            try {
                StorageControllerSpec spec = new StorageControllerSpec(name.getText(),
                        Objects.toString(bus.getSelectedItem()));
                runBackground("Adding storage controller", () -> {
                    client.addStorageController(machine, spec);
                    return spec.name();
                }, added -> {
                    appendLog("Added storage controller '" + added + "' to " + machine.name() + ".");
                    reload();
                });
            } catch (IllegalArgumentException exception) {
                showError(exception.getMessage());
            }
        }

        private String suggestedControllerName() {
            for (String candidate : List.of("SATA", "SATA 2", "IDE", "SCSI", "Storage")) {
                if (controllerByName(candidate) == null) {
                    return candidate;
                }
            }
            return "Controller " + (controllers.size() + 1);
        }

        private void removeSelectedController() {
            StorageController controller = selectedController();
            if (controller == null) {
                return;
            }
            if (!confirmDestructiveAction("Remove controller '" + controller.name() + "' and detach its "
                    + controller.attachments().size() + " medium/media?", "Remove Storage Controller")) {
                return;
            }
            runBackground("Removing storage controller", () -> {
                client.removeStorageController(machine, controller.name());
                return controller.name();
            }, removed -> {
                appendLog("Removed storage controller '" + removed + "' from " + machine.name() + ".");
                reload();
            });
        }

        private void addSelectedAttachment() {
            StorageController controller = selectedController();
            if (controller == null) {
                showError("Select a storage controller first.");
                return;
            }
            boolean canCreateDisks = client instanceof VBoxManageClient;

            JComboBox<String> deviceType = new JComboBox<>(new String[]{"Hard disk", "Optical drive"});
            JSpinner port = new JSpinner(new SpinnerNumberModel(firstFreePort(controller), 0,
                    Math.max(0, controller.maxPortCount() - 1), 1));
            JSpinner device = new JSpinner(new SpinnerNumberModel(0, 0,
                    "ide".equals(controller.bus()) ? 1 : 0, 1));
            JTextField medium = new JTextField(26);
            JButton browse = new JButton("Browse…");
            browse.addActionListener(event -> {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle("Hard disk".equals(deviceType.getSelectedItem())
                        ? "Select Virtual Hard Disk" : "Select Disc Image");
                if (chooser.showOpenDialog(panel) == JFileChooser.APPROVE_OPTION) {
                    medium.setText(chooser.getSelectedFile().getAbsolutePath());
                }
            });
            JPanel mediumPanel = new JPanel(new BorderLayout(6, 0));
            mediumPanel.add(medium, BorderLayout.CENTER);
            mediumPanel.add(browse, BorderLayout.EAST);

            JCheckBox createNew = new JCheckBox("Create a new virtual hard disk", false);
            createNew.setEnabled(canCreateDisks);
            createNew.setToolTipText(canCreateDisks ? null
                    : "Creating disks requires a Local / SSH VBoxManage connection.");
            JTextField size = new JTextField("20480", 8);
            JComboBox<String> format = new JComboBox<>(new String[]{"VDI", "VHD", "VMDK"});
            JCheckBox fixed = new JCheckBox("Allocate the full size immediately", false);

            Runnable syncFields = () -> {
                boolean hardDisk = "Hard disk".equals(deviceType.getSelectedItem());
                createNew.setEnabled(canCreateDisks && hardDisk);
                boolean creating = hardDisk && createNew.isEnabled() && createNew.isSelected();
                size.setEnabled(creating);
                format.setEnabled(creating);
                fixed.setEnabled(creating);
            };
            deviceType.addActionListener(event -> syncFields.run());
            createNew.addActionListener(event -> syncFields.run());
            syncFields.run();

            JPanel content = formPanel(
                    new String[]{"Controller", "Device", "Port", "Device slot", "Medium file", "",
                            "Size (MB)", "Format", ""},
                    new Component[]{new JLabel(controller.name() + "  (" + controller.busDescription() + ")"),
                            deviceType, port, device, mediumPanel, createNew, size, format, fixed});
            if (JOptionPane.showConfirmDialog(panel, content, "Attach Medium — " + machine.name(),
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
                return;
            }

            int selectedPort = (Integer) port.getValue();
            int selectedDevice = (Integer) device.getValue();
            String path = medium.getText().trim();
            if ("Optical drive".equals(deviceType.getSelectedItem())) {
                runBackground("Attaching optical drive", () -> {
                    client.attachOpticalMedium(machine, controller.name(), selectedPort, selectedDevice, path);
                    return null;
                }, ignored -> {
                    appendLog("Attached an optical drive to " + controller.name() + " on " + machine.name() + ".");
                    reload();
                });
                return;
            }
            if (createNew.isSelected() && createNew.isEnabled()) {
                int sizeMb;
                try {
                    sizeMb = Integer.parseInt(size.getText().trim());
                } catch (NumberFormatException exception) {
                    showError("The virtual disk size must be a whole number of megabytes.");
                    return;
                }
                runBackground("Creating and attaching virtual disk", () ->
                        client.createAndAttachHardDisk(machine, controller.name(), selectedPort, selectedDevice,
                                path, sizeMb, Objects.toString(format.getSelectedItem()), fixed.isSelected()),
                        created -> {
                            appendLog("Created and attached virtual disk " + created + ".");
                            reload();
                        });
                return;
            }
            if (path.isBlank()) {
                showError("Select an existing virtual hard-disk file to attach.");
                return;
            }
            runBackground("Attaching virtual disk", () -> {
                client.attachHardDisk(machine, controller.name(), selectedPort, selectedDevice, path);
                return null;
            }, ignored -> {
                appendLog("Attached a virtual disk to " + controller.name() + " on " + machine.name() + ".");
                reload();
            });
        }

        private int firstFreePort(StorageController controller) {
            for (int port = 0; port < Math.max(1, controller.maxPortCount()); port++) {
                int candidate = port;
                if (controller.attachments().stream().noneMatch(attachment -> attachment.port() == candidate)) {
                    return candidate;
                }
            }
            return 0;
        }

        private void removeSelectedAttachment() {
            if (!(selectedUserObject() instanceof AttachedMedium attached)) {
                return;
            }
            StorageAttachment attachment = attached.attachment();
            if (!confirmDestructiveAction("Detach " + attachment.displayName() + " from "
                    + attached.controllerName() + " (" + attachment.slot() + ")?", "Detach Medium")) {
                return;
            }
            runBackground("Detaching storage medium", () -> {
                client.detachStorageMedium(machine, attached.controllerName(), attachment.port(),
                        attachment.device());
                return null;
            }, ignored -> {
                appendLog("Detached a medium from " + machine.name() + ".");
                reload();
            });
        }

        private void mountSelectedImage(boolean mount) {
            if (!(selectedUserObject() instanceof AttachedMedium attached)) {
                return;
            }
            String image = "";
            if (mount) {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle("Select Disc Image");
                if (chooser.showOpenDialog(panel) != JFileChooser.APPROVE_OPTION) {
                    return;
                }
                image = chooser.getSelectedFile().getAbsolutePath();
            }
            String selectedImage = image;
            StorageAttachment attachment = attached.attachment();
            runBackground(mount ? "Mounting disc image" : "Ejecting disc image", () -> {
                client.attachOpticalMedium(machine, attached.controllerName(), attachment.port(),
                        attachment.device(), selectedImage);
                return null;
            }, ignored -> {
                appendLog((selectedImage.isBlank() ? "Ejected the medium in " : "Mounted an image in ")
                        + attached.controllerName() + " on " + machine.name() + ".");
                reload();
            });
        }
    }

    private record AttachedMedium(String controllerName, StorageAttachment attachment) {
    }

    private static final class StorageTreeRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
                                                      boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            setIcon(null);
            Object node = value instanceof DefaultMutableTreeNode treeNode ? treeNode.getUserObject() : value;
            if (node instanceof StorageController controller) {
                setText("Controller: " + controller.name());
                setFont(getFont().deriveFont(Font.BOLD));
            } else if (node instanceof AttachedMedium attached) {
                setText(attached.attachment().displayName());
                setFont(getFont().deriveFont(Font.PLAIN));
            }
            return this;
        }
    }

    private static String attachmentExplanation(String type) {
        return switch (type) {
            case "nat" -> "NAT gives the guest outbound network access. Configure port forwarding on Adapter 1 "
                    + "when a host service such as SSH must reach the guest.";
            case "bridged" -> "Bridged mode connects the guest directly to the physical LAN. Enter the host network "
                    + "interface name that VirtualBox reports.";
            case "hostonly" -> "Host-only mode creates a private host/guest network. Enter the configured VirtualBox "
                    + "host-only adapter name; combine it with NAT on another adapter for Internet access.";
            case "intnet" -> "Internal networking is isolated from the host and external network. Guests communicate "
                    + "only when they use the same internal-network name.";
            default -> "This adapter is disabled and does not participate in guest networking.";
        };
    }

    private void showNatPortForwardingDialog(VirtualMachine machine) {
        runBackground("Loading NAT port-forwarding rules", () -> client.natPortForwardRules(machine),
                rules -> showNatPortForwardingEditor(machine, rules));
    }

    private void showNatPortForwardingEditor(VirtualMachine machine, List<NatPortForwardRule> rules) {
        JTextArea currentRules = new JTextArea(formatNatRules(rules), 9, 54);
        currentRules.setEditable(false);
        currentRules.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JButton add = new JButton("Add…");
        JButton remove = new JButton("Remove…");
        JPanel actions = new JPanel();
        actions.add(add);
        actions.add(remove);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(new EmptyBorder(16, 20, 16, 20));
        content.add(new JScrollPane(currentRules), BorderLayout.CENTER);
        content.add(actions, BorderLayout.SOUTH);

        add.addActionListener(event -> showAddNatPortForwardRuleDialog(machine));
        remove.addActionListener(event -> {
            if (rules.isEmpty()) {
                showError("No NAT port-forwarding rules exist for this guest.");
                return;
            }
            JComboBox<String> names = new JComboBox<>(rules.stream().map(NatPortForwardRule::name).toArray(String[]::new));
            if (JOptionPane.showConfirmDialog(this, names, "Remove NAT Rule — " + machine.name(),
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
                return;
            }
            String name = Objects.toString(names.getSelectedItem());
            runBackground("Removing NAT port-forwarding rule", () -> {
                client.removeNatPortForwardRule(machine, name);
                return null;
            }, ignored -> {
                appendLog("Removed NAT rule '" + name + "' from " + machine.name() + ".");
                showNatPortForwardingDialog(machine);
            });
        });
        JOptionPane.showMessageDialog(this, content, "NAT Port Forwarding — " + machine.name(),
                JOptionPane.PLAIN_MESSAGE);
    }

    private void showAddNatPortForwardRuleDialog(VirtualMachine machine) {
        JTextField name = new JTextField(20);
        JComboBox<String> protocol = new JComboBox<>(new String[]{"tcp", "udp"});
        JTextField hostIp = new JTextField(18);
        JTextField hostPort = new JTextField(8);
        JTextField guestIp = new JTextField(18);
        JTextField guestPort = new JTextField(8);
        JLabel hint = formHelpText("Leave host/guest IP blank to bind all interfaces or use the NAT default.");
        JPanel content = formPanel(
                new String[]{"Rule name", "Protocol", "Host IP (optional)", "Host port", "Guest IP (optional)", "Guest port", ""},
                new Component[]{name, protocol, hostIp, hostPort, guestIp, guestPort, hint}
        );
        if (JOptionPane.showConfirmDialog(this, content, "Add NAT Rule — " + machine.name(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            NatPortForwardRule rule = new NatPortForwardRule(name.getText(), Objects.toString(protocol.getSelectedItem()),
                    hostIp.getText(), Integer.parseInt(hostPort.getText().trim()),
                    guestIp.getText(), Integer.parseInt(guestPort.getText().trim()));
            runBackground("Adding NAT port-forwarding rule", () -> {
                client.addNatPortForwardRule(machine, rule);
                return null;
            }, ignored -> {
                appendLog("Added NAT rule '" + rule.name() + "' to " + machine.name() + ".");
                showNatPortForwardingDialog(machine);
            });
        } catch (IllegalArgumentException exception) {
            showError(exception.getMessage());
        }
    }

    private static String formatNatRules(List<NatPortForwardRule> rules) {
        if (rules.isEmpty()) {
            return "(No NAT port-forwarding rules configured.)";
        }
        StringBuilder text = new StringBuilder("Name                 Protocol  Host                 Guest")
                .append(System.lineSeparator())
                .append("----------------------------------------------------------------")
                .append(System.lineSeparator());
        for (NatPortForwardRule rule : rules) {
            text.append(String.format("%-20s %-9s %-20s %s:%d",
                    rule.name(), rule.protocol(), endpoint(rule.hostIp(), rule.hostPort()),
                    rule.guestIp().isBlank() ? "guest" : rule.guestIp(), rule.guestPort()))
                    .append(System.lineSeparator());
        }
        return text.toString().trim();
    }

    private static String endpoint(String ip, int port) {
        return (ip == null || ip.isBlank() ? "*" : ip) + ":" + port;
    }

    private void showPortManagementDialog(VirtualMachine machine) {
        Object[] options = {"Serial port 1…", "Serial port 2…", "Parallel port…", "Cancel"};
        int choice = JOptionPane.showOptionDialog(this, "Choose a port to configure for '" + machine.name() + "'.",
                "Ports — " + machine.name(), JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, options, options[0]);
        if (choice == 0 || choice == 1) {
            showSerialPortDialog(machine, choice + 1);
        } else if (choice == 2) {
            showParallelPortDialog(machine);
        }
    }

    private void showSerialPortDialog(VirtualMachine machine, int portIndex) {
        runBackground("Loading serial port " + portIndex, () -> client.serialPortSettings(machine, portIndex),
                settings -> showSerialPortEditor(machine, portIndex, settings));
    }

    private void showSerialPortEditor(VirtualMachine machine, int portIndex, SerialPortSettings settings) {
        JCheckBox enabled = new JCheckBox("Enable serial port", settings.enabled());
        JComboBox<String> ioBase = new JComboBox<>(new String[]{"0x3f8", "0x2f8", "0x3e8", "0x2e8"});
        ioBase.setSelectedItem(settings.ioBase());
        JTextField irq = new JTextField(Integer.toString(settings.irq()), 5);
        JPanel content = formPanel(new String[]{"", "I/O base", "IRQ"},
                new Component[]{enabled, ioBase, irq});
        if (JOptionPane.showConfirmDialog(this, content, "Serial Port " + portIndex + " — " + machine.name(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            SerialPortSettings updated = new SerialPortSettings(enabled.isSelected(),
                    Objects.toString(ioBase.getSelectedItem()), Integer.parseInt(irq.getText().trim()), "disconnected");
            runBackground("Saving serial port " + portIndex, () -> {
                client.updateSerialPortSettings(machine, portIndex, updated);
                return null;
            }, ignored -> appendLog("Updated serial port " + portIndex + " for " + machine.name() + "."));
        } catch (IllegalArgumentException exception) {
            showError(exception.getMessage());
        }
    }

    private void showParallelPortDialog(VirtualMachine machine) {
        runBackground("Loading parallel port", () -> client.parallelPortSettings(machine),
                settings -> showParallelPortEditor(machine, settings));
    }

    private void showParallelPortEditor(VirtualMachine machine, ParallelPortSettings settings) {
        JCheckBox enabled = new JCheckBox("Enable parallel port", settings.enabled());
        JComboBox<String> ioBase = new JComboBox<>(new String[]{"0x378", "0x278", "0x3bc"});
        ioBase.setSelectedItem(settings.ioBase());
        JTextField irq = new JTextField(Integer.toString(settings.irq()), 5);
        JPanel content = formPanel(new String[]{"", "I/O base", "IRQ"},
                new Component[]{enabled, ioBase, irq});
        if (JOptionPane.showConfirmDialog(this, content, "Parallel Port — " + machine.name(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            ParallelPortSettings updated = new ParallelPortSettings(enabled.isSelected(),
                    Objects.toString(ioBase.getSelectedItem()), Integer.parseInt(irq.getText().trim()));
            runBackground("Saving parallel port", () -> {
                client.updateParallelPortSettings(machine, updated);
                return null;
            }, ignored -> appendLog("Updated parallel port for " + machine.name() + "."));
        } catch (IllegalArgumentException exception) {
            showError(exception.getMessage());
        }
    }

    private void showGuestIntegrationDialog(VirtualMachine machine) {
        runBackground("Loading guest integration settings", () -> client.guestIntegrationSettings(machine),
                settings -> showGuestIntegrationEditor(machine, settings));
    }

    private void showGuestIntegrationEditor(VirtualMachine machine, GuestIntegrationSettings settings) {
        String[] modes = {"disabled", "hosttoguest", "guesttohost", "bidirectional"};
        JComboBox<String> clipboard = new JComboBox<>(modes);
        clipboard.setSelectedItem(settings.clipboardMode());
        JComboBox<String> dragAndDrop = new JComboBox<>(modes);
        dragAndDrop.setSelectedItem(settings.dragAndDropMode());
        JLabel hint = formHelpText("These channels only operate when compatible Guest Additions are installed.");
        JPanel content = formPanel(new String[]{"Shared clipboard", "Drag and drop", ""},
                new Component[]{clipboard, dragAndDrop, hint});
        if (JOptionPane.showConfirmDialog(this, content, "Integration — " + machine.name(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            GuestIntegrationSettings updated = new GuestIntegrationSettings(
                    Objects.toString(clipboard.getSelectedItem()), Objects.toString(dragAndDrop.getSelectedItem()));
            runBackground("Saving guest integration settings", () -> {
                client.updateGuestIntegrationSettings(machine, updated);
                return null;
            }, ignored -> appendLog("Updated host/guest integration for " + machine.name() + "."));
        } catch (IllegalArgumentException exception) {
            showError(exception.getMessage());
        }
    }

    private void showUsbManagementDialog(VirtualMachine machine) {
        Object[] options = {"Controller…", "Device filters…", "Cancel"};
        int choice = JOptionPane.showOptionDialog(this, "Choose a USB configuration task for '" + machine.name() + "'.",
                "USB — " + machine.name(), JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, options, options[0]);
        if (choice == 0) {
            showUsbControllerDialog(machine);
        } else if (choice == 1) {
            showUsbDeviceFiltersDialog(machine);
        }
    }

    private void showUsbDeviceFiltersDialog(VirtualMachine machine) {
        runBackground("Loading USB device filters", () -> client.usbDeviceFilters(machine),
                filters -> showUsbDeviceFilterEditor(machine, filters));
    }

    private void showUsbDeviceFilterEditor(VirtualMachine machine, List<UsbDeviceFilter> filters) {
        JTextArea current = new JTextArea(formatUsbDeviceFilters(filters), 9, 52);
        current.setEditable(false);
        current.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JButton add = new JButton("Add…");
        JButton remove = new JButton("Remove…");
        JPanel actions = new JPanel();
        actions.add(add);
        actions.add(remove);
        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(new EmptyBorder(16, 20, 16, 20));
        content.add(new JScrollPane(current), BorderLayout.CENTER);
        content.add(actions, BorderLayout.SOUTH);

        add.addActionListener(event -> showAddUsbDeviceFilterDialog(machine));
        remove.addActionListener(event -> {
            if (filters.isEmpty()) {
                showError("No USB device filters exist for this guest.");
                return;
            }
            JComboBox<String> names = new JComboBox<>(filters.stream().map(UsbDeviceFilter::name).toArray(String[]::new));
            if (JOptionPane.showConfirmDialog(this, names, "Remove USB Filter — " + machine.name(),
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
                return;
            }
            String name = Objects.toString(names.getSelectedItem());
            runBackground("Removing USB device filter", () -> {
                client.removeUsbDeviceFilter(machine, name);
                return null;
            }, ignored -> {
                appendLog("Removed USB device filter '" + name + "' from " + machine.name() + ".");
                showUsbDeviceFiltersDialog(machine);
            });
        });
        JOptionPane.showMessageDialog(this, content, "USB Device Filters — " + machine.name(),
                JOptionPane.PLAIN_MESSAGE);
    }

    private void showAddUsbDeviceFilterDialog(VirtualMachine machine) {
        JTextField name = new JTextField(22);
        JCheckBox active = new JCheckBox("Enable this filter", true);
        JTextField vendorId = new JTextField(8);
        JTextField productId = new JTextField(8);
        JLabel hint = formHelpText("Vendor/product IDs are optional four-digit hexadecimal values, such as 046d.");
        JPanel content = formPanel(new String[]{"Name", "", "Vendor ID (optional)", "Product ID (optional)", ""},
                new Component[]{name, active, vendorId, productId, hint});
        if (JOptionPane.showConfirmDialog(this, content, "Add USB Device Filter — " + machine.name(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            UsbDeviceFilter filter = new UsbDeviceFilter(name.getText(), active.isSelected(),
                    vendorId.getText(), productId.getText());
            runBackground("Adding USB device filter", () -> {
                client.addUsbDeviceFilter(machine, filter);
                return null;
            }, ignored -> {
                appendLog("Added USB device filter '" + filter.name() + "' to " + machine.name() + ".");
                showUsbDeviceFiltersDialog(machine);
            });
        } catch (IllegalArgumentException exception) {
            showError(exception.getMessage());
        }
    }

    private static String formatUsbDeviceFilters(List<UsbDeviceFilter> filters) {
        if (filters.isEmpty()) {
            return "(No USB device filters configured.)";
        }
        StringBuilder result = new StringBuilder("Name                    Active  Vendor  Product")
                .append(System.lineSeparator())
                .append("---------------------------------------------------")
                .append(System.lineSeparator());
        for (UsbDeviceFilter filter : filters) {
            result.append(String.format("%-23s %-7s %-7s %s",
                    filter.name(), filter.active() ? "yes" : "no",
                    filter.vendorId().isBlank() ? "*" : filter.vendorId(),
                    filter.productId().isBlank() ? "*" : filter.productId()))
                    .append(System.lineSeparator());
        }
        return result.toString().trim();
    }

    private void showUsbControllerDialog(VirtualMachine machine) {
        runBackground("Loading USB controller settings", () -> client.usbControllerSettings(machine),
                settings -> showUsbControllerEditor(machine, settings));
    }

    private void showUsbControllerEditor(VirtualMachine machine, UsbControllerSettings settings) {
        JCheckBox enabled = new JCheckBox("Enable USB controller", settings.enabled());
        JComboBox<String> controller = new JComboBox<>(new String[]{"ohci", "ehci", "xhci"});
        controller.setSelectedItem(settings.controller());
        JLabel hint = formHelpText("OHCI = USB 1.1, EHCI = USB 2.0, xHCI = USB 3.0. EHCI/xHCI may require the Extension Pack.");
        JPanel content = formPanel(new String[]{"", "Controller", ""}, new Component[]{enabled, controller, hint});
        if (JOptionPane.showConfirmDialog(this, content, "USB — " + machine.name(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            UsbControllerSettings updated = new UsbControllerSettings(enabled.isSelected(),
                    Objects.toString(controller.getSelectedItem()));
            runBackground("Saving USB controller settings", () -> {
                client.updateUsbControllerSettings(machine, updated);
                return null;
            }, ignored -> appendLog("Updated USB controller for " + machine.name() + "."));
        } catch (IllegalArgumentException exception) {
            showError(exception.getMessage());
        }
    }

    private void showSharedFoldersDialog(VirtualMachine machine) {
        runBackground("Loading shared folders", () -> client.sharedFolders(machine),
                folders -> showSharedFolderEditor(machine, folders));
    }

    private void showSharedFolderEditor(VirtualMachine machine, List<SharedFolder> folders) {
        DefaultTableModel model = new DefaultTableModel(new String[]{"Name", "Host path", "Read-only", "Auto-mount"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        folders.forEach(folder -> model.addRow(new Object[]{folder.name(), folder.hostPath(),
                folder.readOnly() ? "Yes" : "No", folder.autoMount() ? "Yes" : "No"}));
        JTable table = new JTable(model);
        table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        JButton add = new JButton("Add...");
        JButton remove = new JButton("Remove");
        remove.setEnabled(!folders.isEmpty());
        JPanel actions = new JPanel();
        actions.add(add);
        actions.add(remove);
        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(new EmptyBorder(16, 20, 16, 20));
        content.add(new JScrollPane(table), BorderLayout.CENTER);
        content.add(actions, BorderLayout.SOUTH);

        add.addActionListener(event -> showAddSharedFolderDialog(machine));
        remove.addActionListener(event -> {
            int row = table.getSelectedRow();
            if (row < 0) {
                showError("Select a shared folder to remove.");
                return;
            }
            String name = Objects.toString(model.getValueAt(row, 0));
            runBackground("Removing shared folder", () -> {
                client.removeSharedFolder(machine, name);
                return null;
            }, ignored -> {
                appendLog("Removed shared folder '" + name + "' from " + machine.name() + ".");
                showSharedFoldersDialog(machine);
            });
        });
        JOptionPane.showMessageDialog(this, content, "Shared Folders - " + machine.name(), JOptionPane.PLAIN_MESSAGE);
    }

    private void showAddSharedFolderDialog(VirtualMachine machine) {
        JTextField name = new JTextField(22);
        JTextField path = new JTextField(28);
        JButton browse = new JButton("Browse…");
        browse.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Select Host Folder");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                path.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        JPanel pathPanel = new JPanel(new BorderLayout(6, 0));
        pathPanel.add(path, BorderLayout.CENTER);
        pathPanel.add(browse, BorderLayout.EAST);
        JCheckBox readOnly = new JCheckBox("Read-only", false);
        JCheckBox autoMount = new JCheckBox("Auto-mount in guest", true);
        JPanel content = formPanel(new String[]{"Name", "Host folder", "", ""},
                new Component[]{name, pathPanel, readOnly, autoMount});
        if (JOptionPane.showConfirmDialog(this, content, "Add Shared Folder — " + machine.name(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        if (name.getText().isBlank() || path.getText().isBlank()) {
            showError("Enter a shared-folder name and host folder.");
            return;
        }
        runBackground("Adding shared folder", () -> {
            client.addSharedFolder(machine, name.getText(), path.getText(), readOnly.isSelected(), autoMount.isSelected());
            return null;
        }, ignored -> {
            appendLog("Added shared folder '" + name.getText().trim() + "' to " + machine.name() + ".");
            showSharedFoldersDialog(machine);
        });
    }

    private static JPanel unsupportedSettingsPanel(String message) {
        JLabel heading = new JLabel("Not available in the current transport");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        JLabel detail = new JLabel("<html><body style='width:420px'>" + escapeHtml(message) + "</body></html>");
        detail.setForeground(MiraDarkTheme.DISABLED_FOREGROUND);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(new EmptyBorder(26, 28, 26, 28));
        content.add(heading, BorderLayout.NORTH);
        content.add(detail, BorderLayout.CENTER);
        return content;
    }

    private static String escapeHtml(String value) {
        String ampersand = Character.toString((char) 38);
        return value.replace(ampersand, ampersand + "amp;")
                .replace("<", ampersand + "lt;")
                .replace(">", ampersand + "gt;")
                .replace("\"", ampersand + "quot;");
    }

    private static String normalizeGroup(String group) {
        String value = group == null ? "" : group.trim();
        if (value.isBlank() || "/".equals(value)) {
            return "/";
        }
        return value.startsWith("/") ? value : "/" + value;
    }

    private void showTextDialog(String title, String text) {
        JTextArea area = new JTextArea(text == null || text.isBlank() ? "(No information returned.)" : text);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setCaretPosition(0);
        area.setBorder(new EmptyBorder(10, 12, 10, 12));

        JScrollPane scrollPane = new JScrollPane(area);
        scrollPane.setPreferredSize(new Dimension(780, 510));
        scrollPane.setMinimumSize(new Dimension(560, 320));
        JOptionPane.showMessageDialog(this, scrollPane, title, JOptionPane.INFORMATION_MESSAGE);
    }

    private void showNewGuestDialog() {
        if (!requireConnection()) {
            return;
        }

        JTextField name = new JTextField(22);
        JComboBox<String> osType = new JComboBox<>(new String[]{
                "Windows11_64", "Windows10_64", "Ubuntu_64", "Debian_64", "Fedora_64", "RedHat_64",
                "Oracle_64", "ArchLinux_64", "Other_64"
        });
        JTextField memory = new JTextField("2048", 8);
        JTextField cpus = new JTextField("2", 8);
        JComboBox<String> diskMode = new JComboBox<>(new String[]{"Create a new virtual disk", "Use an existing virtual disk", "Do not add a disk"});
        JTextField diskSize = new JTextField("20480", 8);
        JComboBox<String> diskFormat = new JComboBox<>(new String[]{"VDI", "VHD", "VMDK"});
        JCheckBox fixedDisk = new JCheckBox("Allocate the full disk size immediately", false);
        JTextField existingDisk = new JTextField(28);
        JButton browseDisk = new JButton("Browse...");
        browseDisk.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Select Existing Virtual Hard Disk");
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                existingDisk.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        JPanel existingDiskPanel = new JPanel(new BorderLayout(6, 0));
        existingDiskPanel.add(existingDisk, BorderLayout.CENTER);
        existingDiskPanel.add(browseDisk, BorderLayout.EAST);
        JTextField machineFolder = new JTextField(28);
        JButton browseFolder = new JButton("Browse...");
        browseFolder.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Select Virtual Machine Folder");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                machineFolder.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        JPanel folderPanel = new JPanel(new BorderLayout(6, 0));
        folderPanel.add(machineFolder, BorderLayout.CENTER);
        folderPanel.add(browseFolder, BorderLayout.EAST);
        JComboBox<String> controllerBus = new JComboBox<>(new String[]{"sata", "ide", "scsi", "sas", "pcie"});
        JTextField controllerName = new JTextField("SATA", 14);
        JTextField diskPort = new JTextField("0", 5);
        JTextField diskDevice = new JTextField("0", 5);
        JTextField installerIso = new JTextField(28);
        JButton browseIso = new JButton("Browse…");
        browseIso.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Select Installer ISO");
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                installerIso.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        JPanel isoPanel = new JPanel(new BorderLayout(6, 0));
        isoPanel.add(installerIso, BorderLayout.CENTER);
        isoPanel.add(browseIso, BorderLayout.EAST);
        JCheckBox vrdeEnabled = new JCheckBox("Enable remote display server", true);
        JTextField vrdePort = new JTextField("3389", 8);
        diskMode.addActionListener(event -> {
            int mode = diskMode.getSelectedIndex();
            diskSize.setEnabled(mode == 0);
            diskFormat.setEnabled(mode == 0);
            fixedDisk.setEnabled(mode == 0);
            existingDiskPanel.setEnabled(mode == 1);
            existingDisk.setEnabled(mode == 1);
            browseDisk.setEnabled(mode == 1);
        });
        diskMode.setSelectedIndex(0);
        JPanel content = formPanel(
                new String[]{"Name", "Operating system", "Memory (MB)", "Processors", "Machine folder (optional)",
                        "Startup disk", "New disk size (MB)", "Disk format", "", "Existing disk", "Controller bus",
                        "Controller name", "Disk port", "Disk device", "Installer ISO (optional)", "Remote display", "TCP port(s)"},
                new Component[]{name, osType, memory, cpus, folderPanel, diskMode, diskSize, diskFormat, fixedDisk,
                        existingDiskPanel, controllerBus, controllerName, diskPort, diskDevice, isoPanel, vrdeEnabled, vrdePort}
        );

        if (JOptionPane.showConfirmDialog(this, content, "Create Virtual Machine",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }

        try {
            NewMachineSpec.DiskMode selectedDiskMode = switch (diskMode.getSelectedIndex()) {
                case 1 -> NewMachineSpec.DiskMode.EXISTING;
                case 2 -> NewMachineSpec.DiskMode.NONE;
                default -> NewMachineSpec.DiskMode.NEW;
            };
            NewMachineSpec specification = new NewMachineSpec(
                    name.getText(), Objects.toString(osType.getSelectedItem()),
                    Integer.parseInt(memory.getText().trim()), Integer.parseInt(cpus.getText().trim()),
                    selectedDiskMode == NewMachineSpec.DiskMode.NEW ? Integer.parseInt(diskSize.getText().trim()) : 4,
                    Objects.toString(diskFormat.getSelectedItem()), fixedDisk.isSelected(), installerIso.getText(),
                    vrdeEnabled.isSelected(), vrdePort.getText(), selectedDiskMode, existingDisk.getText(),
                    machineFolder.getText(), Objects.toString(controllerName.getText()),
                    Objects.toString(controllerBus.getSelectedItem()), Integer.parseInt(diskPort.getText().trim()),
                    Integer.parseInt(diskDevice.getText().trim())
            );
            if (!isValidVrdePortSpecification(specification.vrdePort())) {
                throw new IllegalArgumentException();
            }
            runBackground("Creating VM", () -> {
                client.createMachine(specification);
                return specification.name();
            }, created -> {
                appendLog("Created guest: " + created);
                refreshMachines();
            });
        } catch (IllegalArgumentException exception) {
            showError("Enter a guest name, at least 4 MB memory, one or more processors, valid controller/slot values, "
                    + "and a valid remote-display port or port range. An existing disk is required when that mode is selected.");
        }
    }

    private void powerOffSelected() {
        VirtualMachine machine = selectedMachine();
        if (machine == null) {
            return;
        }
        if (!confirmDestructiveAction("Power off '" + machine.name() + "'? This is equivalent to pulling the power cable.",
                "Confirm Power Off")) {
            return;
        }
        runMachineAction("Powering off", VirtualBoxClient::powerOff);
    }

    private void resetSelected() {
        VirtualMachine machine = selectedMachine();
        if (machine == null) {
            return;
        }
        if (!confirmDestructiveAction("Reset '" + machine.name() + "'? The guest will reboot immediately.",
                "Confirm Reset")) {
            return;
        }
        runMachineAction("Resetting", VirtualBoxClient::reset);
    }

    /**
     * Shows a confirmation dialog for a destructive guest action unless the user has
     * disabled confirmations in Preferences.
     */
    private boolean confirmDestructiveAction(String message, String title) {
        if (!preferences.getBoolean("confirm.actions", true)) {
            return true;
        }
        return JOptionPane.showConfirmDialog(this, message, title,
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    private void pauseOrResume() {
        VirtualMachine machine = selectedMachine();
        if (machine == null) {
            return;
        }
        if (machine.isPaused()) {
            runMachineAction("Resuming", VirtualBoxClient::resume);
        } else {
            runMachineAction("Pausing", VirtualBoxClient::pause);
        }
    }

    private void openDisplay() {
        VirtualMachine machine = selectedMachine();
        if (machine == null || !requireConnection()) {
            return;
        }
        if (!machine.isRunning()) {
            showError("The guest must be running before its display can be opened.");
            return;
        }
        runBackground("Opening guest display", () -> client.showDisplay(machine),
                command -> appendLog("Opened the remote display client for " + machine.name() + ": " + command));
    }

    private void sendCtrlAltDelete() {
        VirtualMachine machine = selectedMachine();
        if (machine == null || !requireConnection()) {
            return;
        }
        if (!machine.isRunning()) {
            showError("The guest must be running before Ctrl-Alt-Del can be sent.");
            return;
        }
        runBackground("Sending Ctrl-Alt-Del to " + machine.name(), () -> {
            client.sendCtrlAltDelete(machine);
            return null;
        }, ignored -> appendLog("Sent Ctrl-Alt-Del to " + machine.name() + "."));
    }

    private void saveScreenshot() {
        VirtualMachine machine = selectedMachine();
        if (machine == null || !requireConnection()) {
            return;
        }
        if (!machine.isRunning()) {
            showError("The guest must be running before a screenshot can be captured.");
            return;
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save Guest Screenshot");
        chooser.setSelectedFile(new File(machine.name() + "_screenshot.png"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        Path destination = chooser.getSelectedFile().toPath().toAbsolutePath();
        if (Files.exists(destination)
                && JOptionPane.showConfirmDialog(this, "Replace existing file?\n" + destination,
                "Confirm Screenshot Replacement", JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }

        runBackground("Capturing screenshot for " + machine.name(), () -> {
            byte[] png = client.screenshotPng(machine);
            if (png.length == 0) {
                throw new VBoxException("VirtualBox returned an empty screenshot.");
            }
            try {
                Files.write(destination, png);
            } catch (IOException exception) {
                throw new VBoxException("Could not save the screenshot to " + destination + ".", exception);
            }
            return destination;
        }, saved -> appendLog("Saved screenshot of " + machine.name() + " to " + saved + "."));
    }

    private void takeSnapshot() {
        VirtualMachine machine = selectedMachine();
        if (machine == null || !requireConnection()) {
            return;
        }
        JTextField name = new JTextField("Snapshot", 22);
        JTextArea description = new JTextArea(4, 22);
        description.setLineWrap(true);
        description.setWrapStyleWord(true);
        JPanel content = formPanel(new String[]{"Name", "Description"},
                new Component[]{name, new JScrollPane(description)});
        if (JOptionPane.showConfirmDialog(this, content, "Take Snapshot — " + machine.name(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION
                && !name.getText().isBlank()) {
            runBackground("Taking snapshot", () -> {
                client.takeSnapshot(machine, name.getText().trim(), description.getText().trim());
                return name.getText().trim();
            }, snapshotName -> {
                appendLog("Created snapshot '" + snapshotName + "' for " + machine.name() + ".");
                refreshMachines();
            });
        }
    }

    private void showSnapshotDetails() {
        VirtualMachine machine = selectedMachine();
        if (machine == null || !requireConnection()) {
            return;
        }
        runBackground("Loading snapshot details", () -> client.snapshots(machine), snapshots -> {
            String details = snapshots.isEmpty()
                    ? "No snapshots exist for '" + machine.name() + "'."
                    : "Snapshots for " + machine.name() + System.lineSeparator() + System.lineSeparator()
                            + String.join(System.lineSeparator(), snapshots);
            showTextDialog("Snapshot Details — " + machine.name(), details);
        });
    }

    private void manageSnapshot(boolean delete) {
        VirtualMachine machine = selectedMachine();
        if (machine == null || !requireConnection()) {
            return;
        }
        runBackground("Loading snapshots", () -> client.snapshots(machine), snapshots -> {
            if (snapshots.isEmpty()) {
                showError("No snapshots exist for '" + machine.name() + "'.");
                return;
            }
            JComboBox<String> selector = new JComboBox<>(snapshots.toArray(String[]::new));
            String operation = delete ? "Delete" : "Restore";
            int result = JOptionPane.showConfirmDialog(this, selector,
                    operation + " Snapshot — " + machine.name(),
                    JOptionPane.OK_CANCEL_OPTION, delete ? JOptionPane.WARNING_MESSAGE : JOptionPane.QUESTION_MESSAGE);
            if (result != JOptionPane.OK_OPTION) {
                return;
            }
            String selected = Objects.toString(selector.getSelectedItem());
            if (delete && !confirmDestructiveAction("Delete snapshot '" + selected + "'?", "Confirm Snapshot Deletion")) {
                return;
            }
            runBackground(operation + " snapshot", () -> {
                if (delete) {
                    client.deleteSnapshot(machine, selected);
                } else {
                    client.restoreSnapshot(machine, selected);
                }
                return selected;
            }, snapshot -> {
                appendLog((delete ? "Deleted" : "Restored") + " snapshot '" + snapshot + "' for " + machine.name() + ".");
                refreshMachines();
            });
        });
    }

    private void removeSelectedMachine() {
        VirtualMachine machine = selectedMachine();
        if (machine == null || !requireConnection()) {
            return;
        }
        Object[] options = {"Remove only", destructiveOption("Delete all files"), "Cancel"};
        int choice = JOptionPane.showOptionDialog(this,
                "Remove '" + machine.name() + "' from VirtualBox?\nDeleting files cannot be undone.",
                "Remove Guest", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                null, options, options[2]);
        if (choice == 0 || choice == 1) {
            boolean deleteFiles = choice == 1;
            runBackground("Removing guest", () -> {
                client.unregister(machine, deleteFiles);
                return deleteFiles;
            }, deleted -> {
                appendLog((deleted ? "Removed and deleted " : "Removed ") + machine.name() + ".");
                refreshMachines();
            });
        }
    }

    private void showAbout() {
        JOptionPane.showMessageDialog(this,
                "<html><b>RemoteBox Java</b><br>"
                        + "A Maven/Java Swing VirtualBox client inspired by RemoteBox 3.7.<br><br>"
                        + "Uses VBoxManage locally or through a configured remote command prefix.<br>"
                        + "Example remote command: <code>ssh user@server VBoxManage</code></html>",
                "About " + APP_NAME, JOptionPane.INFORMATION_MESSAGE);
    }

    private void runMachineAction(String action, MachineAction operation) {
        VirtualMachine machine = selectedMachine();
        if (machine == null || !requireConnection()) {
            return;
        }
        if (!isActionAllowed(action, machine)) {
            showError(action + " is not available while '" + machine.name() + "' is "
                    + machine.displayState() + ".");
            return;
        }
        runBackground(action + " " + machine.name(), () -> {
            operation.execute(client, machine);
            return null;
        }, ignored -> {
            appendLog(action + " " + machine.name() + ".");
            refreshMachines();
        });
    }

    private static boolean isActionAllowed(String action, VirtualMachine machine) {
        return switch (action) {
            case "Starting" -> machine.canStart();
            case "Powering off", "Sending ACPI shutdown signal", "Saving state for", "Resetting" -> machine.canStop();
            case "Pausing" -> machine.isRunning();
            case "Resuming" -> machine.isPaused();
            case "Discarding saved state for" -> machine.isSaved();
            default -> true;
        };
    }

    private <T> void runBackground(String taskName, BackgroundTask<T> task, SuccessHandler<T> success) {
        if (busy) {
            // Without this the request would vanish with no trace of why nothing happened.
            appendLog(taskName + " was skipped because another operation is still running.");
            return;
        }
        busy = true;
        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
        updateActionState();

        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return task.run();
            }

            @Override
            protected void done() {
                busy = false;
                setCursor(java.awt.Cursor.getDefaultCursor());
                updateActionState();
                try {
                    success.accept(get());
                } catch (Exception exception) {
                    Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                    appendLog(taskName + " failed: " + cause.getMessage());
                    showError(taskName + " failed.\n\n" + cause.getMessage());
                }
                updateActionState();
            }
        }.execute();
    }

    private void updateSelection() {
        VirtualMachine machine = selectedMachine();
        detailsArea.setText(machine == null ? "" : machineDetails(machine, extendedDetails.isSelected()));
        detailsArea.setCaretPosition(0);
        updateSnapshotPreview(machine);
        updateActionState();
    }

    private void updateSnapshotPreview(VirtualMachine machine) {
        long generation = ++snapshotPreviewGeneration;
        if (machine == null) {
            snapshotsArea.setText("Select a guest to view its snapshots.");
            return;
        }
        VirtualBoxClient snapshotClient = client;
        if (snapshotClient == null) {
            snapshotsArea.setText("Connect to a VirtualBox host to view snapshots.");
            return;
        }

        String machineId = machine.id();
        snapshotsArea.setText("Loading snapshots for " + machine.name() + "…");
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() throws Exception {
                return snapshotClient.snapshots(machine);
            }

            @Override
            protected void done() {
                if (generation != snapshotPreviewGeneration) {
                    return;
                }
                VirtualMachine selected = selectedMachine();
                if (client != snapshotClient || selected == null || !machineId.equals(selected.id())) {
                    return;
                }
                try {
                    List<String> snapshots = get();
                    String text = snapshots.isEmpty()
                            ? "No snapshots exist for '" + machine.name() + "'."
                            : "Snapshots for " + machine.name() + System.lineSeparator() + System.lineSeparator()
                                    + String.join(System.lineSeparator(), snapshots);
                    snapshotsArea.setText(text);
                    snapshotsArea.setCaretPosition(0);
                } catch (Exception exception) {
                    Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                    snapshotsArea.setText("Could not load snapshots: " + cause.getMessage());
                }
            }
        }.execute();
    }

    private void updateActionState() {
        VirtualMachine machine = selectedMachine();
        boolean connected = client != null && !busy;
        boolean selected = connected && machine != null;
        if (startButton != null) {
            startButton.setEnabled(selected && machine.canStart());
            stopButton.setEnabled(selected && machine.canStop());
            if (pauseButton != null) {
                pauseButton.setEnabled(selected && (machine.isRunning() || machine.isPaused()));
                pauseButton.setText(machine != null && machine.isPaused() ? "▶ Resume" : "Ⅱ Pause");
            }
            resetButton.setEnabled(selected && machine.canStop());
            displayButton.setEnabled(selected && machine.isRunning());
            settingsButton.setEnabled(selected);
        }
        // Keep the Guest menu accessible so "New guest…" remains available
        // even when no existing virtual machine is selected.
    }

    private VirtualMachine selectedMachine() {
        TreePath selection = machineTree.getSelectionPath();
        if (selection == null || !(selection.getLastPathComponent() instanceof DefaultMutableTreeNode node)) {
            return null;
        }
        Object value = node.getUserObject();
        return value instanceof VirtualMachine machine ? machine : null;
    }

    private void restoreSelection(String id) {
        if (id == null) {
            return;
        }
        TreePath path = machineModel.pathForMachine(id);
        if (path != null) {
            machineTree.setSelectionPath(path);
            machineTree.scrollPathToVisible(path);
        }
    }

    private void expandGuestFolders() {
        for (int row = 0; row < machineTree.getRowCount(); row++) {
            machineTree.expandRow(row);
        }
    }

    private boolean requireConnection() {
        if (client != null) {
            return true;
        }
        showError("Connect to a VirtualBox host first.");
        return false;
    }

    private void appendLog(String message) {
        logArea.append("[" + LocalDateTime.now().format(LOG_TIME) + "] " + message + System.lineSeparator());
        trimLog();
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    /** An unbounded log slows the text area down over a long-running session. */
    private void trimLog() {
        int lines = logArea.getLineCount();
        if (lines <= MAX_LOG_LINES) {
            return;
        }
        try {
            logArea.replaceRange("", 0, logArea.getLineEndOffset(lines - MAX_LOG_LINES - 1));
        } catch (javax.swing.text.BadLocationException ignored) {
            logArea.setText("");
        }
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, APP_NAME, JOptionPane.ERROR_MESSAGE);
    }

    private JButton remoteBoxToolButton(String text, String iconName, java.awt.event.ActionListener listener) {
        JButton button = new JButton("<html><center>" + text + "</center></html>", icon(iconName, 32));
        button.setHorizontalTextPosition(SwingConstants.CENTER);
        button.setVerticalTextPosition(SwingConstants.BOTTOM);
        button.setIconTextGap(2);
        button.setFocusable(false);
        button.setBorderPainted(false);
        button.setOpaque(true);
        button.setContentAreaFilled(true);
        button.setBackground(MiraDarkTheme.PANEL_BACKGROUND);
        button.setMargin(new Insets(2, 9, 2, 9));
        button.setBorder(new EmptyBorder(3, 9, 3, 9));
        button.setToolTipText(text);
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                if (button.isEnabled()) {
                    button.setBackground(MiraDarkTheme.TOOLBAR_HOVER);
                    button.setBorder(new EmptyBorder(3, 9, 3, 9));
                }
            }

            @Override
            public void mouseExited(MouseEvent event) {
                button.setBackground(MiraDarkTheme.PANEL_BACKGROUND);
                button.setBorder(new EmptyBorder(3, 9, 3, 9));
            }

            @Override
            public void mousePressed(MouseEvent event) {
                if (button.isEnabled() && SwingUtilities.isLeftMouseButton(event)) {
                    button.setBackground(MiraDarkTheme.TOOLBAR_PRESSED);
                    // Shift the visual content down one pixel while pressed.
                    button.setBorder(new EmptyBorder(4, 9, 2, 9));
                }
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                if (button.isEnabled()) {
                    button.setBackground(button.contains(event.getPoint())
                            ? MiraDarkTheme.TOOLBAR_HOVER : MiraDarkTheme.PANEL_BACKGROUND);
                    button.setBorder(new EmptyBorder(3, 9, 3, 9));
                }
            }
        });
        button.addActionListener(listener);
        return button;
    }

    /**
     * Returns a dark-theme-safe vector icon. The original RemoteBox PNG artwork
     * targets a pale background and loses contrast on Mira Dark; drawing simple,
     * semantic glyphs keeps toolbar and state icons sharp at every UI scale.
     */
    private static Icon icon(String name, int size) {
        return new RemoteBoxIcon(name, size);
    }

    private static final class RemoteBoxIcon implements Icon {
        private static final Color COMMAND = Color.decode("#6FA8FF");
        private static final Color MUTED = Color.decode("#A8ABB0");
        private static final Color SUCCESS = Color.decode("#5CB85C");
        private static final Color WARNING = Color.decode("#E5A11E");
        private static final Color ERROR = Color.decode("#E55353");
        private static final Color SAVED = Color.decode("#B994E6");

        private final String name;
        private final int size;

        private RemoteBoxIcon(String name, int size) {
            this.name = name;
            this.size = size;
        }

        @Override
        public void paintIcon(Component component, java.awt.Graphics graphics, int x, int y) {
            Graphics2D graphics2d = (Graphics2D) graphics.create();
            try {
                graphics2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                graphics2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                float stroke = Math.max(1.35f, size / 16f);
                graphics2d.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

                int inset = Math.max(2, size / 7);
                int left = x + inset;
                int top = y + inset;
                int width = size - inset * 2;
                int right = left + width;
                int bottom = top + width;
                int middleX = (left + right) / 2;
                int middleY = (top + bottom) / 2;

                boolean disabled = component != null && !component.isEnabled();
                if (name.endsWith(".png") && Character.isUpperCase(name.charAt(0))) {
                    paintStateIcon(graphics2d, left, top, width, middleX, middleY, disabled);
                    return;
                }
                graphics2d.setColor(disabled ? MUTED.darker() : commandColor());
                paintCommandIcon(graphics2d, left, top, right, bottom, middleX, middleY, width);
            } finally {
                graphics2d.dispose();
            }
        }

        private Color commandColor() {
            return switch (name) {
                case "vm_start_32px.png" -> SUCCESS;
                case "stop_32px.png" -> ERROR;
                case "reset_32px.png", "vm_discard_32px.png" -> WARNING;
                default -> COMMAND;
            };
        }

        private void paintStateIcon(Graphics2D graphics, int left, int top, int width, int middleX, int middleY,
                                    boolean disabled) {
            Color color = disabled ? MUTED.darker() : switch (name) {
                case "Running.png" -> SUCCESS;
                case "Paused.png" -> WARNING;
                case "Saved.png" -> SAVED;
                case "Aborted.png", "Error.png" -> ERROR;
                case "Saving.png", "Restoring.png", "Discarding.png" -> COMMAND;
                default -> MUTED;
            };
            graphics.setColor(color);
            if ("Running.png".equals(name)) {
                int[] xs = {left + width / 3, left + width / 3, left + width * 3 / 4};
                int[] ys = {top + width / 4, top + width * 3 / 4, middleY};
                graphics.fillPolygon(xs, ys, 3);
            } else if ("Paused.png".equals(name)) {
                int bar = Math.max(3, width / 5);
                graphics.fillRoundRect(left + width / 4, top + width / 5, bar, width * 3 / 5, bar, bar);
                graphics.fillRoundRect(left + width * 3 / 5, top + width / 5, bar, width * 3 / 5, bar, bar);
            } else if ("Saved.png".equals(name)) {
                graphics.drawRoundRect(left + 1, top + 1, width - 2, width - 2, 3, 3);
                graphics.fillRoundRect(left + width / 4, top + width / 5, width / 2, width / 4, 2, 2);
                graphics.drawLine(left + width / 4, top + width * 3 / 4, left + width * 3 / 4, top + width * 3 / 4);
            } else if ("Aborted.png".equals(name) || "Error.png".equals(name)) {
                graphics.drawLine(left + width / 4, top + width / 4, left + width * 3 / 4, top + width * 3 / 4);
                graphics.drawLine(left + width * 3 / 4, top + width / 4, left + width / 4, top + width * 3 / 4);
            } else {
                graphics.drawRoundRect(left + 1, top + 1, width - 2, width - 2, 4, 4);
                graphics.drawLine(left + width / 4, middleY, left + width * 3 / 4, middleY);
            }
        }

        private void paintCommandIcon(Graphics2D graphics, int left, int top, int right, int bottom,
                                      int middleX, int middleY, int width) {
            switch (name) {
                case "connect_32px.png" -> {
                    graphics.drawOval(left, top, width, width);
                    graphics.drawLine(left + width / 4, middleY, right - width / 4, middleY);
                    graphics.drawLine(middleX, top + width / 4, middleX, bottom - width / 4);
                }
                case "vm_new_32px.png", "dialog_create_new_guest_16px.png" -> {
                    graphics.drawRoundRect(left + 1, top + 1, width - 2, width - 2, 4, 4);
                    graphics.drawLine(middleX, top + width / 4, middleX, bottom - width / 4);
                    graphics.drawLine(left + width / 4, middleY, right - width / 4, middleY);
                }
                case "vm_settings_32px.png", "rb_settings_16px.png" -> {
                    graphics.drawOval(left + width / 4, top + width / 4, width / 2, width / 2);
                    for (int index = 0; index < 8; index++) {
                        double angle = Math.PI * index / 4d;
                        int x1 = middleX + (int) (Math.cos(angle) * width / 3.2);
                        int y1 = middleY + (int) (Math.sin(angle) * width / 3.2);
                        int x2 = middleX + (int) (Math.cos(angle) * width / 2.2);
                        int y2 = middleY + (int) (Math.sin(angle) * width / 2.2);
                        graphics.drawLine(x1, y1, x2, y2);
                    }
                }
                case "vm_start_32px.png" -> {
                    int[] xs = {left + width / 3, left + width / 3, right - width / 5};
                    int[] ys = {top + width / 4, bottom - width / 4, middleY};
                    graphics.fillPolygon(xs, ys, 3);
                }
                case "vrdp_32px.png" -> {
                    int screenTop = top + width / 6;
                    int screenHeight = width * 3 / 5;
                    int screenBottom = screenTop + screenHeight;
                    int screenMiddleY = screenTop + screenHeight / 2;
                    int baseY = bottom - width / 8;
                    graphics.drawRoundRect(left, screenTop, width, screenHeight, 4, 4);
                    graphics.drawLine(middleX, screenBottom, middleX, baseY);
                    graphics.drawLine(middleX - width / 5, baseY, middleX + width / 5, baseY);
                    int play = Math.max(2, width / 6);
                    graphics.fillPolygon(new int[]{middleX - play, middleX - play, middleX + play},
                            new int[]{screenMiddleY - play, screenMiddleY + play, screenMiddleY}, 3);
                }
                case "stop_32px.png" -> graphics.fillRoundRect(left + width / 4, top + width / 4,
                        width / 2, width / 2, 3, 3);
                case "reset_32px.png", "refresh_32px.png" -> {
                    graphics.drawArc(left + width / 6, top + width / 6, width * 2 / 3, width * 2 / 3, 38, 286);
                    graphics.fillPolygon(new int[]{right - width / 5, right - width / 5, right - width / 2},
                            new int[]{top + width / 5, top + width / 2, top + width / 5}, 3);
                }
                case "keyboard_32px.png" -> {
                    graphics.drawRoundRect(left + 1, top + width / 4, width - 2, width / 2, 4, 4);
                    for (int row = 0; row < 2; row++) {
                        for (int column = 0; column < 4; column++) {
                            int keyX = left + width / 5 + column * width / 6;
                            int keyY = top + width / 3 + row * width / 6;
                            graphics.fillRect(keyX, keyY, Math.max(1, width / 12), Math.max(1, width / 12));
                        }
                    }
                }
                case "vm_discard_32px.png" -> {
                    graphics.drawRoundRect(left + width / 4, top + width / 5, width / 2, width * 3 / 5, 3, 3);
                    graphics.drawLine(left + width / 5, top + width / 5, right - width / 5, top + width / 5);
                    graphics.drawLine(middleX, top + width / 8, middleX, top + width / 4);
                }
                case "machine_16px.png" -> {
                    graphics.drawRoundRect(left, top + width / 5, width, width * 3 / 5, 3, 3);
                    graphics.fillOval(left + width / 5, top + width / 2, Math.max(2, width / 7), Math.max(2, width / 7));
                }
                case "show_logs_16px.png" -> {
                    graphics.drawRoundRect(left + 1, top + 1, width - 2, width - 2, 3, 3);
                    for (int row = 0; row < 3; row++) {
                        int lineY = top + width / 4 + row * width / 4;
                        graphics.drawLine(left + width / 4, lineY, right - width / 5, lineY);
                    }
                }
                case "snapshot_manager_16px.png" -> {
                    graphics.drawOval(left + 1, top + 1, width - 2, width - 2);
                    graphics.drawLine(middleX, top + width / 4, middleX, middleY);
                    graphics.drawLine(middleX, middleY, right - width / 4, middleY);
                }
                default -> {
                    graphics.drawRoundRect(left + 1, top + 1, width - 2, width - 2, 4, 4);
                    graphics.drawLine(left + width / 4, middleY, right - width / 4, middleY);
                }
            }
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }
    }

    private static JTabbedPane darkTabbedPane() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.setOpaque(true);
        tabs.setBackground(MiraDarkTheme.BACKGROUND);
        tabs.setForeground(MiraDarkTheme.FOREGROUND);
        tabs.putClientProperty("JTabbedPane.tabAreaBackground", MiraDarkTheme.BACKGROUND);
        tabs.putClientProperty("JTabbedPane.selectedBackground", MiraDarkTheme.BACKGROUND);
        return tabs;
    }

    private static JScrollPane darkScrollPane(Component view) {
        JScrollPane scrollPane = new JScrollPane(view);
        scrollPane.setOpaque(true);
        scrollPane.setBackground(MiraDarkTheme.BACKGROUND);
        scrollPane.getViewport().setOpaque(true);
        scrollPane.getViewport().setBackground(MiraDarkTheme.BACKGROUND);
        return scrollPane;
    }

    private static JButton destructiveOption(String text) {
        JButton button = new JButton(text);
        button.setForeground(Color.decode("#E55353"));
        button.setToolTipText("This permanently deletes the guest's registered files.");
        return button;
    }

    private static JMenuItem menuItem(String text, java.awt.event.ActionListener listener) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(listener);
        return item;
    }

    private static JLabel formHelpText(String text) {
        JLabel help = new JLabel(text);
        help.setForeground(MiraDarkTheme.DISABLED_FOREGROUND);
        help.setBorder(new EmptyBorder(1, 1, 1, 0));
        return help;
    }

    private static JPanel formPanel(String[] labels, Component[] components) {
        JLabel[] labelComponents = new JLabel[labels.length];
        for (int index = 0; index < labels.length; index++) {
            labelComponents[index] = labels[index].isBlank() ? new JLabel() : new JLabel(labels[index] + ":");
        }
        return formPanel(labelComponents, components);
    }

    private static JPanel formPanel(JLabel[] labels, Component[] components) {
        if (labels.length != components.length) {
            throw new IllegalArgumentException("Every form component must have a corresponding label.");
        }

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(16, 22, 16, 22));

        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.anchor = GridBagConstraints.LINE_END;
        labelConstraints.insets = new Insets(5, 0, 5, 16);

        GridBagConstraints componentConstraints = new GridBagConstraints();
        componentConstraints.gridx = 1;
        componentConstraints.weightx = 1;
        componentConstraints.fill = GridBagConstraints.HORIZONTAL;
        componentConstraints.anchor = GridBagConstraints.LINE_START;
        componentConstraints.insets = new Insets(5, 0, 5, 0);

        for (int index = 0; index < labels.length; index++) {
            JLabel label = labels[index];
            Component component = components[index];

            labelConstraints.gridy = index;
            componentConstraints.gridy = index;
            label.setLabelFor(component);
            panel.add(label, labelConstraints);

            if (component instanceof JComponent swingComponent) {
                swingComponent.setAlignmentX(Component.LEFT_ALIGNMENT);
            }
            if (component instanceof JScrollPane scrollPane) {
                Dimension preferred = scrollPane.getPreferredSize();
                scrollPane.setPreferredSize(new Dimension(
                        Math.max(300, preferred.width),
                        Math.max(96, preferred.height)
                ));
            }

            boolean compactTextField = component instanceof JTextField
                    && component.getPreferredSize().width <= 130;
            componentConstraints.weightx = compactTextField ? 0 : 1;
            componentConstraints.fill = compactTextField ? GridBagConstraints.NONE : GridBagConstraints.HORIZONTAL;
            panel.add(component, componentConstraints);
        }

        panel.add(Box.createVerticalGlue(), fillerConstraints(labels.length));
        return panel;
    }

    /**
     * Bottom filler that absorbs the leftover height so form rows stay top-aligned.
     */
    private static GridBagConstraints fillerConstraints(int gridy) {
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridx = 0;
        filler.gridy = gridy;
        filler.gridwidth = 2;
        filler.weightx = 1;
        filler.weighty = 1;
        filler.fill = GridBagConstraints.BOTH;
        return filler;
    }

    private static String machineDetails(VirtualMachine machine, boolean includeExtended) {
        String details = """
                Name:          %s
                UUID:          %s
                State:         %s
                Operating OS:  %s
                Memory:        %d MB
                Processors:    %d
                Group:         %s
                VRDE Port:     %s

                Description:
                %s
                """.formatted(
                machine.name(),
                machine.id(),
                machine.displayState(),
                machine.osType(),
                machine.memoryMb(),
                machine.cpuCount(),
                machine.displayGroup(),
                machine.vrdePort().isBlank() ? "Not configured" : machine.vrdePort(),
                machine.description().isBlank() ? "(No description)" : machine.description()
        );
        if (!includeExtended) {
            return details;
        }
        return details + """

                Extended information:
                Raw state:     %s
                Can start:     %s
                Can stop:      %s
                Saved state:   %s
                """.formatted(machine.state(), machine.canStart(), machine.canStop(), machine.isSaved());
    }

    private record SettingsPageData(MachineSettings settings, List<NetworkAdapterSettings> networkAdapters,
                                    AudioSettings audioSettings, GuestIntegrationSettings integrationSettings,
                                    List<SerialPortSettings> serialPorts, ParallelPortSettings parallelPortSettings,
                                    UsbControllerSettings usbControllerSettings,
                                    List<StorageController> storageControllers,
                                    MotherboardSettings motherboardSettings,
                                    DisplaySettings displaySettings, boolean commandTransport, Map<String, String> loadErrors) {
        private boolean hasLoadError(String page) {
            return loadErrors.containsKey(page);
        }

        private String loadError(String page) {
            return loadErrors.get(page);
        }
    }

    private record ConnectedClient(VirtualBoxClient client, String version, String transport,
                                   String endpoint, String command) {
        String description() {
            return "web-service".equals(transport) ? endpoint : command;
        }
    }

    private static void installLookAndFeel() {
        try (InputStream theme = RemoteBoxApplication.class.getResourceAsStream("/MiraDark.properties")) {
            if (theme == null) {
                throw new IllegalStateException("MiraDark.properties is missing from the application resources.");
            }
            UIManager.setLookAndFeel(new FlatPropertiesLaf("Mira Dark", theme));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not install the autoMATE Mira Dark look and feel.", exception);
        }
    }

    /**
     * Values used directly by RemoteBox-specific renderers and borders.
     * The complete component styling is supplied by the copied autoMATE
     * MiraDark.properties resource through FlatLaf.
     */
    private static final class MiraDarkTheme {
        private static final Color BACKGROUND = Color.decode("#1E1F22");
        private static final Color DISABLED_FOREGROUND = Color.decode("#6F7174");
        private static final Color FOREGROUND = Color.decode("#E6E6E6");
        private static final Color SELECTION_BACKGROUND = Color.decode("#1E5BC6");
        private static final Color SELECTION_FOREGROUND = Color.WHITE;
        private static final Color PANEL_BACKGROUND = Color.decode("#2B2D30");
        private static final Color TOOLBAR_HOVER = Color.decode("#2F3338");
        private static final Color TOOLBAR_PRESSED = Color.decode("#26292D");
        private static final Color BORDER_COLOR = Color.decode("#3C3F44");

        private MiraDarkTheme() {
        }
    }

    @FunctionalInterface
    private interface SettingsLoader<T> {
        T load() throws Exception;
    }

    @FunctionalInterface
    private interface BackgroundTask<T> {
        T run() throws Exception;
    }

    @FunctionalInterface
    private interface SuccessHandler<T> {
        void accept(T value);
    }

    @FunctionalInterface
    private interface MediaOperation {
        void run(VirtualMedia media) throws Exception;
    }

    @FunctionalInterface
    private interface MachineAction {
        void execute(VirtualBoxClient manager, VirtualMachine machine) throws VBoxException;
    }

    private static final class GuestTreeModel extends DefaultTreeModel {
        private final DefaultMutableTreeNode root;
        private final Map<String, TreePath> machinePaths = new LinkedHashMap<>();

        private GuestTreeModel() {
            this(new DefaultMutableTreeNode("Guests"));
        }

        private GuestTreeModel(DefaultMutableTreeNode root) {
            super(root);
            this.root = root;
        }

        void setMachines(List<VirtualMachine> machines) {
            root.removeAllChildren();
            machinePaths.clear();
            Map<String, DefaultMutableTreeNode> groups = new LinkedHashMap<>();
            groups.put("/", root);

            machines.stream()
                    .sorted(Comparator.comparing(VirtualMachine::groups)
                            .thenComparing(VirtualMachine::name, String.CASE_INSENSITIVE_ORDER))
                    .forEach(machine -> {
                        DefaultMutableTreeNode parent = root;
                        String groupPath = machine.groups() == null ? "/" : machine.groups().trim();
                        if (!groupPath.isBlank() && !"/".equals(groupPath)) {
                            StringBuilder path = new StringBuilder();
                            for (String segment : groupPath.split("/")) {
                                if (segment.isBlank()) {
                                    continue;
                                }
                                path.append('/').append(segment);
                                String key = path.toString();
                                DefaultMutableTreeNode folder = groups.get(key);
                                if (folder == null) {
                                    folder = new DefaultMutableTreeNode(segment);
                                    groups.put(key, folder);
                                    parent.add(folder);
                                }
                                parent = folder;
                            }
                        }
                        DefaultMutableTreeNode guest = new DefaultMutableTreeNode(machine, false);
                        parent.add(guest);
                        machinePaths.put(machine.id(), new TreePath(guest.getPath()));
                    });
            reload();
        }

        TreePath pathForMachine(String id) {
            return machinePaths.get(id);
        }

        List<VirtualMachine> machines() {
            List<VirtualMachine> result = new ArrayList<>();
            collectMachines(root, result);
            return result;
        }

        private static void collectMachines(DefaultMutableTreeNode node, List<VirtualMachine> result) {
            Object value = node.getUserObject();
            if (value instanceof VirtualMachine machine) {
                result.add(machine);
            }
            for (int index = 0; index < node.getChildCount(); index++) {
                collectMachines((DefaultMutableTreeNode) node.getChildAt(index), result);
            }
        }
    }

    private static final class GuestTreeRenderer extends DefaultTreeCellRenderer {
        private GuestTreeRenderer() {
            setOpaque(true);
            setBackgroundNonSelectionColor(MiraDarkTheme.BACKGROUND);
            setBackgroundSelectionColor(MiraDarkTheme.SELECTION_BACKGROUND);
            setTextNonSelectionColor(MiraDarkTheme.FOREGROUND);
            setTextSelectionColor(MiraDarkTheme.SELECTION_FOREGROUND);
            setBorderSelectionColor(MiraDarkTheme.SELECTION_BACKGROUND);
        }

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
                                                      boolean leaf, int row, boolean focused) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focused);
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
            Object item = node.getUserObject();
            if (item instanceof VirtualMachine machine) {
                String iconName = switch (machine.state().toLowerCase(java.util.Locale.ROOT)) {
                    case "running" -> "Running.png";
                    case "saved" -> "Saved.png";
                    case "paused" -> "Paused.png";
                    case "aborted" -> "Aborted.png";
                    default -> "PoweredOff.png";
                };
                setText("<html><b>" + escapeHtml(machine.name()) + "</b><br><span style='color:#6F7174'>"
                        + escapeHtml(machine.displayState()) + "</span></html>");
                setIcon(icon(iconName, 28));
                setBorder(new EmptyBorder(3, 6, 3, 4));
            } else {
                setText(escapeHtml(Objects.toString(item)));
                setIcon(icon("machine_16px.png", 16));
                setBorder(new EmptyBorder(2, 4, 2, 4));
            }
            setBackground(selected ? MiraDarkTheme.SELECTION_BACKGROUND : MiraDarkTheme.BACKGROUND);
            return this;
        }
    }
}
