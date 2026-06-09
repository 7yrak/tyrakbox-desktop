package com.tyrak.box.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.stage.DirectoryChooser;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.*;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.concurrent.*;
import java.util.concurrent.CompletionStage;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class DashboardView {
    private static final Path QUEUE_STATE_PATH = Path.of(System.getProperty("user.home"), ".tyrakbox-desktop-sync.json");
    private final AppState state;
    private final ApiClient apiClient = new ApiClient();
    private final VBox root = new VBox(12);
    private final Label syncStatus = new Label("Conectando...");
    private final Label syncMessage = new Label("Sin actividad todavía.");
    private final Label syncTask = new Label("");
    private final ProgressBar progressBar = new ProgressBar(0);
    private final ProgressBar uploadProgressBar = new ProgressBar(0);
    private final Label uploadStats = new Label("Archivos: 0/0");
    private final Label currentUploadLabel = new Label("Archivo actual: -");
    private final Label completionLabel = new Label("Estado: Listo para iniciar");
    private final ListView<String> activityList = new ListView<>();
    private final ListView<String> filesList = new ListView<>();
    private final Label folderLabel = new Label("Sin carpeta seleccionada");
    private final Button syncButton = new Button("Elegir carpeta para sincronizar");
    private final Button changeFolderButton = new Button("Cambiar carpeta");
    private final Button closeFolderButton = new Button("Cerrar sesión de carpeta");
    private final Button pauseResumeButton = new Button("Pausar cola");
    private final Button retryFailedButton = new Button("Reintentar fallidos");
    private WebSocket webSocket;
    private WatchService watchService;
    // El escaneo inicial y el watcher deben poder correr en paralelo.
    // Con un solo hilo, el watchLoop quedaba bloqueado hasta que terminaba el árbol completo.
    private final ExecutorService watcherExecutor = Executors.newFixedThreadPool(2);
    private final ScheduledExecutorService uploadExecutor = Executors.newSingleThreadScheduledExecutor();
    private final Semaphore uploadSlots = new Semaphore(3);
    private final AtomicInteger activeUploads = new AtomicInteger(0);
    private final Map<String, ScheduledFuture<?>> pendingUploads = new ConcurrentHashMap<>();
    private final Map<String, Integer> uploadAttempts = new ConcurrentHashMap<>();
    private final Map<String, Boolean> uploadResults = new ConcurrentHashMap<>();
    private final Map<String, String> uploadedSignatures = new ConcurrentHashMap<>();
    private final Set<String> failedUploads = ConcurrentHashMap.newKeySet();
    private final Map<String, String> remoteFolderIds = new ConcurrentHashMap<>();
    private volatile String remoteRootFolderId;
    private volatile int totalFilesToUpload = 0;
    private volatile int completedFiles = 0;
    private volatile int failedFiles = 0;
    private volatile boolean scanning = false;
    private volatile boolean paused = false;
    private static final int MAX_UPLOAD_ATTEMPTS = 3;

    public DashboardView(AppState state) {
        this.state = state;
        build();
        refreshAll();
        connectSocket();
        if (state.getSyncFolder() != null && !state.getSyncFolder().isBlank()) {
            Platform.runLater(() -> startFolderSync(Path.of(state.getSyncFolder())));
        }
    }

    public Parent getRoot() {
        return root;
    }

    public static boolean hasSavedSession() {
        return Files.exists(QUEUE_STATE_PATH);
    }

    public static String getSavedSyncFolder() {
        if (!Files.exists(QUEUE_STATE_PATH)) {
            return null;
        }
        try {
            JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(QUEUE_STATE_PATH, StandardCharsets.UTF_8));
            String value = node.path("syncFolder").asText("");
            return value.isBlank() ? null : value;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static void clearSavedSession() {
        try {
            Files.deleteIfExists(QUEUE_STATE_PATH);
        } catch (IOException ignored) {
        }
    }

    private void build() {
        root.getStyleClass().add("dashboard-root");
        root.setPadding(new Insets(18));

        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label("Tyrak Box Desktop");
        title.getStyleClass().add("title");
        Label user = new Label("Sesión: " + state.getUsername());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button refresh = new Button("Actualizar");
        refresh.getStyleClass().add("secondary-button");
        refresh.setOnAction(e -> refreshAll());
        header.getChildren().addAll(title, spacer, user, refresh);

        VBox syncCard = new VBox(8);
        syncCard.getStyleClass().add("glass-card");
        syncCard.setPadding(new Insets(18));
        syncStatus.getStyleClass().add("sync-status");
        syncMessage.getStyleClass().add("sync-message");
        syncTask.getStyleClass().add("sync-task");
        progressBar.setPrefWidth(520);
        syncButton.getStyleClass().add("primary-button");
        syncButton.setOnAction(e -> chooseSyncFolder());
        changeFolderButton.getStyleClass().add("secondary-button");
        changeFolderButton.setOnAction(e -> chooseSyncFolder());
        closeFolderButton.getStyleClass().add("secondary-button");
        closeFolderButton.setOnAction(e -> closeFolderSession());
        pauseResumeButton.getStyleClass().add("secondary-button");
        pauseResumeButton.setOnAction(e -> togglePause());
        retryFailedButton.getStyleClass().add("secondary-button");
        retryFailedButton.setOnAction(e -> retryFailedUploads());
        folderLabel.setWrapText(true);
        completionLabel.getStyleClass().add("sync-message");
        uploadProgressBar.setPrefWidth(520);
        uploadStats.getStyleClass().add("sync-message");
        currentUploadLabel.getStyleClass().add("sync-message");
        syncCard.getChildren().addAll(
                new Label("Estado de sync"),
                syncStatus,
                syncTask,
                progressBar,
                syncMessage,
                new Separator(),
                new Label("Progreso de subida"),
                uploadStats,
                uploadProgressBar,
                currentUploadLabel,
                new Separator(),
                syncButton,
                changeFolderButton,
                closeFolderButton,
                pauseResumeButton,
                retryFailedButton,
                completionLabel,
                folderLabel
        );

        HBox body = new HBox(14);
        body.setAlignment(Pos.TOP_CENTER);

        VBox left = new VBox(10);
        left.setPrefWidth(520);
        left.getChildren().addAll(makePanel("Archivos", filesList), makePanel("Actividad reciente", activityList));
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(syncCard, Priority.ALWAYS);

        body.getChildren().addAll(syncCard, left);
        root.getChildren().addAll(header, body);
    }

    private VBox makePanel(String title, ListView<String> listView) {
        VBox panel = new VBox(8);
        panel.getStyleClass().add("glass-card");
        panel.setPadding(new Insets(14));
        Label label = new Label(title);
        label.getStyleClass().add("panel-title");
        listView.setPrefHeight(250);
        panel.getChildren().addAll(label, listView);
        return panel;
    }

    private void refreshAll() {
        try {
            JsonNode status = apiClient.getSyncStatus(state.getServerUrl(), state.getToken());
            syncStatus.setText((status.path("running").asBoolean(false) ? "Activa" : "Detenida") + " | " + status.path("syncUsername").asText(state.getUsername()));
            syncTask.setText(status.path("currentTask").asText(""));
            syncMessage.setText(status.path("lastEvent").asText("Sin actividad"));
            syncMessage.setWrapText(true);
            syncMessage.setText(status.path("lastError").asText("").isBlank() ? status.path("lastEvent").asText("Sin actividad") : status.path("lastError").asText());
            progressBar.setProgress(status.path("currentTaskProgress").asInt(0) / 100.0);

            activityList.setItems(FXCollections.observableArrayList());
            if (status.has("recentEvents") && status.get("recentEvents").isArray()) {
                var items = FXCollections.<String>observableArrayList();
                status.get("recentEvents").forEach(node -> items.add(node.asText()));
                activityList.setItems(items);
            }

            JsonNode content = apiClient.getRootContent(state.getServerUrl(), state.getToken());
            var items = FXCollections.<String>observableArrayList();
            if (content.has("folders")) {
                content.get("folders").forEach(node -> items.add("📁 " + node.path("name").asText("Carpeta")));
            }
            if (content.has("files")) {
                content.get("files").forEach(node -> items.add("📄 " + node.path("name").asText("Archivo")));
            }
            filesList.setItems(items);
        } catch (Exception e) {
            syncMessage.setText("Error actualizando: " + e.getMessage());
        }
    }

    private void chooseSyncFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Selecciona la carpeta a sincronizar");
        if (state.getSyncFolder() != null && !state.getSyncFolder().isBlank()) {
            chooser.setInitialDirectory(Path.of(state.getSyncFolder()).toFile());
        }
        java.io.File selected = chooser.showDialog(root.getScene().getWindow());
        if (selected == null) {
            return;
        }

        if (!isSyncCompleted() && state.getSyncFolder() != null && !state.getSyncFolder().isBlank()) {
            syncMessage.setText("La carpeta actual sigue en proceso. Espera a que termine para cambiarla.");
            return;
        }

        state.setSyncFolder(selected.getAbsolutePath());
        state.setResumeToken(selected.getAbsolutePath());
        folderLabel.setText("Carpeta: " + selected.getAbsolutePath());
        startFolderSync(Path.of(selected.getAbsolutePath()));
    }

    private void closeFolderSession() {
        if (state.getSyncFolder() == null || state.getSyncFolder().isBlank()) {
            syncMessage.setText("No hay carpeta activa para cerrar.");
            return;
        }

        if (!isSyncCompleted()) {
            syncMessage.setText("La carpeta aún no terminó. Espera a que esté completada para cerrarla.");
            return;
        }

        saveQueueState();
        stopFolderSync();
        state.setResumeToken(state.getSyncFolder());
        state.setSyncFolder(null);
        Platform.runLater(() -> {
            folderLabel.setText("Sin carpeta seleccionada");
            completionLabel.setText("Estado: Sesión cerrada");
            syncStatus.setText("Carpeta cerrada");
            syncMessage.setText("Sesión de carpeta cerrada. Ya puedes elegir otra.");
            syncTask.setText("");
            progressBar.setProgress(0);
            uploadProgressBar.setProgress(0);
            currentUploadLabel.setText("Archivo actual: -");
        });
    }

    private void startFolderSync(Path folder) {
        stopFolderSync();
        syncMessage.setText("Preparando sincronización local...");
        try {
            resetUploadProgress();
            watchService = FileSystems.getDefault().newWatchService();
            ensureRemoteFolderTree(folder);
            registerAll(folder);
            scanning = true;
            updateUploadStats();
            restoreQueueStateIfPossible(folder);
            watcherExecutor.submit(() -> scanAndUpload(folder));
            watcherExecutor.submit(() -> watchLoop(folder));
            syncMessage.setText("Sincronizando: " + folder);
            syncStatus.setText("Sync local activa");
        } catch (Exception e) {
            syncMessage.setText("No se pudo iniciar sync local: " + e.getMessage());
        }
    }

    private void stopFolderSync() {
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {
            }
            watchService = null;
        }
        pendingUploads.values().forEach(future -> future.cancel(false));
        pendingUploads.clear();
        uploadAttempts.clear();
        uploadResults.clear();
        uploadedSignatures.clear();
        failedUploads.clear();
        activeUploads.set(0);
        scanning = false;
        paused = false;
        Platform.runLater(() -> pauseResumeButton.setText("Pausar cola"));
        saveQueueState();
        updateFolderControls();
    }

    private void registerAll(Path start) throws IOException {
        Files.walk(start)
                .filter(Files::isDirectory)
                .forEach(dir -> {
                    try {
                        dir.register(watchService, StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_DELETE,
                                StandardWatchEventKinds.ENTRY_MODIFY);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    private void watchLoop(Path rootFolder) {
        while (watchService != null) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException e) {
                return;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    continue;
                }

                Path dir = (Path) key.watchable();
                Path child = dir.resolve((Path) event.context());
                if (Files.isDirectory(child) && kind == StandardWatchEventKinds.ENTRY_CREATE) {
                    try {
                        registerAll(child);
                    } catch (IOException ignored) {
                    }
                }

                if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                    handleDelete(rootFolder, child);
                } else {
                    queueUpload(rootFolder, child);
                }
            }

            if (!key.reset()) {
                break;
            }
        }
    }

    private void scanAndUpload(Path rootFolder) {
        try {
            Files.walkFileTree(rootFolder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    try {
                        ensureRemoteFolder(dir, rootFolder);
                    } catch (Exception e) {
                        Platform.runLater(() -> syncMessage.setText("Error creando carpeta: " + e.getMessage()));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    totalFilesToUpload++;
                    updateUploadStats();
                    queueUpload(rootFolder, file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    totalFilesToUpload++;
                    failedFiles++;
                    updateUploadStats();
                    Platform.runLater(() -> syncMessage.setText("No se pudo leer: " + file.getFileName()));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            Platform.runLater(() -> syncMessage.setText("Error escaneando carpeta: " + e.getMessage()));
        } finally {
            scanning = false;
            updateUploadStats();
        }
    }

    private void ensureRemoteFolderTree(Path rootFolder) throws Exception {
        remoteFolderIds.clear();
        java.io.File fileName = rootFolder.toFile();
        String rootName = fileName.getName();
        JsonNode rootFolderNode = apiClient.createFolder(state.getServerUrl(), state.getToken(), rootName, null);
        remoteRootFolderId = rootFolderNode.path("id").asText();
        remoteFolderIds.put("", remoteRootFolderId);
    }

    private String ensureRemoteFolder(Path dir, Path rootFolder) throws Exception {
        String relative = rootFolder.relativize(dir).toString().replace("\\", "/");
        if (relative.isBlank()) {
            return remoteRootFolderId;
        }

        String parentKey = "";
        StringBuilder currentPath = new StringBuilder();
        String currentParentId = remoteRootFolderId;
        for (String segment : relative.split("/")) {
            if (segment.isBlank()) {
                continue;
            }
            if (currentPath.length() > 0) {
                currentPath.append("/");
            }
            currentPath.append(segment);
            String key = currentPath.toString();
            String cached = remoteFolderIds.get(key);
            if (cached != null) {
                currentParentId = cached;
                parentKey = key;
                continue;
            }

            JsonNode created = apiClient.createFolder(state.getServerUrl(), state.getToken(), segment, currentParentId);
            currentParentId = created.path("id").asText();
            remoteFolderIds.put(key, currentParentId);
            parentKey = key;
        }
        return remoteFolderIds.getOrDefault(parentKey, remoteRootFolderId);
    }

    private void queueUpload(Path rootFolder, Path path) {
        if (!Files.exists(path) || Files.isDirectory(path)) {
            return;
        }

        String relative = rootFolder.relativize(path).toString().replace("\\", "/");
        String signature = fileSignature(path);
        String previousSignature = uploadedSignatures.get(relative);
        if (Boolean.TRUE.equals(uploadResults.get(relative)) && signature.equals(previousSignature)) {
            return;
        }

        uploadResults.put(relative, Boolean.FALSE);
        uploadAttempts.putIfAbsent(relative, 0);
        saveQueueState();
        updateUploadStats();
        ScheduledFuture<?> previous = pendingUploads.get(relative);
        if (previous != null) {
            previous.cancel(false);
        }

        ScheduledFuture<?> scheduled = uploadExecutor.schedule(() -> {
            try {
                uploadFile(rootFolder, path);
            } finally {
                pendingUploads.remove(relative);
            }
        }, 500, TimeUnit.MILLISECONDS);

        pendingUploads.put(relative, scheduled);
    }

    private void uploadFile(Path rootFolder, Path path) {
        if (!Files.exists(path) || Files.isDirectory(path)) {
            return;
        }

        if (paused) {
            scheduleRetry(rootFolder, path, 500);
            return;
        }

        boolean acquired = false;
        try {
            uploadSlots.acquire();
            acquired = true;
            activeUploads.incrementAndGet();
            waitIfPaused();
            ensureRemoteFolder(path.getParent(), rootFolder);
            String relative = rootFolder.relativize(path).toString().replace("\\", "/");
            Platform.runLater(() -> currentUploadLabel.setText("Archivo actual: " + relative));
            syncMessage.setText("Subiendo: " + relative);
            apiClient.uploadFile(state.getServerUrl(), state.getToken(), path, relative, remoteRootFolderId);
            uploadResults.put(rootFolder.relativize(path).toString().replace("\\", "/"), Boolean.TRUE);
            uploadAttempts.remove(rootFolder.relativize(path).toString().replace("\\", "/"));
            uploadedSignatures.put(rootFolder.relativize(path).toString().replace("\\", "/"), fileSignature(path));
            failedUploads.remove(rootFolder.relativize(path).toString().replace("\\", "/"));
            completedFiles = (int) uploadResults.values().stream().filter(Boolean::booleanValue).count();
            failedFiles = (int) uploadResults.values().stream().filter(v -> !v).count();
            updateUploadStats();
            saveQueueState();
            Platform.runLater(() -> {
                activityList.getItems().add(0, "Subido: " + relative);
                syncMessage.setText("Subido: " + relative);
            });
        } catch (Exception e) {
            String relativeKey = rootFolder.relativize(path).toString().replace("\\", "/");
            int attempt = uploadAttempts.merge(relativeKey, 1, Integer::sum);
            if (attempt < MAX_UPLOAD_ATTEMPTS && !paused) {
                scheduleRetry(rootFolder, path, 1000L * attempt);
            } else {
                uploadResults.put(relativeKey, Boolean.FALSE);
                failedUploads.add(relativeKey);
                failedFiles = (int) uploadResults.values().stream().filter(v -> !v).count();
                updateUploadStats();
                saveQueueState();
                Platform.runLater(() -> syncMessage.setText("Error subiendo " + path.getFileName() + ": " + e.getMessage()));
            }
        } finally {
            if (acquired) {
                uploadSlots.release();
            }
            activeUploads.updateAndGet(v -> Math.max(0, v - 1));
            Platform.runLater(() -> currentUploadLabel.setText("Archivo actual: -"));
            updateUploadStats();
        }
    }

    private void scheduleRetry(Path rootFolder, Path path, long delayMs) {
        String relative = rootFolder.relativize(path).toString().replace("\\", "/");
        ScheduledFuture<?> scheduled = uploadExecutor.schedule(() -> uploadFile(rootFolder, path), delayMs, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> previous = pendingUploads.put(relative, scheduled);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    private void waitIfPaused() throws InterruptedException {
        while (paused) {
            Thread.sleep(200);
        }
    }

    private void togglePause() {
        paused = !paused;
        Platform.runLater(() -> pauseResumeButton.setText(paused ? "Reanudar cola" : "Pausar cola"));
        if (!paused) {
            retryFailedUploads();
        }
        saveQueueState();
        updateUploadStats();
    }

    private void retryFailedUploads() {
        String folderValue = state.getSyncFolder();
        if (folderValue == null || folderValue.isBlank()) {
            return;
        }
        Path root = Path.of(folderValue);
        for (String relative : new LinkedHashSet<>(failedUploads)) {
            Path file = root.resolve(relative);
            if (Files.exists(file)) {
                queueUpload(root, file);
            }
        }
        failedUploads.clear();
        saveQueueState();
        updateUploadStats();
    }

    private String fileSignature(Path path) {
        try {
            return Files.size(path) + ":" + Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return "missing";
        }
    }

    private void resetUploadProgress() {
        totalFilesToUpload = 0;
        completedFiles = 0;
        failedFiles = 0;
        uploadResults.clear();
        uploadAttempts.clear();
        failedUploads.clear();
        Platform.runLater(this::updateUploadStats);
    }

    private void updateUploadStats() {
        int completed = completedFiles;
        int total = totalFilesToUpload;
        int failed = failedFiles;
        int inFlight = activeUploads.get();
        int pending = Math.max(total - completed - failed - inFlight, 0);
        double progress = total > 0 ? (double) completed / (double) total : 0.0;
        boolean completedSync = isSyncCompleted();

        Runnable update = () -> {
            String scanState = scanning ? "Escaneando..." : "Listo";
            String pauseState = paused ? "Pausada" : "Activa";
            String syncState = completedSync ? "Completada" : "En proceso";
            uploadStats.setText(scanState + " | Sync: " + syncState + " | Cola: " + pauseState + " | Archivos: " + completed + "/" + total + " | En vuelo: " + inFlight + " | Pendientes: " + pending + " | Fallidos: " + failed);
            uploadProgressBar.setProgress(progress);
            completionLabel.setText("Estado: " + syncState);
            updateFolderControls();
        };

        if (Platform.isFxApplicationThread()) {
            update.run();
        } else {
            Platform.runLater(update);
        }
    }

    private void handleDelete(Path rootFolder, Path path) {
        String relative = rootFolder.relativize(path).toString().replace("\\", "/");
        Platform.runLater(() -> activityList.getItems().add(0, "Borrado local: " + relative));
    }

    private boolean isSyncCompleted() {
        int inFlight = activeUploads.get();
        int pending = Math.max(totalFilesToUpload - completedFiles - failedFiles - inFlight, 0);
        return !scanning && !paused && pending == 0 && inFlight == 0 && failedUploads.isEmpty();
    }

    private void updateFolderControls() {
        boolean canChange = isSyncCompleted();
        Runnable update = () -> {
            changeFolderButton.setDisable(!canChange);
            syncButton.setDisable(!canChange);
            pauseResumeButton.setDisable(!stateHasActiveSync());
            retryFailedButton.setDisable(failedUploads.isEmpty() && !stateHasActiveSync());
        };
        if (Platform.isFxApplicationThread()) {
            update.run();
        } else {
            Platform.runLater(update);
        }
    }

    private boolean stateHasActiveSync() {
        return state.getSyncFolder() != null && !state.getSyncFolder().isBlank();
    }

    private void saveQueueState() {
        try {
            var root = new java.util.LinkedHashMap<String, Object>();
            root.put("syncFolder", state.getSyncFolder());
            root.put("paused", paused);
            root.put("scanning", scanning);
            root.put("failedUploads", new java.util.ArrayList<>(failedUploads));
            root.put("uploadedSignatures", new java.util.LinkedHashMap<>(uploadedSignatures));
            root.put("uploadAttempts", new java.util.LinkedHashMap<>(uploadAttempts));
            Files.writeString(QUEUE_STATE_PATH, new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(root), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception ignored) {
        }
    }

    private void restoreQueueStateIfPossible(Path folder) {
        if (!Files.exists(QUEUE_STATE_PATH)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(QUEUE_STATE_PATH, StandardCharsets.UTF_8)) {
            JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(reader);
            if (!folder.toAbsolutePath().toString().equals(node.path("syncFolder").asText(""))) {
                return;
            }
            paused = node.path("paused").asBoolean(false);
            Platform.runLater(() -> pauseResumeButton.setText(paused ? "Reanudar cola" : "Pausar cola"));
            node.path("uploadedSignatures").fields().forEachRemaining(entry -> uploadedSignatures.put(entry.getKey(), entry.getValue().asText("")));
            node.path("uploadAttempts").fields().forEachRemaining(entry -> uploadAttempts.put(entry.getKey(), entry.getValue().asInt(0)));
            node.path("failedUploads").forEach(item -> failedUploads.add(item.asText()));
            updateUploadStats();
        } catch (Exception ignored) {
        }
    }

    private void connectSocket() {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        String wsUrl = state.getServerUrl().replaceFirst("^http", "ws").replaceAll("/$", "") + "/ws/sync";
        webSocket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        WebSocket.Listener.super.onOpen(webSocket);
                        Platform.runLater(() -> syncStatus.setText("Conectado"));
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        Platform.runLater(() -> handleSocketMessage(data.toString()));
                        webSocket.request(1);
                        return WebSocket.Listener.super.onText(webSocket, data, last);
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        Platform.runLater(() -> syncStatus.setText("Socket con error: " + error.getMessage()));
                    }
                }).join();
        webSocket.request(1);
    }

    private void handleSocketMessage(String raw) {
        try {
            JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
            if (node.has("currentTask")) {
                syncTask.setText(node.path("currentTask").asText(""));
                progressBar.setProgress(node.path("currentTaskProgress").asInt(0) / 100.0);
                syncStatus.setText(node.path("running").asBoolean(false) ? "Activa" : "Detenida");
                syncMessage.setText(node.path("lastEvent").asText("Sin actividad"));
                activityList.setItems(FXCollections.observableArrayList());
                var items = FXCollections.<String>observableArrayList();
                if (node.has("recentEvents")) {
                    node.get("recentEvents").forEach(item -> items.add(item.asText()));
                }
                activityList.setItems(items);
            }
            if ("refresh".equals(node.path("type").asText())) {
                refreshAll();
            }
        } catch (Exception e) {
            syncMessage.setText("Mensaje de sync inválido: " + e.getMessage());
        }
    }
}
