package dev.jqve.serverscanner.screens;

import dev.jqve.serverscanner.mixin.MultiplayerScreenInvoker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.option.ServerList;
import net.minecraft.text.Text;
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
    private final Set<ServerInfo> foundServers = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Queue<Runnable> uiUpdateQueue = new ConcurrentLinkedQueue<>();
    private final List<ButtonWidget> serverButtons = new ArrayList<>();

    private TextFieldWidget ipTextField;
    private ButtonWidget scanButton;
    private Text statusText;
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
        super(Text.literal("Minecraft Server Scanner"));
        this.parent = parent;
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
            savedIpText = this.ipTextField.getText();
        }
    }

    private void initializeTextFields() {
        this.ipTextField = new TextFieldWidget(
                this.textRenderer,
                this.width / 2 - TEXT_FIELD_WIDTH / 2,
                20,
                TEXT_FIELD_WIDTH,
                BUTTON_HEIGHT,
                Text.literal("IP Address")
        );
        this.ipTextField.setMaxLength(15);
        this.ipTextField.setTooltip(Tooltip.of(Text.literal("Enter IP address (e.g., 192.168.1.1)")));
        this.addDrawableChild(ipTextField);
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
        for (ButtonWidget button : serverButtons) {
            this.remove(button);
        }
        serverButtons.clear();
    }

    private void initializeButtons() {
        this.scanButton = ButtonWidget.builder(Text.literal("Scan Network"), this::handleScanButton)
                .width(200)
                .position(this.width / 2 - 100, 50)
                .build();

        ButtonWidget backButton = ButtonWidget.builder(Text.literal("Back"), button ->
                        MinecraftClient.getInstance().setScreen(parent))
                .width(50)
                .position(5, 5)
                .build();

        this.addDrawableChild(scanButton);
        this.addDrawableChild(backButton);
    }

    private void restoreState() {
        statusText = Text.literal("");
        this.ipTextField.setText(savedIpText.isEmpty() ? "192.168.1.1" : savedIpText);
    }

    private void startUiUpdateThread() {
        uiUpdateExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "UI-Update-Thread");
            thread.setDaemon(true);
            return thread;
        });

        uiUpdateExecutor.scheduleAtFixedRate(() -> {
            while (!uiUpdateQueue.isEmpty()) {
                Runnable update = uiUpdateQueue.poll();
                if (update != null) {
                    MinecraftClient.getInstance().execute(update);
                }
            }
        }, 0, 50, TimeUnit.MILLISECONDS);
    }

    private void handleScanButton(ButtonWidget button) {
        if (isScanning) {
            stopScanning();
        } else {
            startScanning();
        }
    }

    private void startScanning() {
        String ip = ipTextField.getText().trim();
        if (!validateInput(ip)) {
            return;
        }

        String networkIp = getNetworkAddress(ip);
        isScanning = true;
        foundServers.clear();
        clearServerButtons();

        queueUiUpdate(() -> {
            scanButton.setMessage(Text.literal("Stop Scanning"));
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
            scanButton.setMessage(Text.literal("Scan Network"));
            statusText = Text.literal("§cScanning stopped");
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

        CompletableFuture<Void> scanTask = CompletableFuture.runAsync(() -> {
            for (int i = SCAN_RANGE_START; i <= SCAN_RANGE_END && isScanning; i++) {
                final String ip = baseIp + i;
                final int currentNumber = i;

                CompletableFuture.runAsync(() -> {
                    try {
                        if (isPortOpen(ip)) {
                            ServerInfo server = new ServerInfo(
                                    "Server #" + currentNumber,
                                    ip + ":" + DEFAULT_MINECRAFT_PORT,
                                    ServerInfo.ServerType.LAN
                            );
                            foundServers.add(server);
                            LOGGER.info("Found server at {}", ip);
                            queueUiUpdate(this::updateServerList);
                        }
                    } finally {
                        int processed = processedIps.incrementAndGet();
                        updateProgress(processed, totalIps);
                    }
                }, executorService);
            }
        }, executorService);

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
            scanButton.setMessage(Text.literal("Scan Network"));
            statusText = Text.literal("§aScanning completed! Found " + foundServers.size() + " servers");
            updateServerList();
        });
    }

    /**
     * Updates the server buttons. We calculate the totalRows, then place the buttons.
     * If the totalRows exceeds MAX_VISIBLE_ROWS, we let the user scroll using the mouse wheel.
     */
    private void updateServerList() {
        clearServerButtons();
        if (foundServers.isEmpty()) return;

        // Calculate total rows needed for all servers
        int serverCount = foundServers.size();
        totalRows = (int) Math.ceil((double) serverCount / buttonsPerRow);

        // The actual loop to create buttons
        int idx = 0;
        int buttonYStart = RESULTS_START_Y - scrollOffset; // offset by scroll
        for (ServerInfo server : foundServers) {
            // compute row, col
            int row = idx / buttonsPerRow;
            int col = idx % buttonsPerRow;
            // the Y position is offset by the scrollOffset
            int buttonY = buttonYStart + row * (BUTTON_HEIGHT + BUTTON_SPACING);
            int buttonX = 20 + col * (dynamicButtonWidth + BUTTON_SPACING);

            ButtonWidget button = ButtonWidget.builder(Text.literal(server.name), (btn) -> addServerToList(server))
                    .width(dynamicButtonWidth)
                    .position(buttonX, buttonY)
                    .build();

            this.serverButtons.add(button);
            this.addDrawableChild(button);

            idx++;
        }
    }

    private void addServerToList(ServerInfo server) {
        MinecraftClient client = MinecraftClient.getInstance();
        MultiplayerScreen multiplayerScreen = new MultiplayerScreen(this);
        multiplayerScreen.init(client, this.width, this.height);

        ServerList serverList = new ServerList(client);
        serverList.loadFile();

        MultiplayerScreenInvoker invoker = (MultiplayerScreenInvoker) multiplayerScreen;
        invoker.setServerList(serverList);
        invoker.setSelectedEntry(server);
        invoker.invokeAddEntry(true);

        client.setScreen(this);
        foundServers.remove(server);
        updateServerList();
    }

    private void queueUiUpdate(Runnable update) {
        uiUpdateQueue.offer(update);
    }

    private void setStatusText(String message) {
        queueUiUpdate(() -> statusText = Text.literal(message));
    }

    /**
     * We override mouseScrolled to allow vertical scrolling if total rows exceed MAX_VISIBLE_ROWS.
     */

    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        // If totalRows > MAX_VISIBLE_ROWS, enable scrolling
        int maxRowsVisible = MAX_VISIBLE_ROWS;
        if (totalRows > maxRowsVisible) {
            // total height needed for all rows
            int totalHeight = totalRows * (BUTTON_HEIGHT + BUTTON_SPACING);
            int maxVisibleHeight = maxRowsVisible * (BUTTON_HEIGHT + BUTTON_SPACING);
            int maxScroll = Math.max(0, totalHeight - maxVisibleHeight);

            // Adjust scroll based on mouse wheel
            scrollOffset -= amount * scrollSpeed;
            if (scrollOffset < 0) scrollOffset = 0;
            if (scrollOffset > maxScroll) scrollOffset = maxScroll;

            // Re-draw the server buttons with new offset
            updateServerList();
        }
        return super.mouseScrolled(mouseX, mouseY, amount, amount);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Avoid invoking the vanilla blur twice in one frame on newer clients by using a lightweight gradient background.
        context.fillGradient(0, 0, this.width, this.height, 0xB0000000, 0xB0000000);
        super.render(context, mouseX, mouseY, delta);

        // Draw title
        context.drawTextWithShadow(
                this.textRenderer,
                this.title,
                this.width / 2 - this.textRenderer.getWidth(this.title) / 2,
                5,
                0xFFFFFF
        );

        // Draw status text
        if (statusText != null) {
            context.drawTextWithShadow(
                    this.textRenderer,
                    statusText,
                    this.width / 2 - this.textRenderer.getWidth(statusText) / 2,
                    80,
                    0xFFFFFF
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
    public void resize(MinecraftClient client, int width, int height) {
        String text = this.ipTextField != null ? this.ipTextField.getText() : "";
        this.init(client, width, height);
        this.ipTextField.setText(text);
        updateServerList(); // Recalculate button layout after resize
    }
}
