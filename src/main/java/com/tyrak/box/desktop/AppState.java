package com.tyrak.box.desktop;

public class AppState {
    private String serverUrl = "http://10.51.9.17:8083";
    private String token;
    private String username;
    private String userId;
    private String syncFolder;
    private String resumeToken;

    public String getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getSyncFolder() {
        return syncFolder;
    }

    public void setSyncFolder(String syncFolder) {
        this.syncFolder = syncFolder;
    }

    public String getResumeToken() {
        return resumeToken;
    }

    public void setResumeToken(String resumeToken) {
        this.resumeToken = resumeToken;
    }
}
