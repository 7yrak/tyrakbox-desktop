package com.tyrak.box.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.concurrent.CompletableFuture;

public class DesktopApp extends Application {
    private final ApiClient apiClient = new ApiClient();
    private AppState state;
    private Scene scene;
    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        this.state = new AppState();
        LoginView loginView = new LoginView(state, this::showDashboard);
        scene = new Scene(loginView.getRoot(), 1100, 760);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        stage.setTitle("Tyrak Box Desktop");
        stage.setScene(scene);
        stage.show();
        restoreSavedSession();
    }

    public static void main(String[] args) {
        launch(args);
    }

    private void restoreSavedSession() {
        SessionStore.load().ifPresent(session -> {
            state.setServerUrl(session.serverUrl());
            state.setToken(session.token());
            state.setUsername(session.username());
            state.setUserId(session.userId());
            CompletableFuture.supplyAsync(() -> {
                try {
                    return apiClient.getCurrentUser(state.getServerUrl(), state.getToken());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).whenComplete((user, throwable) -> Platform.runLater(() -> {
                if (throwable != null) {
                    SessionStore.clear();
                    state.setToken(null);
                    state.setUsername(null);
                    state.setUserId(null);
                    return;
                }

                if (user != null) {
                    state.setUsername(user.path("username").asText(state.getUsername()));
                    state.setUserId(user.path("userId").asText(state.getUserId()));
                    showDashboard();
                }
            }));
        });
    }

    private void showDashboard() {
        DashboardView dashboardView = new DashboardView(state, this::showLogin);
        scene.setRoot(dashboardView.getRoot());
    }

    private void showLogin() {
        LoginView loginView = new LoginView(state, this::showDashboard);
        scene.setRoot(loginView.getRoot());
        stage.toFront();
    }
}
