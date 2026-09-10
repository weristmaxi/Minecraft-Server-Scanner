package dev.jqve.serverscanner.screens;

import dev.jqve.serverscanner.mixin.MultiplayerScreenInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public class PortScannerScreen extends Screen {
    private final Screen parent;
    private static final Logger LOGGER = LogManager.getLogger(PortScannerScreen.class);

    // Port scanning settings
    private static final int TIMEOUT_MS = 150;
    private static final int THREAD_POOL_SIZE = 25;
    private static final Pattern IP_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
    );

    // Text fields and button references
    private EditBox ipTextField;
    private EditBox startPortTextField;
    private EditBox endPortTextField;
    private Button scanButton;

    // Status text to display scanning progress or errors
    private Component statusText;

    // Collected ports and related server info
    private final List<Integer> openPorts = new ArrayList<>();
    private final List<ServerData> foundServers = new ArrayList<>();
    private ExecutorService executorService;
    private boolean isScanning = false;

    // UI layout constants
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_SPACING = 4;
    private static final int RESULTS_START_Y = 120;

    // Dynamic layout variables
    private int buttonsPerRow;
    private int dynamicButtonWidth;

    public PortScannerScreen(Screen parent) {
        super(Component.literal("Minecraft Server Scanner"));
        this.parent = parent;
    }

    @Override
    public void onClose() {
        ((MultiplayerScreenInvoker) parent).invokeRefreshServerList();
    }

    @Override
    protected void init() {
        // Calculate layout metrics before placing fields and buttons
        calculateLayoutMetrics();
        initializeTextFields();
        initializeButtons();
        statusText = Component.literal("");
    }

    /**
     * Computes how many buttons fit per row and then reduces button width by 50%.
     */
    private void calculateLayoutMetrics() {
        int totalWidth = this.width - 40; // 20 px padding on each side
        if (totalWidth < 1) totalWidth = 1;

        // Approximating each button at ~200 px + spacing
        int approximateButtonWidth = 200;
        buttonsPerRow = Math.max(1, totalWidth / (approximateButtonWidth + BUTTON_SPACING));

        // Calculate how much space is left for the buttons plus spacing
        int totalSpacing = BUTTON_SPACING * (buttonsPerRow - 1);
        int spaceForButtons = totalWidth - totalSpacing;

        // First determine a "full" width, then reduce by 50%
        int fullWidth = Math.max(50, spaceForButtons / buttonsPerRow);
        dynamicButtonWidth = (int) (fullWidth * 0.5); // reduce by 50%
    }

    private void initializeTextFields() {
        // IP Address field
        this.ipTextField = new EditBox(
                this.font,
                this.width / 2 - 100,
                20,
                200,
                20,
                Component.literal("IP Address")
        );
        this.ipTextField.setMaxLength(15);
        this.ipTextField.setValue("127.0.0.1");
        // Added basic tooltip
        this.ipTextField.setTooltip(Tooltip.create(Component.literal("Enter the IP address to scan (e.g., 127.0.0.1)")));
        this.addRenderableWidget(ipTextField);

        // Start Port field
        this.startPortTextField = new EditBox(
                this.font,
                this.width / 2 - 100,
                50,
                90,
                20,
                Component.literal("Start Port")
        );
        this.startPortTextField.setMaxLength(5);
        this.startPortTextField.setValue("1");
        // Added tooltip for clarity
        this.startPortTextField.setTooltip(Tooltip.create(Component.literal("Enter the starting port number (lowest port to scan)")));
        this.addRenderableWidget(startPortTextField);

        // End Port field
        this.endPortTextField = new EditBox(
                this.font,
                this.width / 2 + 10,
                50,
                90,
                20,
                Component.literal("End Port")
        );
        this.endPortTextField.setMaxLength(5);
        this.endPortTextField.setValue("65535");
        // Added tooltip for clarity
        this.endPortTextField.setTooltip(Tooltip.create(Component.literal("Enter the ending port number (highest port to scan)")));
        this.addRenderableWidget(endPortTextField);
    }

    private void initializeButtons() {
        this.scanButton = Button.builder(Component.literal("Scan Ports"), this::handleScanButton)
                .width(200)
                .pos(this.width / 2 - 100, 80)
                .build();
        this.addRenderableWidget(scanButton);
    }

    private void handleScanButton(Button button) {
        if (isScanning) {
            stopScanning();
        } else {
            startScanning();
        }
    }

    private void startScanning() {
        if (!validateInput()) {
            return;
        }

        String ip = ipTextField.getValue();
        int startPort = Integer.parseInt(startPortTextField.getValue());
        int endPort = Integer.parseInt(endPortTextField.getValue());

        isScanning = true;
        scanButton.setMessage(Component.literal("Stop Scanning"));
        openPorts.clear();
        foundServers.clear();

        // Clear out old server buttons
        updateServerButtons();

        executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        scanPorts(ip, startPort, endPort);
    }

    private void stopScanning() {
        if (executorService != null) {
            executorService.shutdownNow();
            executorService = null;
        }
        isScanning = false;
        scanButton.setMessage(Component.literal("Scan Ports"));
        statusText = Component.literal("Scanning stopped");
    }

    private boolean validateInput() {
        try {
            String ip = ipTextField.getValue();
            int startPort = Integer.parseInt(startPortTextField.getValue());
            int endPort = Integer.parseInt(endPortTextField.getValue());

            if (!IP_PATTERN.matcher(ip).matches()) {
                statusText = Component.literal("§cInvalid IP address format");
                return false;
            }
            if (startPort < 1 || startPort > 65535 || endPort < 1 || endPort > 65535) {
                statusText = Component.literal("§cPorts must be between 1 and 65535");
                return false;
            }
            if (startPort > endPort) {
                statusText = Component.literal("§cStart port must be ≤ end port");
                return false;
            }
            return true;
        } catch (NumberFormatException e) {
            statusText = Component.literal("§cInvalid port numbers");
            return false;
        }
    }

    private void scanPorts(String ip, int startPort, int endPort) {
        ConcurrentLinkedQueue<Integer> portQueue = new ConcurrentLinkedQueue<>();
        AtomicInteger processedPorts = new AtomicInteger(0);
        int totalPorts = endPort - startPort + 1;

        for (int port = startPort; port <= endPort; port++) {
            final int currentPort = port;
            executorService.submit(() -> {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(ip, currentPort), TIMEOUT_MS);
                    portQueue.add(currentPort);
                    LOGGER.info("Found open port {} on {}", currentPort, ip);
                } catch (IOException ignored) {
                    // Port is closed or unreachable
                } finally {
                    int processed = processedPorts.incrementAndGet();
                    updateProgress(processed, totalPorts);
                }
            });
        }

        new Thread(() -> {
            while (!Thread.currentThread().isInterrupted() && processedPorts.get() < totalPorts) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            Minecraft.getInstance().execute(() -> {
                openPorts.addAll(portQueue);
                for (Integer p : openPorts) {
                    foundServers.add(
                            new ServerData(ip + ":" + p, ip + ":" + p, ServerData.Type.LAN)
                    );
                }
                updateServerButtons();
                isScanning = false;
                scanButton.setMessage(Component.literal("Scan Ports"));
                statusText = Component.literal("§aScanning completed! Found " + openPorts.size() + " open ports");
            });
        }).start();
    }

    private void updateProgress(int processed, int total) {
        float progress = (float) processed / total * 100;
        Minecraft.getInstance().execute(() -> {
            String statusString = String.format("Scanning: %.1f%% (%d/%d)", progress, processed, total);
            statusText = Component.literal(statusString);
        });
    }

    /**
     * Rebuilds the server buttons based on the open ports found.
     * This method applies the updated dynamic button width (reduced by 50%).
     */
    private void updateServerButtons() {
        // Remove old server buttons
        this.children().removeIf(child ->
                child instanceof Button
                        && ((Button) child).getMessage().getString().contains(":")
        );

        // Nothing to do if no servers
        if (foundServers.isEmpty()) return;

        // Recalculate metrics (e.g., buttonsPerRow, dynamicButtonWidth) in case the screen size changed
        calculateLayoutMetrics();

        // Determine how many rows we'll need
        int totalServers = foundServers.size();
        int totalRows = (int) Math.ceil(totalServers / (double) buttonsPerRow);

        // Calculate the total width for the block of buttons, to center them horizontally
        int totalBlockWidth = buttonsPerRow * dynamicButtonWidth
                + (buttonsPerRow - 1) * BUTTON_SPACING;

        // Horizontal offset to center them in the screen
        int offsetX = (this.width - totalBlockWidth) / 2;

        for (int index = 0; index < foundServers.size(); index++) {
            ServerData server = foundServers.get(index);
            int row = index / buttonsPerRow;
            int col = index % buttonsPerRow;

            // X-position is offset by offsetX so everything is centered
            int buttonX = offsetX + col * (dynamicButtonWidth + BUTTON_SPACING);
            // Y-position remains the same as before, starting from RESULTS_START_Y for row 0
            int buttonY = RESULTS_START_Y + row * (BUTTON_HEIGHT + BUTTON_SPACING);

            Button button = Button.builder(Component.literal(server.name), b -> addServerToList(server))
                    .width(dynamicButtonWidth)
                    .pos(buttonX, buttonY)
                    .build();

            this.addRenderableWidget(button);
        }
    }

    private void addServerToList(ServerData server) {
        Minecraft client = Minecraft.getInstance();
        JoinMultiplayerScreen multiplayerScreen = new JoinMultiplayerScreen(this);
        multiplayerScreen.init(this.width, this.height);

        ServerList serverList = new ServerList(client);
        serverList.load();

        MultiplayerScreenInvoker invoker = (MultiplayerScreenInvoker) multiplayerScreen;
        invoker.setServerList(serverList);
        invoker.setSelectedEntry(server);
        invoker.invokeAddEntry(true);

        client.setScreenAndShow(this);
        foundServers.remove(server);
        updateServerButtons();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {

        super.extractRenderState(context, mouseX, mouseY, delta);

        // Draw title
        context.text(
                this.font,
                this.title,
                this.width / 2 - this.font.width(this.title) / 2,
                5,
                0xFFFFFFFF
        );

        // Draw status text
        if (statusText != null) {
            context.text(
                    this.font,
                    statusText,
                    this.width / 2 - this.font.width(statusText) / 2,
                    110,
                    0xFFFFFFFF
            );
        }
    }

    @Override
    public void removed() {
        if (executorService != null) {
            executorService.shutdownNow();
            executorService = null;
        }
        super.removed();
    }

    @Override
    public void resize(int width, int height) {
        String tempIp = ipTextField != null ? ipTextField.getValue() : "";
        String tempStart = startPortTextField != null ? startPortTextField.getValue() : "";
        String tempEnd = endPortTextField != null ? endPortTextField.getValue() : "";

        super.resize(width, height);

        ipTextField.setValue(tempIp);
        startPortTextField.setValue(tempStart);
        endPortTextField.setValue(tempEnd);

        updateServerButtons();
    }
}
