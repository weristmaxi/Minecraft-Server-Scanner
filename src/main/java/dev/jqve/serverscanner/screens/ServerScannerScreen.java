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
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * An updated ServerScannerScreen that includes scrolling if the buttons exceed
 * the visible rows (in this case, two). Scrolling is handled via mouse wheel.
 */
public class ServerScannerScreen extends Screen {
    private static final Logger LOGGER = LogManager.getLogger(ServerScannerScreen.class);

    // Configuration constants
    private static final int TIMEOUT_MS = 200;
    private static final int THREAD_POOL_SIZE = 50;
    private static final int DEFAULT_MINECRAFT_PORT = 25565;
    private static final int SCAN_RANGE_START = 1;
    private static final int SCAN_RANGE_END = 254;

    // UI constants
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_SPACING = 4;
    private static final int RESULTS_START_Y = 120;
    private static final int TEXT_FIELD_WIDTH = 200;
    private static final Pattern IP_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
    );

    private final Screen parent;
    private final Set<ServerData> foundServers = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Queue<Runnable> uiUpdateQueue = new ConcurrentLinkedQueue<>();
    private final List<Button> serverButtons = new ArrayList<>();

    private EditBox ipTextField;
    private Button scanButton;
    private Component statusText;
    private ExecutorService executorService;
    private ScheduledExecutorService uiUpdateExecutor;
    private volatile boolean isScanning;
    private String savedIpText = "";

    // Layout-related fields
    private int buttonsPerRow;
    private int dynamicButtonWidth;

    // We'll cap the visible rows at 2 for demonstration, then let the rest scroll.
    private static final int MAX_VISIBLE_ROWS = 2;

    // We'll store the total row count, plus a scroll offset to allow viewing off-screen buttons.
    private int totalRows;
    private int scrollOffset = 0;
    private final int scrollSpeed = 10; // Adjust scroll speed as needed

    public ServerScannerScreen(Screen parent) {
        super(Component.literal("Minecraft Server Scanner"));
        this.parent = parent;
    }

    @Override
    public void onClose() {
        ((MultiplayerScreenInvoker) parent).invokeRefreshServerList();
    }

    @Override
    protected void init() {
        saveCurrentState();
        calculateLayoutMetrics();
        initializeTextFields();
        initializeButtons();
        restoreState();
        startUiUpdateThread();
    }

    /**
     * Dynamically calculates how many buttons fit in one row and the button width,
     * but caps the visible rows to MAX_VISIBLE_ROWS. If more servers occupy more rows,
     * we'll allow scrolling to see them.
     */
    private void calculateLayoutMetrics() {
        int totalWidth = this.width - 40; // 20px padding on each side
        if (totalWidth < 1) totalWidth = 1;
        // Estimate how many buttons can fit using around 180px each + spacing
        int approximateButtonWidth = 180;
        buttonsPerRow = Math.max(1, totalWidth / (approximateButtonWidth + BUTTON_SPACING));

        // Now compute how many total "slots" we have per row for that arrangement
        int spaceForButtons = totalWidth - (BUTTON_SPACING * (buttonsPerRow - 1));
        dynamicButtonWidth = Math.max(50, spaceForButtons / buttonsPerRow);
    }

    private void saveCurrentState() {
        if (this.ipTextField != null) {
            savedIpText = this.ipTextField.getValue();
        }
    }

    private void initializeTextFields() {
        this.ipTextField = new EditBox(
                this.font,
                this.width / 2 - TEXT_FIELD_WIDTH / 2,
                20,
                TEXT_FIELD_WIDTH,
                BUTTON_HEIGHT,
                Component.literal("IP Address")
        );
        this.ipTextField.setMaxLength(15);
        this.ipTextField.setTooltip(Tooltip.create(Component.literal("Enter IP address (e.g., 192.168.1.1)")));
        this.addRenderableWidget(ipTextField);
    }

    private String getNetworkAddress(String ip) {
        try {
            InetAddress addr = InetAddress.getByName(ip);
            NetworkInterface networkInterface = NetworkInterface.getByInetAddress(addr);

            if (networkInterface != null) {
                for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                    if (interfaceAddress.getAddress() instanceof Inet4Address) {
                        int prefix = interfaceAddress.getNetworkPrefixLength();
                        byte[] bytes = addr.getAddress();
                        int mask = 0xffffffff << (32 - prefix);

                        int network = ((bytes[0] & 0xff) << 24) |
                                ((bytes[1] & 0xff) << 16) |
                                ((bytes[2] & 0xff) << 8) |
                                (bytes[3] & 0xff);
                        network &= mask;
                        return String.format("%d.%d.%d.",
                                (network >>> 24) & 0xff,
                                (network >>> 16) & 0xff,
                                (network >>> 8) & 0xff);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error determining network address", e);
        }
        // Fallback: remove last octet
        return ip.substring(0, ip.lastIndexOf('.') + 1);
    }

    private void clearServerButtons() {
        for (Button button : serverButtons) {
            this.removeWidget(button);
        }
        serverButtons.clear();
    }

    private void initializeButtons() {
        this.scanButton = Button.builder(Component.literal("Scan Network"), this::handleScanButton)
                .width(200)
                .pos(this.width / 2 - 100, 50)
                .build();

        Button backButton = Button.builder(Component.literal("Back"), button ->
                        onClose())
                .width(50)
                .pos(5, 5)
                .build();

        this.addRenderableWidget(scanButton);
        this.addRenderableWidget(backButton);
    }

    private void restoreState() {
        statusText = Component.literal("");
        this.ipTextField.setValue(savedIpText.isEmpty() ? "192.168.1.1" : savedIpText);
    }

    private void startUiUpdateThread() {
        if (uiUpdateExecutor != null && !uiUpdateExecutor.isShutdown()) return;
        uiUpdateExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "UI-Update-Thread");
            thread.setDaemon(true);
            return thread;
        });

        uiUpdateExecutor.scheduleAtFixedRate(() -> {
            while (!uiUpdateQueue.isEmpty()) {
                Runnable update = uiUpdateQueue.poll();
                if (update != null) {
                    Minecraft.getInstance().execute(update);
                }
            }
        }, 0, 50, TimeUnit.MILLISECONDS);
    }

    private void handleScanButton(Button button) {
        if (isScanning) {
            stopScanning();
        } else {
            startScanning();
        }
    }

    private void startScanning() {
        String ip = ipTextField.getValue().trim();
        if (!validateInput(ip)) {
            return;
        }

        String networkIp = getNetworkAddress(ip);
        isScanning = true;
        scrollOffset = 0;
        foundServers.clear();
        clearServerButtons();

        queueUiUpdate(() -> {
            scanButton.setMessage(Component.literal("Stop Scanning"));
            updateServerList();
        });

        executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE, r -> {
            Thread thread = new Thread(r, "Server-Scanner-Thread");
            thread.setDaemon(true);
            return thread;
        });

        scanNetwork(networkIp);
    }

    private void stopScanning() {
        if (executorService != null) {
            executorService.shutdownNow();
            executorService = null;
        }
        isScanning = false;

        queueUiUpdate(() -> {
            scanButton.setMessage(Component.literal("Scan Network"));
            statusText = Component.literal("§cScanning stopped");
        });
    }

    private boolean validateInput(String ip) {
        if (!IP_PATTERN.matcher(ip).matches()) {
            setStatusText("§cInvalid IP address format");
            return false;
        }
        return true;
    }

    private void scanNetwork(String baseIp) {
        AtomicInteger processedIps = new AtomicInteger(0);
        int totalIps = SCAN_RANGE_END - SCAN_RANGE_START + 1;

        CompletableFuture<Void> scanTask = CompletableFuture.supplyAsync(() -> {
            List<CompletableFuture<Void>> probes = new ArrayList<>();
            for (int i = SCAN_RANGE_START; i <= SCAN_RANGE_END && isScanning; i++) {
                final String ip = baseIp + i;
                final int currentNumber = i;

                probes.add(CompletableFuture.runAsync(() -> {
                    try {
                        if (isPortOpen(ip)) {
                            ServerData server = new ServerData(
                                    "Server #" + currentNumber,
                                    ip + ":" + DEFAULT_MINECRAFT_PORT,
                                    ServerData.Type.LAN
                            );
                            foundServers.add(server);
                            LOGGER.info("Found server at {}", ip);
                            queueUiUpdate(this::updateServerList);
                        }
                    } finally {
                        int processed = processedIps.incrementAndGet();
                        updateProgress(processed, totalIps);
                    }
                }, executorService));
            }
            return CompletableFuture.allOf(probes.toArray(CompletableFuture[]::new));
        }, executorService).thenCompose(probes -> probes);

        scanTask.whenComplete((result, exception) -> {
            if (exception != null) {
                LOGGER.error("Error during scanning", exception);
                setStatusText("§cError during scanning");
            }
            completeScan();
        });
    }

    private boolean isPortOpen(String ip) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, DEFAULT_MINECRAFT_PORT), TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void updateProgress(int processed, int total) {
        float progress = (float) processed / total * 100;
        setStatusText(String.format("§eScanning: %.1f%% (%d/%d)", progress, processed, total));
    }

    private void completeScan() {
        if (!isScanning) return;

        queueUiUpdate(() -> {
            isScanning = false;
            scanButton.setMessage(Component.literal("Scan Network"));
            statusText = Component.literal("§aScanning completed! Found " + foundServers.size() + " servers");
            updateServerList();
        });
    }

    /**
     * Updates the server buttons. We calculate the totalRows, then place the buttons.
     * If the totalRows exceeds MAX_VISIBLE_ROWS, we let the user scroll using the mouse wheel.
     */
    private void updateServerList() {
        clearServerButtons();
        List<ServerData> servers;
        synchronized (foundServers) {
            servers = new ArrayList<>(foundServers);
        }

        // Calculate total rows needed for all servers
        int serverCount = servers.size();
        totalRows = (int) Math.ceil((double) serverCount / buttonsPerRow);
        int maxScroll = Math.max(0, (totalRows - MAX_VISIBLE_ROWS) * (BUTTON_HEIGHT + BUTTON_SPACING));
        scrollOffset = Math.min(scrollOffset, maxScroll);

        // The actual loop to create buttons
        int idx = 0;
        int buttonYStart = RESULTS_START_Y - scrollOffset; // offset by scroll
        for (ServerData server : servers) {
            // compute row, col
            int row = idx / buttonsPerRow;
            int col = idx % buttonsPerRow;
            // the Y position is offset by the scrollOffset
            int buttonY = buttonYStart + row * (BUTTON_HEIGHT + BUTTON_SPACING);
            int buttonX = 20 + col * (dynamicButtonWidth + BUTTON_SPACING);

            // Only create fully visible buttons, so off-screen results cannot
            // cover or intercept input intended for the status and scan controls.
            int resultsBottom = RESULTS_START_Y + MAX_VISIBLE_ROWS * (BUTTON_HEIGHT + BUTTON_SPACING);
            if (buttonY < RESULTS_START_Y || buttonY + BUTTON_HEIGHT > resultsBottom) {
                idx++;
                continue;
            }

            Button button = Button.builder(Component.literal(server.name), (btn) -> addServerToList(server))
                    .width(dynamicButtonWidth)
                    .pos(buttonX, buttonY)
                    .build();

            this.serverButtons.add(button);
            this.addRenderableWidget(button);

            idx++;
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
        updateServerList();
    }

    private void queueUiUpdate(Runnable update) {
        uiUpdateQueue.offer(update);
    }

    private void setStatusText(String message) {
        queueUiUpdate(() -> statusText = Component.literal(message));
    }

    /**
     * We override mouseScrolled to allow vertical scrolling if total rows exceed MAX_VISIBLE_ROWS.
     */

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        // If totalRows > MAX_VISIBLE_ROWS, enable scrolling
        int maxRowsVisible = MAX_VISIBLE_ROWS;
        if (totalRows > maxRowsVisible) {
            // total height needed for all rows
            int totalHeight = totalRows * (BUTTON_HEIGHT + BUTTON_SPACING);
            int maxVisibleHeight = maxRowsVisible * (BUTTON_HEIGHT + BUTTON_SPACING);
            int maxScroll = Math.max(0, totalHeight - maxVisibleHeight);

            // Adjust scroll based on mouse wheel
            scrollOffset -= (int) (verticalAmount * scrollSpeed);
            if (scrollOffset < 0) scrollOffset = 0;
            if (scrollOffset > maxScroll) scrollOffset = maxScroll;

            // Re-draw the server buttons with new offset
            updateServerList();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
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
                    80,
                    0xFFFFFFFF
            );
        }
    }

    @Override
    public void removed() {
        stopScanning();
        if (uiUpdateExecutor != null) {
            uiUpdateExecutor.shutdownNow();
            uiUpdateExecutor = null;
        }
        super.removed();
    }

    @Override
    public void resize(int width, int height) {
        String text = this.ipTextField != null ? this.ipTextField.getValue() : "";
        super.resize(width, height);
        this.ipTextField.setValue(text);
        updateServerList(); // Recalculate button layout after resize
    }
}
