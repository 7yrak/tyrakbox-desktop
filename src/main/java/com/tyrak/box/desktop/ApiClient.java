package com.tyrak.box.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.SequenceInputStream;
import java.util.Vector;
import java.util.function.LongConsumer;

public class ApiClient {
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public AuthResult login(String serverUrl, String username, String password) throws Exception {
        var payload = mapper.createObjectNode();
        payload.put("username", username);
        payload.put("password", password);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl.replaceAll("/$", "") + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            JsonNode node = safeRead(response.body());
            String message = node != null && node.has("error") ? node.get("error").asText() : "Credenciales incorrectas";
            throw new IllegalStateException(message);
        }

        JsonNode node = mapper.readTree(response.body());
        AuthResult result = new AuthResult();
        result.token = node.path("token").asText();
        result.username = node.path("username").asText(username);
        result.userId = node.path("userId").asText("");
        return result;
    }

    public JsonNode getSyncStatus(String serverUrl, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl.replaceAll("/$", "") + "/api/sync/status"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(response.body());
    }

    public JsonNode getCurrentUser(String serverUrl, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl.replaceAll("/$", "") + "/api/auth/me"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(response.body());
        }
        return mapper.readTree(response.body());
    }

    public JsonNode getRootContent(String serverUrl, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl.replaceAll("/$", "") + "/api/folders/content"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(response.body());
    }

    public JsonNode createFolder(String serverUrl, String token, String name, String parentId) throws Exception {
        var payload = mapper.createObjectNode();
        payload.put("name", name);
        if (parentId != null && !parentId.isBlank()) {
            payload.put("parentId", parentId);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl.replaceAll("/$", "") + "/api/folders"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(response.body());
        }
        return mapper.readTree(response.body());
    }

    public JsonNode uploadFile(String serverUrl, String token, Path filePath, String relativePath, String folderId) throws Exception {
        return uploadFile(serverUrl, token, filePath, relativePath, folderId, null);
    }

    public JsonNode uploadFile(String serverUrl, String token, Path filePath, String relativePath, String folderId, LongConsumer progressListener) throws Exception {
        String boundary = "----TyrakBoundary" + System.currentTimeMillis();
        byte[] fileBytes = Files.readAllBytes(filePath);
        String sanitizedName = relativePath.replace("\\", "/");

        MultipartBody multipartBody = buildMultipartBody(boundary, sanitizedName, fileBytes, folderId, progressListener);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl.replaceAll("/$", "") + "/api/files/upload"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofInputStream(multipartBody::openStream))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(response.body());
        }
        return safeRead(response.body());
    }

    public JsonNode getUploadJob(String serverUrl, String token, String jobId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl.replaceAll("/$", "") + "/api/files/upload-jobs/" + jobId))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(response.body());
        }
        return mapper.readTree(response.body());
    }

    public JsonNode getFolderContent(String serverUrl, String token, String parentId) throws Exception {
        String uri = serverUrl.replaceAll("/$", "") + "/api/folders/content" + (parentId != null && !parentId.isBlank() ? "?parentId=" + parentId : "");
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(response.body());
        }
        return mapper.readTree(response.body());
    }

    public void deleteFile(String serverUrl, String token, String fileId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl.replaceAll("/$", "") + "/api/files/" + fileId))
                .header("Authorization", "Bearer " + token)
                .DELETE()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2 && response.statusCode() != 204) {
            throw new IllegalStateException(response.body());
        }
    }

    private JsonNode safeRead(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private MultipartBody buildMultipartBody(String boundary, String relativePath, byte[] fileBytes, String folderId, LongConsumer progressListener) throws Exception {
        String partHeader =
                "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"" + relativePath.substring(relativePath.lastIndexOf('/') + 1).replace("\"", "%22") + "\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n";
        String pathPart =
                "\r\n--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"relativePath\"\r\n\r\n" +
                relativePath + "\r\n";
        String folderPart = "";
        if (folderId != null && !folderId.isBlank()) {
            folderPart =
                    "--" + boundary + "\r\n" +
                    "Content-Disposition: form-data; name=\"folderId\"\r\n\r\n" +
                    folderId + "\r\n";
        }
        String partFooter = "--" + boundary + "--\r\n";

        byte[] headerBytes = partHeader.getBytes(StandardCharsets.UTF_8);
        byte[] pathBytes = pathPart.getBytes(StandardCharsets.UTF_8);
        byte[] folderBytes = folderPart.getBytes(StandardCharsets.UTF_8);
        byte[] footerBytes = partFooter.getBytes(StandardCharsets.UTF_8);
        return new MultipartBody(headerBytes, fileBytes, pathBytes, folderBytes, footerBytes, progressListener);
    }

    private static class MultipartBody {
        private final byte[][] parts;
        private final long totalBytes;
        private final LongConsumer progressListener;

        private MultipartBody(byte[] headerBytes, byte[] fileBytes, byte[] pathBytes, byte[] folderBytes, byte[] footerBytes, LongConsumer progressListener) {
            this.parts = new byte[][]{headerBytes, fileBytes, pathBytes, folderBytes, footerBytes};
            long total = 0L;
            for (byte[] part : parts) {
                total += part.length;
            }
            this.totalBytes = total;
            this.progressListener = progressListener;
        }

        private InputStream openStream() {
            Vector<InputStream> streams = new Vector<>();
            for (byte[] part : parts) {
                streams.add(new ByteArrayInputStream(part));
            }
            SequenceInputStream sequence = new SequenceInputStream(streams.elements());
            if (progressListener == null) {
                return sequence;
            }
            return new ProgressInputStream(sequence, totalBytes, progressListener);
        }
    }

    private static class ProgressInputStream extends InputStream {
        private final InputStream delegate;
        private final long totalBytes;
        private final LongConsumer progressListener;
        private long transferred = 0L;

        private ProgressInputStream(InputStream delegate, long totalBytes, LongConsumer progressListener) {
            this.delegate = delegate;
            this.totalBytes = totalBytes;
            this.progressListener = progressListener;
        }

        @Override
        public int read() throws java.io.IOException {
            int value = delegate.read();
            if (value != -1) {
                report(1L);
            } else {
                report(totalBytes);
            }
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws java.io.IOException {
            int read = delegate.read(b, off, len);
            if (read > 0) {
                report(read);
            } else if (read == -1) {
                report(totalBytes);
            }
            return read;
        }

        @Override
        public void close() throws java.io.IOException {
            delegate.close();
        }

        private void report(long delta) {
            transferred += delta;
            progressListener.accept(Math.min(transferred, totalBytes));
        }
    }

    public static class AuthResult {
        public String token;
        public String username;
        public String userId;
    }
}
