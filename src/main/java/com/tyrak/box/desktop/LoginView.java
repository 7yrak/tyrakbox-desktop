package com.tyrak.box.desktop;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.util.concurrent.CompletableFuture;

public class LoginView {
    private final AppState state;
    private final ApiClient apiClient = new ApiClient();
    private final VBox root = new VBox(12);
    private final Runnable onAuthenticated;

    public LoginView(AppState state, Runnable onAuthenticated) {
        this.state = state;
        this.onAuthenticated = onAuthenticated;
        build();
    }

    public Parent getRoot() {
        return root;
    }

    private void build() {
        root.getStyleClass().add("login-root");
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(28));

        VBox card = new VBox(14);
        card.getStyleClass().add("glass-card");
        card.setMaxWidth(420);
        card.setPadding(new Insets(24));

        Label title = new Label("Tyrak Box Desktop");
        title.getStyleClass().add("title");

        TextField serverUrl = new TextField(state.getServerUrl());
        serverUrl.setPromptText("Servidor, por ejemplo http://localhost:8084");

        TextField username = new TextField();
        username.setPromptText("Usuario");
        username.setText("sergio");

        PasswordField password = new PasswordField();
        password.setPromptText("Contraseña");
        password.setText("password123");

        Button login = new Button("Ingresar");
        login.getStyleClass().add("primary-button");
        Label status = new Label("Conéctate a tu servidor local o remoto.");

        login.setOnAction(evt -> {
            login.setDisable(true);
            status.setText("Validando sesión...");
            state.setServerUrl(serverUrl.getText().trim());
            CompletableFuture.supplyAsync(() -> {
                try {
                    return apiClient.login(state.getServerUrl(), username.getText().trim(), password.getText());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).whenComplete((result, throwable) -> Platform.runLater(() -> {
                try {
                    if (throwable != null) {
                        Throwable rootCause = throwable.getCause() != null ? throwable.getCause() : throwable;
                        status.setText(rootCause.getMessage());
                        return;
                    }

                    state.setToken(result.token);
                    state.setUsername(result.username);
                    state.setUserId(result.userId);
                    SessionStore.save(state.getServerUrl(), result.token, result.username, result.userId);
                    if (DashboardView.hasSavedSession()) {
                        Alert prompt = new Alert(Alert.AlertType.CONFIRMATION);
                        prompt.setTitle("Sesión guardada");
                        prompt.setHeaderText("Se detectó una carpeta de sincronización guardada");
                        prompt.setContentText("¿Quieres retomarla o empezar una nueva?");
                        ButtonType resume = new ButtonType("Retomar");
                        ButtonType fresh = new ButtonType("Nueva");
                        prompt.getButtonTypes().setAll(resume, fresh, ButtonType.CANCEL);
                        var choice = prompt.showAndWait();
                        if (choice.isPresent() && choice.get() == resume) {
                            String savedFolder = DashboardView.getSavedSyncFolder();
                            if (savedFolder != null) {
                                state.setSyncFolder(savedFolder);
                                state.setResumeToken(savedFolder);
                            }
                        } else if (choice.isPresent() && choice.get() == fresh) {
                            DashboardView.clearSavedSession();
                            state.setSyncFolder(null);
                            state.setResumeToken(null);
                        }
                    }
                    if (onAuthenticated != null) {
                        onAuthenticated.run();
                    }
                } finally {
                    login.setDisable(false);
                }
            }));
        });

        card.getChildren().addAll(title, new Label("Servidor"), serverUrl, new Label("Usuario"), username, new Label("Contraseña"), password, login, status);
        root.getChildren().add(card);
    }
}
