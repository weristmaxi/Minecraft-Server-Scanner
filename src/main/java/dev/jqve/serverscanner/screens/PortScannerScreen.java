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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public class PortScannerScreen extends Screen {
    private static final Logger LOGGER = LogManager.getLogger(PortScannerScreen.class);

    // Port scanning settings
    private static final int TIMEOUT_MS = 150;
    private static final int THREAD_POOL_SIZE = 25;
    private static final Pattern IP_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
    );

    // Text fields and button references
    private TextFieldWidget ipTextField;
    private TextFieldWidget startPortTextField;
    private TextFieldWidget endPortTextField;
    private ButtonWidget scanButton;

    // Status text to display scanning progress or errors
    private Text statusText;

    // Collected ports and related server info
    private final List<Integer> openPorts = new ArrayList<>();
    private final List<ServerInfo> foundServers = new ArrayList<>();
    private ExecutorService executorService;
    private boolean isScanning = false;

    // UI layout constants
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_SPACING = 4;
    private static final int RESULTS_START_Y = 120;

    // Dynamic layout variables
    private int buttonsPerRow;
    private int dynamicButtonWidth;

    public PortScannerScreen() {
        super(Text.literal("Minecraft Server Scanner"));
    }

    @Override
    protected void init() {
        // Calculate layout metrics before placing fields and buttons
        calculateLayoutMetrics();
        initializeTextFields();
        initializeButtons();
        statusText = Text.literal("");
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
        this.ipTextField = new TextFieldWidget(
                this.textRenderer,
                this.width / 2 - 100,
                20,
                200,
                20,
                Text.literal("IP Address")
        );
        this.ipTextField.setMaxLength(15);
        this.ipTextField.setText("127.0.0.1");
        // Added basic tooltip
        this.ipTextField.setTooltip(Tooltip.of(Text.literal("Enter the IP address to scan (e.g., 127.0.0.1)")));
        this.addDrawableChild(ipTextField);

        // Start Port field
        this.startPortTextField = new TextFieldWidget(
                this.textRenderer,
                this.width / 2 - 100,
                50,
                90,
                20,
                Text.literal("Start Port")
        );
        this.startPortTextField.setMaxLength(5);
        this.startPortTextField.setText("1");
        // Added tooltip for clarity
        this.startPortTextField.setTooltip(Tooltip.of(Text.literal("Enter the starting port number (lowest port to scan)")));
        this.addDrawableChild(startPortTextField);

        // End Port field
        this.endPortTextField = new TextFieldWidget(
                this.textRenderer,
                this.width / 2 + 10,
                50,
                90,
                20,
                Text.literal("End Port")
        );
        this.endPortTextField.setMaxLength(5);
        this.endPortTextField.setText("65535");
        // Added tooltip for clarity
        this.endPortTextField.setTooltip(Tooltip.of(Text.literal("Enter the ending port number (highest port to scan)")));
        this.addDrawableChild(endPortTextField);
    }

    private void initializeButtons() {
        this.scanButton = ButtonWidget.builder(Text.literal("Scan Ports"), this::handleScanButton)
                .width(200)
                .position(this.width / 2 - 100, 80)
                .build();
        this.addDrawableChild(scanButton);
    }

    private void handleScanButton(ButtonWidget button) {
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

        String ip = ipTextField.getText();
        int startPort = Integer.parseInt(startPortTextField.getText());
        int endPort = Integer.parseInt(endPortTextField.getText());

        isScanning = true;
        scanButton.setMessage(Text.literal("Stop Scanning"));
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
        scanButton.setMessage(Text.literal("Scan Ports"));
        statusText = Text.literal("Scanning stopped");
    }

    private boolean validateInput() {
        try {
            String ip = ipTextField.getText();
            int startPort = Integer.parseInt(startPortTextField.getText());
            int endPort = Integer.parseInt(endPortTextField.getText());

            if (!IP_PATTERN.matcher(ip).matches()) {
                statusText = Text.literal("§cInvalid IP address format");
                return false;
            }
            if (startPort < 1 || startPort > 65535 || endPort < 1 || endPort > 65535) {
                statusText = Text.literal("§cPorts must be between 1 and 65535");
                return false;
            }
            if (startPort > endPort) {
                statusText = Text.literal("§cStart port must be ≤ end port");
                return false;
            }
            return true;
        } catch (NumberFormatException e) {
            statusText = Text.literal("§cInvalid port numbers");
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

            MinecraftClient.getInstance().execute(() -> {
                openPorts.addAll(portQueue);
                for (Integer p : openPorts) {
                    foundServers.add(
                            new ServerInfo(ip + ":" + p, ip + ":" + p, ServerInfo.ServerType.LAN)
                    );
                }
                updateServerButtons();
                isScanning = false;
                scanButton.setMessage(Text.literal("Scan Ports"));
                statusText = Text.literal("§aScanning completed! Found " + openPorts.size() + " open ports");
            });
        }).start();
    }

    private void updateProgress(int processed, int total) {
        float progress = (float) processed / total * 100;
        MinecraftClient.getInstance().execute(() -> {
            String statusString = String.format("Scanning: %.1f%% (%d/%d)", progress, processed, total);
            statusText = Text.literal(statusString);
        });
    }

    /**
     * Rebuilds the server buttons based on the open ports found.
     * This method applies the updated dynamic button width (reduced by 50%).
     */
    private void updateServerButtons() {
        // Remove old server buttons
        this.children().removeIf(child ->
                child instanceof ButtonWidget
                        && ((ButtonWidget) child).getMessage().getString().contains(":")
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
            ServerInfo server = foundServers.get(index);
            int row = index / buttonsPerRow;
            int col = index % buttonsPerRow;

            // X-position is offset by offsetX so everything is centered
            int buttonX = offsetX + col * (dynamicButtonWidth + BUTTON_SPACING);
            // Y-position remains the same as before, starting from RESULTS_START_Y for row 0
            int buttonY = RESULTS_START_Y + row * (BUTTON_HEIGHT + BUTTON_SPACING);

            ButtonWidget button = ButtonWidget.builder(Text.literal(server.name), b -> addServerToList(server))
                    .width(dynamicButtonWidth)
                    .position(buttonX, buttonY)
                    .build();

            this.addDrawableChild(button);
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
        updateServerButtons();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Use a manual gradient background to bypass repeated blur calls on newer Minecraft versions.
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
                    110,
                    0xFFFFFF
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
    public void resize(MinecraftClient client, int width, int height) {
        String tempIp = ipTextField != null ? ipTextField.getText() : "";
        String tempStart = startPortTextField != null ? startPortTextField.getText() : "";
        String tempEnd = endPortTextField != null ? endPortTextField.getText() : "";

        this.init(client, width, height);

        ipTextField.setText(tempIp);
        startPortTextField.setText(tempStart);
        endPortTextField.setText(tempEnd);

        updateServerButtons();
    }
}
